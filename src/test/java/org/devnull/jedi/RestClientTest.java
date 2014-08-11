package org.devnull.jedi;

import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.devnull.jedi.configs.JediConfig;
import org.devnull.jedi.mock.*;
import org.devnull.jedi.records.*;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;

import static org.testng.AssertJUnit.*;

public class RestClientTest
{
	protected static Logger log = null;

	@BeforeClass
	public void setUp() throws Exception
	{
		Properties logProperties = new Properties();

		logProperties.put("log4j.rootLogger", "DEBUG, stdout");
		logProperties.put("log4j.appender.stdout", "org.apache.log4j.ConsoleAppender");
		logProperties.put("log4j.appender.stdout.layout", "org.apache.log4j.EnhancedPatternLayout");
		logProperties.put("log4j.appender.stdout.layout.ConversionPattern", "%d [%F:%L] [%p] %C: %m%n");
		logProperties.put("log4j.appender.stdout.layout.ConversionPattern", "[%p] %C{1}: %m%n");
		logProperties.put("log4j.appender.stdout.immediateFlush", "true");
		logProperties.put("log4j.category.org.apache.http.wire", "INFO, stdout");
		logProperties.put("log4j.additivity.org.apache.http.wire", false);
		logProperties.put("log4j.category.org.apache.http.impl.conn.LoggingManagedHttpClientConnection", "INFO, stdout");
		logProperties.put("log4j.additivity.org.apache.http.impl.conn.LoggingManagedHttpClientConnection", false);

		BasicConfigurator.resetConfiguration();
		PropertyConfigurator.configure(logProperties);

		log = Logger.getLogger(RestClientTest.class);
	}

	@Test
	public void testConstructor() throws Exception
	{
		RestClient client = null;

		try
		{
			client = new RestClient(null);
			assertTrue(false);
		}
		catch (Exception e)
		{
			assertTrue(e.getMessage(), e.getMessage().equals("config argument is null"));
		}

		try
		{
			client = new RestClient(new JediConfig());
			assertNotNull(client);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testSetHostname() throws Exception
	{
		try
		{
			RestClient client = new RestClient(new JediConfig());
			client.setHostname("foobarbaz");
			assertTrue(client.getHostname(), "foobarbaz".equals(client.getHostname()));
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testCall() throws Exception
	{
		RestClient client = null;

		/**
		 * test 1: expect NPE because hostname is not set
		 */

		try
		{
			log.info("testing failure when no hostname is set");
			client = new RestClient(new JediConfig());
			DNSRecordSet r = client.call();
			assertTrue(false);
		}
		catch (NullPointerException npe)
		{
			assertTrue(true);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}

		/**
		 * test 2: expect null record because there's no server to talk to on the default host/port
		 */
		try
		{
			log.info("testing against a server that is rejecting connections");
			client.setHostname("foo.bar.baz");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}

		/**
		 * test 3: set up a mock server that does not support authentication
		 */
		MockAPIServer mock = null;
		client.setHostname("foo.bar.baz");

		try
		{
			log.info("testing against an API server that doesn't support authentication");
			mock = new MockAPIServer(new HelloServlet(), false, null, null);
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 4: set up a mock server that has wrong authentication
		 */

		try
		{
			log.info("testing against an API server with mismatched authentication");
			mock = new MockAPIServer(new HelloServlet(), true, "bar", "foo");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 5: set up a mock server that has correct auth but gives a non-200 reply
		 */

		try
		{
			log.info("testing against an API server that returns 404");
			mock = new MockAPIServer(new BadReplyServlet(), true, "foo", "bar");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 6: set up a mock server that gives a 200 reply but an empty response body
		 */

		try
		{
			log.info("testing against an API sever that gives an empty response");
			mock = new MockAPIServer(new EmptyReplyServlet(), true, "foo", "bar");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 7: set up a mock server that gives a reply that is too long
		 */

		try
		{
			log.info("testing against an API server that gives a too long reply");
			mock = new MockAPIServer(new TooLongReplyServlet(), true, "foo", "bar");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 8: set up a mock server that gives a reply that does not parse correctly
		 */

		try
		{
			log.info("testing against a non-json reply");
			mock = new MockAPIServer(new HelloServlet(), true, "foo", "bar");
			DNSRecordSet r = client.call();
			assertNull(r);
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}

		/**
		 * test 9: set up a mock server that gives a reply that does parse correctly, expect the correct
		 * 	DNSRecord with a relatively current timestamp.
		 */
		try
		{
			log.info("testing against a good reply");
			mock = new MockAPIServer(new GoodReplyServlet(), true, "foo", "bar");
			DNSRecordSet r = client.call();
			assertNotNull(r);
			assertTrue(r.toString(), r.getTTL() == 100);
			assertNotNull(r.getRecords());
			List<Record> ips = r.getRecords();
			log.debug("Record Set: " + r.toString());
			assertTrue(r.toString(), ips.size() == 4);
			assertTrue(r.toString(), ips.get(0) instanceof SOARecord);
			assertTrue(r.toString(), ips.get(0).getType().equals("SOA"));
			assertTrue(r.toString(), ips.get(0).getAddress().equals("foo.bar.baz me.foo.bar.baz 2012080849 7200 3600 1209600 3600"));
			assertTrue(r.toString(), ips.get(1) instanceof ARecord);
			assertTrue(r.toString(), ips.get(1).getType().equals("A"));
			assertTrue(r.toString(), ips.get(1).getAddress().equals("1.1.1.1"));
			assertTrue(r.toString(), ips.get(2) instanceof AAAARecord);
			assertTrue(r.toString(), ips.get(2).getType().equals("AAAA"));
			assertTrue(r.toString(), ips.get(2).getAddress().equals("2001::fefe"));
			assertTrue(r.toString(), ips.get(3) instanceof MXRecord);
			assertTrue(r.toString(), ips.get(3).getType().equals("MX"));
			assertTrue(r.toString(), ips.get(3).getAddress().equals("mail1.bar.com"));
			assertEquals(((MXRecord)ips.get(3)).getPriority(), 10);

			assertNotNull(r.getSOA());

			SOARecord soa = r.getSOA();

			assertEquals(soa.getAddress(), "foo.bar.baz me.foo.bar.baz 2012080849 7200 3600 1209600 3600");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			fail(e.getMessage());
		}
		finally
		{
			mock.shutdown();
		}
	}
}
