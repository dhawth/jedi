package org.devnull.jedi;

import com.fasterxml.jackson.core.JsonParseException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NoHttpResponseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.auth.params.AuthPNames;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicClientConnectionManager;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
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
	private CloseableHttpClient httpClient = null;

	/**
	 * The config object for Jedi, used for instantiating HttpClients
	 */
	private JediConfig config = null;

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

			this.config = config;

			httpClient = generateHttpClient();
			httpHost = new HttpHost(config.rest_server_hostname, config.rest_server_port);

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

	private CloseableHttpClient generateHttpClient()
	{
		if (log.isDebugEnabled())
		{
			log.debug(instanceName + ": Generating HTTP Client");
		}

		CredentialsProvider credsProvider = new BasicCredentialsProvider();

		credsProvider.setCredentials(
			new AuthScope(config.rest_server_hostname, config.rest_server_port),
			new UsernamePasswordCredentials(config.rest_username, config.rest_password)
		);

		RequestConfig requestConfig = RequestConfig.custom()
							   .setTargetPreferredAuthSchemes(
								   Arrays.asList(AuthSchemes.DIGEST))
							   .setSocketTimeout(new Long(config.rest_fetch_timeout)
										     .intValue())
							   .setConnectTimeout(
								   new Long(config.rest_fetch_timeout)
									   .intValue())
							   .setConnectionRequestTimeout(
								   new Long(config.rest_fetch_timeout)
									   .intValue())
							   .build();

		return HttpClients.custom()
				  .setConnectionManager(new BasicHttpClientConnectionManager())
				  .setDefaultCredentialsProvider(credsProvider)
				  .disableAutomaticRetries()
				  .setMaxConnTotal(1)
				  .setDefaultRequestConfig(requestConfig)
				  .build();
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

		if (hostname == null)
		{
			so.increment("RestClient.hostname_not_set_exception");
			throw new NullPointerException("hostname has not been set, is null");
		}

		long start = System.nanoTime();

		HttpEntity entity = null;

		/*
		Using the Fluent HC wrapper for the apache http client:
		does not support authentication, though.

		Response response = Request.Get(httpHost.toURI() + path).socketTimeout(1000).execute();
		int code = response.returnResponse().getStatusLine().getStatusCode();
		String content = response.returnContent().asString();
		*/

		try
		{
			so.increment("RestClient.fetches_attempted");

			String path = "/fqdn/" + API_VERSION + "/" + hostname;

			HttpGet httpGet = new HttpGet(path);

			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " requesting URI: " + httpGet.getURI());
			}

			CloseableHttpResponse response = null;
			int retryCount = 3;

			do
			{
				try
				{
					if (retryCount < 3)
					{
						log.info("Making attempt " + (3 - retryCount + 1) + " to fetch records");
					}

					response = httpClient.execute(httpHost, httpGet);
				}
				catch (IllegalStateException ise)
				{
					if (ise.getMessage().equals("Connection is still allocated"))
					{
						so.increment("RestClient.httpClientBugsCaught");
						log.info(
							"Caught 'Connection is still allocated' exception due to buggy Http client code, regenerating httpClient");
						httpClient.close();
						httpClient = generateHttpClient();
					}
				}
			}
			while (response == null && retryCount-- > 0);

			if (response == null)
			{
				log.error("Could not fetch records from Darkside, response is still null after 3 retries");
				return null;
			}

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

				so.increment("RestClient.returned_null.bad_status_code");
				return null;
			}

			entity = response.getEntity();

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
				// no content-length header, do not check to see if the length is too long
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

			try
			{
				DNSRecordSet r = mapper.readValue(entity.getContent(), DNSRecordSet.class);
				r.setTimestamp(Now.getNow());

				so.increment("RestClient.valid_responses");

				return r;
			}
			catch (JsonParseException jpe)
			{
				log.info(instanceName + " got a JsonParseException reading the reply", jpe);
				so.increment("RestClient.output_parsing_exceptions.JsonParseExceptions");
				so.increment("RestClient.returned_null.JsonParseExceptions");
				return null;
			}
			catch (Exception e)
			{
				log.info(instanceName + " got exception reading reply content body", e);
				so.increment("RestClient.output_parsing_exceptions.generic");
				so.increment("RestClient.returned_null.generic_exception_reading_output");
				return null;
			}
		}
		catch (NoHttpResponseException e)
		{
			if (log.isDebugEnabled())
			{
				log.debug(instanceName + " timed out fetching record for " + hostname);
			}

			so.increment("RestClient.exceptions.request_timeout");
			so.increment("RestClient.returned_null.request_timeouts");
			return null;
		}
		catch (Exception e)
		{
			log.info(
				instanceName + " got exception fetching record for " + hostname + " from REST server: ", e);
			so.increment("RestClient.exceptions.generic");
			so.increment("RestClient.returned_null.generic_request_exception");
			return null;
		}
		finally
		{
			EntityUtils.consume(entity);
			so.timing("RestClient.processing_time", (System.nanoTime() - start) / 1000);
		}
	}
}
