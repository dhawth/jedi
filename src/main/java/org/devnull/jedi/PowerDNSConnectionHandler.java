package org.devnull.jedi;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.cache.Cache;
import org.apache.log4j.Logger;
import org.devnull.jedi.configs.JediConfig;
import org.devnull.jedi.records.MXRecord;
import org.devnull.jedi.records.Record;
import org.devnull.jedi.records.SOARecord;
import org.devnull.statsd_client.StatsObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The PowerDNSConnectionHandler reads input lines (strings) from the client socket, parses
 * out the hostname that is being requested, and attempts to look up that hostname in the
 * local in-memory LRU cache then from the Dynamo API server.
 * On finding the record, it updates any other caches that have higher priority.
 * <p/>
 * When a Handler cannot find a record in the local cache, it submits a job to
 * a ThreadPool that will execute the RestClient and return an DNSRecord as a Future.  This way
 * the ThreadPool size limit also limits the amount of clients the REST server must deal with at any
 * time.  This gives us more predictable scalability.
 */

public class PowerDNSConnectionHandler extends JsonBase implements Runnable
{
	private static final Logger log = Logger.getLogger(PowerDNSConnectionHandler.class);
	private static final StatsObject so = StatsObject.getInstance();

	private JediConfig config = null;
	private Socket socket = null;
	private RestClient restClient = null;
	private Future<DNSRecordSet> future = null;
	private ExecutorService apiPool = null;
	private Cache<String, DNSRecordSet> cache = null;
	private StringBuffer sb = new StringBuffer(1024);

	/**
	 * Constructor
	 *
	 * @param client  The client Socket object
	 * @param config  The JediConfig
	 * @param apiPool The ExecutorService used to execute RestClient requests
	 * @param cache   The results Cache
	 * @throws Exception On issues setting up an RestClient using the config object
	 */
	public PowerDNSConnectionHandler(Socket client,
					 final JediConfig config,
					 final ExecutorService apiPool,
					 final Cache<String, DNSRecordSet> cache)
		throws Exception
	{
		if (log.isDebugEnabled())
		{
			log.debug("ConnectionHandler created to handle requests from socket: " + client.toString());
		}

		this.socket = client;
		this.config = config;
		this.apiPool = apiPool;
		this.cache = cache;
		restClient = new RestClient(config);
	}

