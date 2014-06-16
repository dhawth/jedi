package org.devnull.jedi;

import com.fasterxml.jackson.core.JsonParseException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.auth.params.AuthPNames;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.BasicClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.devnull.jedi.configs.JediConfig;
import org.devnull.statsd_client.StatsObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The RestClient is an object that is submitted to a ThreadPool to execute a given request against the MC server.
 * <p/>
 * There is a safety measure built into this class that prevents it from attempting to read any response that is
 * larger than 8k bytes.
 */
public class RestClient extends JsonBase implements Callable<DNSRecordSet>
{
	private static final Logger log = Logger.getLogger(RestClient.class);

	/**
	 * The longest response size allowed from the REST server, to prevent any issues that may arise otherwise.
	 */
	private static final long MAX_REST_RESPONSE_LENGTH_ALLOWED = 8192;

	/**
	 * The version of the API we are using.
	 */
	private static final int API_VERSION = 1;

	/**
	 * A counter to keep track of how many of these things we've created, for id purposes.
	 */
	private static final AtomicLong instanceCounter = new AtomicLong(0);
	/**
	 * for statsd stats
	 */
	private static final StatsObject so = StatsObject.getInstance();
	private String instanceName = null;
	/**
	 * variables related to the fetching of data from the REST servers
	 */
	private HttpHost httpHost = null;
	private DefaultHttpClient httpClient = null;

	/**
	 * the hostname we are looking up, not the REST server hostname that we connect to in order to do the lookup.
	 */
	private String hostname = null;

	/**
	 * Constructor
	 *
	 * @param config The main JediConfig object that includes REST server related config items.
	 * @throws Exception When there are issues setting up the HTTP client objects using the config.
	 */
	public RestClient(final JediConfig config)
		throws Exception
	{
		try
		{
			if (config == null)
			{
				throw new IllegalArgumentException("config argument is null");
			}

			if (config.rest_server_hostname == null || config.rest_username == null || config.rest_password == null)
			{
				throw new IllegalArgumentException(
					"rest_server_hostname, rest_username, or rest_password is null");
			}

			httpClient = new DefaultHttpClient(new BasicClientConnectionManager());
			httpClient.getParams()
				  .setParameter(AuthPNames.PROXY_AUTH_PREF, Arrays.asList(AuthPolicy.DIGEST));
			httpClient.getCredentialsProvider().setCredentials
				(
					new AuthScope(config.rest_server_hostname, config.rest_server_port),
					new UsernamePasswordCredentials(config.rest_username, config.rest_password)
				);

			httpHost = new HttpHost(config.rest_server_hostname, config.rest_server_port);

			//
			// set up a retry handler that overrides the default one, this one does not do any retries
			//
			HttpRequestRetryHandler myRetryHandler = new HttpRequestRetryHandler()
			{
				public boolean retryRequest(
					IOException exception,
					int executionCount,
					HttpContext context)
				{
					return false;
				}
			};

			httpClient.setHttpRequestRetryHandler(myRetryHandler);

			instanceName = "RestClient" + instanceCounter.incrementAndGet();

			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " initialized");
			}
		}
		catch (Exception e)
		{
			so.increment("RestClient.exceptions_in_constructor");
			throw e;
		}

		so.increment("RestClient.created");
	}

	/**
	 * getter for the hostname
	 *
	 * @return the current hostname that is being looked up by this object.
	 */
	public String getHostname()
	{
		return hostname;
	}

	/**
	 * setter for setting the hostname that is to be looked up by this client when it is submitted to the execution service.
	 *
	 * @param hostname the hostname to ask the REST server about
	 */
	public void setHostname(final String hostname)
	{
		this.hostname = hostname;
	}

	/**
	 * Called by the ExecutorService when this object is submitted for execution.  Attempts to fetch records
	 * for the {@hostname}, for both IPv4 and IPv6, and returns those records in an DNSRecord object.
	 * Requests the FQDN data object via URI with a version number /fqdn/1/$hostname
	 *
	 * @return DNSRecord populated with data, or null if no record was found.
	 * @throws Exception If there are errors processing the http get, interruptions in execution, etc.
	 */
	@Override
	public DNSRecordSet call() throws Exception
	{
		so.increment("RestClient.calls");

		if (log.isDebugEnabled())
		{
			log.debug(instanceName + " starting execution");
		}

		if (hostname == null)
		{
			throw new NullPointerException("hostname has not been set, is null");
		}

		HttpEntity entity = null;

		String path = "/fqdn/" + API_VERSION + "/" + hostname;

		try
		{
			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " requesting URI: " + path);
			}

			so.increment("RestClient.fetches_attempted");

			HttpResponse response = httpClient.execute(httpHost, new HttpGet(path));
			entity = response.getEntity();

			int status = response.getStatusLine().getStatusCode();

			so.increment("RestClient.return_codes." + status);

			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " query for fqdn " + hostname + " got return code: " + status);
			}

			if (status != 200)
			{
				if (log.isDebugEnabled())
				{
					log.debug(instanceName + " returning null because we didn't get a 200 OK");
				}

				if (entity != null)
				{
					entity.getContent().close();
				}

				so.increment("RestClient.returned_null.bad_status_code");
				return null;
			}

			if (entity == null)
			{
				if (log.isDebugEnabled())
				{
					log.debug(
						instanceName + " query for " + hostname + " returned an empty content body, returning null");
				}

				so.increment("RestClient.returned_null.empty_query_body");
				return null;
			}

			//
			// I thought it wise to put a safety valve in here.
			// 8k is big enough, right?
			//
			long len = entity.getContentLength();

			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " got a response " + len + " bytes long");
			}

			if (len == -1)
			{
				//
				// no content-length header
				//
			}
			else if (len < 0 || len > MAX_REST_RESPONSE_LENGTH_ALLOWED)
			{
				if (log.isDebugEnabled())
				{
					log.debug(
						instanceName + " query for " + hostname + " returned an empty or too large content body");
				}

				so.increment("RestClient.returned_null.content_too_long");
				return null;
			}

			InputStream instream = entity.getContent();

			try
			{
				DNSRecordSet r = mapper.readValue(instream, DNSRecordSet.class);
				r.setTimestamp(Now.getNow());

				if (log.isDebugEnabled())
				{
					log.debug(instanceName + " got a valid response, returning it!");
				}

				so.increment("RestClient.valid_responses");

				return r;
			}
			catch (JsonParseException jpe)
			{
				so.increment("RestClient.JsonParseExceptions");
				so.increment("RestClient.total_exceptions");

				if (log.isDebugEnabled())
				{
					log.debug(instanceName + " got a JsonParseException reading the reply: " + jpe);
				}
			}
			catch (Exception e)
			{
				so.increment("RestClient.total_exceptions");

				if (log.isDebugEnabled())
				{
					log.debug(instanceName + " got exception reading reply content body: " + e);
				}
				e.printStackTrace();
			}
			finally
			{
				instream.close();
			}
		}
		catch (NoHttpResponseException e)
		{
			if (log.isDebugEnabled())
			{
				log.debug("Request timed out fetching record for " + hostname + " from REST Server");
			}

			so.increment("RestClient.null_returns.request_timeout");
			return null;
		}
		catch (Exception e)
		{
			so.increment("RestClient.total_exceptions");
			log.info(
				instanceName + " got exception fetching record for " + hostname + " from REST server: " + e);
		}
		finally
		{
			EntityUtils.consume(entity);
		}

		if (log.isDebugEnabled())
		{
			log.debug(instanceName + " returning null at end of call()");
		}

		so.increment("RestClient.null_returns.bad_json");
		return null;
	}


}
