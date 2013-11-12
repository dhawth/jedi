package org.devnull.jedi.mock;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;

import javax.servlet.http.HttpServlet;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import org.apache.log4j.*;

public class MockAPIServer
{
	private Logger log = Logger.getLogger(MockAPIServer.class);
	private Server server = null;
	private Thread serverThread = null;

	/**
	 * Primary constructor.  The authentication information is required on server setup, else it would be
	 * an attribute of the handler.
	 *
	 * @param handler
	 * @param supportsAuth
	 * @param username
	 * @param password
	 * @throws Exception
	 */
	public MockAPIServer(HttpServlet handler, boolean supportsAuth, String username, String password) throws Exception
	{
		// Create a basic jetty server object that will listen on port 8080.  Note that if you set this to port 0
		// then a randomly available port will be assigned that you can either look in the logs for the port,
		// or programmatically obtain it for use in test cases.
		server = new Server(new InetSocketAddress("127.0.0.1", 8080));

		ServletContextHandler servletContextHandler = new ServletContextHandler();

		if (supportsAuth)
		{
			servletContextHandler.setSecurityHandler(getDigestAuthHandler(username, password));
		}

		//
		// servletContextHandler uses the longest prefix of the request URI
		// to determine which servlet to use to answer the request
		//
		servletContextHandler.setContextPath("/");
		servletContextHandler.addServlet(new ServletHolder(handler), "/*");

		server.setHandler(servletContextHandler);

		// Start things up! By using the server.join() the server thread will join with the current thread.
		// See "http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Thread.html#join()" for more details.
		serverThread = new Thread()
		{
			@Override
			public void run()
			{
				try
				{
					server.start();
					log.debug("jetty server started");
					server.join();
					log.debug("jetty server thread joined");
				}
				catch (Exception ie)
				{
				}
			}
		};

		serverThread.start();

		//
		// sleep .5s to let server start before client calls
		//
		Thread.sleep(500);
	}

	/**
	 * stop the server and join it with a 1s timeout
	 *
	 * @throws Exception
	 */
	public void shutdown() throws Exception
	{
		log.debug("telling jetty to stop");
		server.stop();
		log.debug("joining jetty server watcher thread");
		serverThread.join(1000);
		log.debug("Jetty server is shutdown");
	}

	private SecurityHandler getDigestAuthHandler(String username, String password)
	{
		final String[] roles = {"user"};
		final HashLoginService loginService = new HashLoginService("MyRealm");
		loginService.putUser(username, new Password(password), roles);
		final ConstraintSecurityHandler constraintSecurityHandler = new ConstraintSecurityHandler();
		final Constraint constraint = new Constraint();
		constraint.setName("auth");
		constraint.setAuthenticate(true);
		constraint.setRoles(roles);
		final ConstraintMapping constraintMapping = new ConstraintMapping();
		constraintMapping.setPathSpec("/");
		constraintMapping.setConstraint(constraint);
		constraintSecurityHandler.setConstraintMappings(Arrays.asList(constraintMapping),
			new HashSet<String>(Arrays.asList(roles)));
		constraintSecurityHandler.setAuthenticator(new DigestAuthenticator());
		constraintSecurityHandler.setLoginService(loginService);
		return constraintSecurityHandler;
	}
}