	/**
	 * run() loops over the socket and reads input lines for requests and writes response lines.  Requests and
	 * responses are all in JSON.  It does this until the socket is detected to be closed, until there is an
	 * error parsing or reading input or writing output.
	 */
	public void run()
	{
		DNSRecordSet dnsRecordSet;
		InputStream inStream = null;
		OutputStream outStream = null;
		BufferedReader reader = null;
		BufferedWriter writer = null;
		String hostname;
		PDNSRequest request;
		long cache_timeout = config.cache_timeout * 1000;
		String requestLine = "";

		if (socket.isClosed())
		{
			so.increment("PDNSCH.socket_closed_before_first_read");
			return;
		}

		try
		{
			inStream = socket.getInputStream();
			outStream = socket.getOutputStream();
			reader = new BufferedReader(new InputStreamReader(inStream));
			writer = new BufferedWriter(new OutputStreamWriter(outStream));

			while (!socket.isClosed())
			{
				/**
				 * Documentation of Request API
				 *
				 * The following methods are used:
				 74
				 75	Method: lookup
				 76	Parameters: qtype, qname (domain), remote, local, real-remote, zone_id
				 77	Reply: array of <qtype,qname,content,ttl,domaind_id,priority,scopeMask>
				 78	Optional values: domain_id and scopeMask
				 79
				 */

				if (log.isDebugEnabled())
				{
					log.debug("waiting to read request from socket");
				}

				try {
					requestLine = reader.readLine();
				} catch (IOException e){
					//expected if this connection is using a unix socket, PowerDNS doesn't tell us when it's
					//done using a connection so we need a timeout to clean up correctly.
					log.debug("socket timed out");
					so.increment("PDNSCH.unix_socket_timed_out");
					break;
				}


				if (requestLine == null)
				{
					log.debug("end of input stream has been reached, assuming socket is closed");
					break;
				}

				if (log.isDebugEnabled())
				{
					log.debug("received request line from socket: " + requestLine);
				}

				so.increment("PDNSCH.requests_received.total");
				long start = System.nanoTime();

				try
				{
					//
					// if the request is for calculateSOASerial, its parameters section will not be
					// a map of string:string but string:object, and thus it will fail when we try
					// to map it ot a PDNSRequest object.  Catch that case early and return an empty
					// (false) answer, and move on.
					// The same behavior should apply to getDomainMetadata requests as well.
					//
					if (requestLine.contains("\"method\":\"calculateSOASerial\"") ||
					    requestLine.contains("\"method\":\"getDomainMetadata\""))
					{
						so.increment("PDNSCH.requests_received.unsupported_method");
						writeEmptyRecordToSocket(writer);
						continue;
					}

					//
					// read line into a PDNSRequest object
					//
					request = mapper.readValue(requestLine, PDNSRequest.class);

					if (!validateRequest(request))
					{
						so.increment("PDNSCH.requests_received.invalid");

						if (log.isDebugEnabled())
						{
							log.debug(
								"invalid request received from powerdns, closing socket");
						}
						socket.close();
						break;
					}

					so.increment("PDNSCH.requests_received.valid");
					so.increment("PDNSCH.requests_received." + request.getMethod());

					if (request.getMethod().equals("initialize"))
					{
						if (log.isDebugEnabled())
						{
							log.debug(
								"got an initialize request from powerdns, replying OK");
						}
						writeOKToSocket(writer);
						continue;
					}

					log.debug("Received request: " + request);
					so.increment("PDNSCH.records_requested." + request.getQType());

					hostname = request.getDomain().toLowerCase();

					//
					// see if it is a SOA or NS request
					//
					if ("SOA".equals(request.getQType()))
					{
						//
						// typical SOA request:
						//
						// {"method":"lookup","parameters":{"qtype":"SOA","qname":"foo.bar.baz","remote":"127.0.0.1","local":"0.0.0.0","real-remote":"127.0.0.1/32","zone-id":"-1"}}
						//
						log.debug("writing SOA response for request: " + request);
						writeSOAResponse(writer, request);
						continue;
					}

					if ("NS".equals(request.getQType()))
					{
						writeEmptyRecordToSocket(writer);
						continue;
					}

					//
					// see if it is in local LRU cache
					//
					if (cache != null)
					{
						if (log.isDebugEnabled())
						{
							log.debug("looking up hostname " + hostname + " in LRU");
						}

						dnsRecordSet = cache.getIfPresent(hostname);

						so.increment("PDNSCH.cache_lookups");

						if (dnsRecordSet == null)
						{
							so.increment("PDNSCH.cache_misses");
						}
						else
						{
							so.increment("PDNSCH.cache_hits");

							if (log.isDebugEnabled())
							{
								log.debug("found cached record for hostname");
							}

							//
							// test to see if record is too old
							//
							if (dnsRecordSet.getTimestamp() < (Now
								.getNow() - cache_timeout))
							{
								if (log.isDebugEnabled())
								{
									log.debug(
										"cache record for hostname " + hostname + " is too old, removing it");
								}
								so.increment("PDNSCH.cache_expirations");
								cache.invalidate(hostname);
							}
							else
							{
								if (log.isDebugEnabled())
								{
									log.debug(
										"cache record for hostname " + hostname + " is valid, sending it");
								}

								so.increment("PDNSCH.answers_served_from_cache");

								writeRecordToSocket(writer, request, dnsRecordSet);

								continue;
							}
						}
					}

					//
					// not in Cache, see if we can fetch it from the Master Controller
					// I've tried to put as much code that might wait into the RestClient, that way
					// we can have a super-timeout that covers all of it via the Future.get(...)
					// method.
					//
					if (log.isDebugEnabled())
					{
						log.debug("submitting RestClient to the execution pool");
					}

					restClient.setHostname(hostname);
					future = apiPool.submit(restClient);

					so.increment("PDNSCH.API_requests_submitted");

					try
					{
						long futureStart = System.nanoTime();

						if (log.isDebugEnabled())
						{
							log.debug("waiting for return from RestClient");
						}

						dnsRecordSet =
							future.get(config.rest_fetch_timeout, TimeUnit.MILLISECONDS);

						so.timing("PDNSCH.future_wait", (System.nanoTime() - futureStart) / 1000);

						if (log.isDebugEnabled())
						{
							log.debug("got dnsRecord from RestClient: " + dnsRecordSet);
						}

						if (dnsRecordSet == null)
						{
							so.increment("PDNSCH.null_futures");

							//
							// we have nothing to write to the socket, empty response.
							// this could be from a timeout, lack of entry for the fqdn, or any
							// other error in processing.
							//
							writeEmptyRecordToSocket(writer);
							continue;
						}

						if (log.isDebugEnabled())
						{
							log.debug(
								"adding cache entry for hostname " + hostname + " to the LRU");
						}

						so.increment("PDNSCH.successful_futures");

						if (cache != null)
						{
							cache.put(hostname, dnsRecordSet);
							so.increment("PDNSCH.cache_inserts");
						}

						writeRecordToSocket(writer, request, dnsRecordSet);
					}
					catch (TimeoutException te)
					{
						if (log.isDebugEnabled())
						{
							log.debug(
								"Future timed out, cancelling it and returning empty response");
						}
						so.increment("PDNSCH.futures_exceptions.TimeoutException");
						future.cancel(true);
						writeEmptyRecordToSocket(writer);
					}
					catch (CancellationException ce)
					{
						if (log.isDebugEnabled())
						{
							log.debug("Future threw a CancellationException");
						}
						so.increment("PDNSCH.futures_exceptions.CancellationException");
						writeEmptyRecordToSocket(writer);
					}
					catch (ExecutionException ee)
					{
						if (log.isDebugEnabled())
						{
							log.debug("Future threw an ExecutionException: " + ee);
						}
						so.increment("PDNSCH.futures_exceptions.ExecutionException");
						writeEmptyRecordToSocket(writer);
					}
					catch (InterruptedException ie)
					{
						if (log.isDebugEnabled())
						{
							log.debug("Future threw an InterruptedException");
						}
						so.increment("PDNSCH.futures_exceptions.InterruptedException");
						future.cancel(true);
						writeEmptyRecordToSocket(writer);
					}
					finally
					{
						future = null;
					}
				}
				finally
				{
					so.timing("PDNSCH.request_processing", (System.nanoTime() - start) / 1000);
				}
			}

			if (log.isDebugEnabled())
			{
				log.debug("closing socket and returning, either because socket was closed or we got an odd exception");
			}

			//
			// end of while (!socket.isClosed())
			//
		}
		catch (JsonMappingException e)
		{
			so.increment("PDNSCH.exceptions.JsonMappingException");
			log.warn("caught json mapping exception: " + e, e);
			log.warn("json mapping exception was for input: " + requestLine);
		}
		catch (Exception e)
		{
			so.increment("PDNSCH.exceptions");
			log.warn("threw exception in run(): " + e, e);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (IOException e)
				{
				}
			}
			if (writer != null)
			{
				try
				{
					writer.close();
				}
				catch (IOException e)
				{
				}
			}

			if (inStream != null)
			{
				try
				{
					inStream.close();
				}
				catch (IOException e)
				{
				}
			}
			if (outStream != null)
			{
				try
				{
					outStream.close();
				}
				catch (IOException e)
				{
				}
			}

			try
			{
				socket.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
			}
		}
	}
	//
	// end of run()
	//

	/**
	 * Writes a hardcoded SOA response for all domains for which a SOA record is requested.
	 *
	 * @param writer    The BufferedWriter created on the client socket's output stream
	 * @param request   The original PDNSRequest read in from the client socket.
	 * @throws Exception When there are issues writing to the socket.
	 */
	private void writeSOAResponse(final BufferedWriter writer, final PDNSRequest request) throws Exception
	{
		if (log.isDebugEnabled())
		{
			log.debug("giving powerdns a positive SOA response");
		}

		/*
		 * SOA Request:
		 *
		 * {
		 	"parameters" : {
		 		"zone-id" : "-1",
		 		"qname" : "foo.bar.baz",
		 		"type" : "SOA",
		 		"real-remote" : "127.0.0.1/32",
		 		"local" : "0.0.0.0",
		 		"remote" : "127.0.0.1"
		 	},
		 	"method" : "lookup"
		 * }
		 */

		/*
		 * SOA Response:
		 *
		 140	Reply:
		 141
		 142	{
		 143	  "result":
		 144	   [
		 145	     { "qtype": "SOA",
		 146	       "qname": "example.com",
		 147	       "content": "dns1.icann.org. hostmaster.icann.org. 2012080849 7200 3600 1209600 3600",
		 148	       "ttl": 3600,
		 149	       "priority": 0,
		 150	       "domain_id": -1
		 151	     }
		 152	   ]
		 153	}
		 */

		sb.setLength(0);
		sb.append("{\"result\":[");

		sb.append("{");
		sb.append("\"qtype\":\"SOA\",");
		sb.append("\"qname\":\"").append(request.getDomain()).append("\",");
		sb.append("\"content\":\"").append("ns1.prod.pertino.com. eng-devops.pertino.com. 1 7200 900 1209600 86400").append("\",");
		sb.append("\"ttl\":3600,");
		sb.append("\"priority\":0,");
		sb.append("\"domain_id\":-1");
		sb.append("}");

		//
		// close the array and hash
		//
		sb.append("]}");

		if (log.isDebugEnabled())
		{
			log.debug("writing response: " + sb.toString());
		}

		sb.append("\n");

		writer.write(sb.toString());
		writer.flush();

		so.increment("PDNSCH.positive_replies_sent");
	}

	/**
	 * Writes an DNSRecord object to the given writer for the given PDNSRequest.
	 * This function is used when we have a positive (successful) reply for the request.
	 *
	 * @param writer    The BufferedWriter created on the client socket's output stream
	 * @param request   The original PDNSRequest read in from the client socket.
	 * @param recordSet The DNSRecordSet object comprising the result records for the given request.
	 * @throws Exception When there are issues writing to the socket.
	 */
	private void writeRecordToSocket(final BufferedWriter writer, final PDNSRequest request,
					 final DNSRecordSet recordSet) throws Exception
	{
		if (log.isDebugEnabled())
		{
			log.debug("giving powerdns a positive response");
		}

		/**
		 * Documentation of Reply API
		 * <p/>
		 * 3.2. Replies
		 * <p/>
		 * 64	You *must* always reply with JSON hash with at least one key, 'result'. This
		 * 65	must be false if the query failed. Otherwise it must conform to the expected
		 * 66	result.
		 * 67
		 * 68	You can optionally add 'log' array, each line in this array will be logged in
		 * 69	PowerDNS.
		 *
		 140	Reply:
		 141
		 142	{
		 143	  "result":
		 144	   [
		 145	     { "qtype": "SOA",
		 146	       "qname": "example.com",
		 147	       "content": "dns1.icann.org. hostmaster.icann.org. 2012080849 7200 3600 1209600 3600",
		 148	       "ttl": 3600,
		 149	       "priority": 0,
		 150	       "domain_id": -1
		 151	     }
		 152	   ]
		 153	}
		 */

		sb.setLength(0);
		sb.append("{\"result\":[");

		//
		// foreach IP, reply
		//
		for (Record r : recordSet.getRecords())
		{
			//
			// skip SOA records.  only return these when they are asked for
			//
			if (r instanceof SOARecord)
			{
				continue;
			}

			/*
			rr(request.getDomain(), ip.getType(), ip.getAddress(), ttl)

			def rr(qname, qtype, content, ttl, priority = 0, auth = 1)
			  {:qname => qname, :qtype => qtype, :content => content, :ttl => ttl, :priority => priority, :auth => auth}
			end
			*/

			sb.append("{");
			sb.append("\"qname\":\"").append(request.getDomain()).append("\",");
			sb.append("\"qtype\":\"").append(r.getType()).append("\",");
			sb.append("\"content\":\"").append(r.getAddress()).append("\",");
			sb.append("\"ttl\":").append(recordSet.getTTL()).append(",");

			if (r.getType().equals("MX"))
			{
				sb.append("\"priority\":").append(((MXRecord) r).getPriority()).append(",");
			}
			else
			{
				sb.append("\"priority\":0,");
			}

			sb.append("\"auth\":1");
			sb.append("},");
		}

		//
		// remove the trailing comma
		//
		sb.deleteCharAt(sb.length() - 1);

		//
		// close the array and hash
		//
		sb.append("]}");

		if (log.isDebugEnabled())
		{
			log.debug("writing response: " + sb.toString());
		}

		sb.append("\n");

		writer.write(sb.toString());
		writer.flush();

		so.increment("PDNSCH.positive_replies_sent");
	}

	/**
	 * Writes an empty (negative) reply to the socket, e.g. {"result":false}
	 * This is used when there is no answer known, either because it DNE or because of a timeout fetching it, etc.
	 *
	 * @param writer The BufferedWriter created on the socket's output stream.
	 * @throws Exception On errors writing to the socket.
	 */
	private void writeEmptyRecordToSocket(final BufferedWriter writer) throws Exception
	{
		if (log.isDebugEnabled())
		{
			log.debug("giving powerdns a negative response");
		}
		writer.write("{\"result\":false}\n");
		writer.flush();
		so.increment("PDNSCH.negative_replies_sent");
	}

	/**
	 * Writes an empty (positive) reply to the socket, e.g. {"result":true}
	 * This is used only for answering initialization requests from PowerDNS.
	 *
	 * @param writer The BufferedWriter created on the socket's output stream.
	 * @throws Exception On errors writing to the socket.
	 */
	private void writeOKToSocket(final BufferedWriter writer) throws Exception
	{
		writer.write("{\"result\":true}\n");
		writer.flush();
		so.increment("PDNSCH.empty_replies_sent");
	}

	/**
	 * Checks to make sure the method is set and is either initialize|lookup, and that there is a hostname
	 * in the lookup request.
	 *
	 * @param r The PDNSRequest object received from the client socket.
	 * @return True if the request is valid, false if it is not.
	 */
	private boolean validateRequest(final PDNSRequest r)
	{
		if (log.isDebugEnabled())
		{
			log.debug("validating request: " + r);
		}

		if ("initialize".equals(r.getMethod()))
		{
			return true;
		}

		//
		// checks for nullity and mismatch at the same time
		//
		if (!"lookup".equals(r.getMethod()))
		{
			so.increment("PDNSCH.invalid_requests.bad_method");
			return false;
		}

		if (r.getDomain() == null)
		{
			so.increment("PDNSCH.invalid_requests.missing_fqdn");
			return false;
		}

		return true;
	}
}
