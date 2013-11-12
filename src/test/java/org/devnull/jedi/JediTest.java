package org.devnull.jedi;

import org.apache.log4j.BasicConfigurator;
import org.devnull.jedi.mock.*;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.devnull.statsd_client.StatsObject;

import java.io.*;
import java.net.*;
import java.util.*;

import static org.testng.AssertJUnit.*;

public class JediTest extends JsonBase
{
	protected static Logger log = null;
	private static StatsObject so = StatsObject.getInstance();

	@BeforeMethod
	public void setUp() throws Exception
	{
		Properties logProperties = new Properties();

		logProperties.put("log4j.rootLogger", "INFO, stdout");
		logProperties.put("log4j.appender.stdout", "org.apache.log4j.ConsoleAppender");
		logProperties.put("log4j.appender.stdout.layout", "org.apache.log4j.EnhancedPatternLayout");
		logProperties.put("log4j.appender.stdout.layout.ConversionPattern", "%d [%F:%L] [%p] %C: %m%n");
		logProperties.put("log4j.appender.stdout.layout.ConversionPattern", "%d [%p] %C: %m%n");
		logProperties.put("log4j.appender.stdout.immediateFlush", "true");
		logProperties.put("log4j.category.org.apache.http.wire", "INFO, stdout");
		logProperties.put("log4j.additivity.org.apache.http.wire", false);

		BasicConfigurator.resetConfiguration();
		PropertyConfigurator.configure(logProperties);

		log = Logger.getLogger(JediTest.class);
	}

	@Test
	public void testRun() throws Exception
	{
		so.clear();
		String[] args = {"-c", "src/test/resources/test.conf", "-l", "src/test/resources/log4j.conf"};
		Jedi jedi = new Jedi(args);
		Thread jediThread = new Thread(jedi, "Jedi");
		jediThread.start();

		//
		// give it time to start up
		//
		Thread.sleep(200);

		//
		// set up Mock API Server
		//
		MockAPIServer mock = new MockAPIServer(new GoodReplyServlet(), true, "foo", "bar");

		//
		// make socket connection and test request/response
		//
		Socket socket = new Socket();

		try
		{
			socket.connect(new InetSocketAddress("localhost", 5300));
			log.debug("connected, sending lookup request");

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

			for (int i = 0; i < 1000; i++)
			{
				socket.getOutputStream().write("{\"method\":\"lookup\", \"parameters\":{\"qtype\":\"ANY\",\"qname\":\"foo.bar.baz\"}}\n".getBytes());
				String line = reader.readLine();
				assertTrue(line, "{\"result\":[{\"qname\":\"foo.bar.baz\",\"qtype\":\"A\",\"content\":\"1.1.1.1\",\"ttl\":100,\"priority\":0,\"auth\":1},{\"qname\":\"foo.bar.baz\",\"qtype\":\"AAAA\",\"content\":\"2001:fefe\",\"ttl\":100,\"priority\":0,\"auth\":1}]}".equals(line));
			}

			socket.close();
		}
		catch (Exception e)
		{
			log.warn(e);
			e.printStackTrace();
			assertTrue(e.getMessage(), false);
		}
		finally
		{
			socket = null;
			mock.shutdown();
		}

		jedi.shutdown();
		jediThread.join(2000L);
		assertTrue(!jediThread.isAlive());

		//
		// confirm StatsObject contains stuff
		//
		Map<String, Long> soMap = new TreeMap<String, Long>(so.getMapAndClear());
		String soMapString = mapper.writeValueAsString(soMap);

		log.debug(soMapString);

		Thread.sleep(100);
	}
}
