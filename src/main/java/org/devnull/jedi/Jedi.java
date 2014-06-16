package org.devnull.jedi;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.devnull.jedi.configs.JediConfig;
import org.devnull.statsd_client.Shipper;
import org.devnull.statsd_client.ShipperFactory;
import org.devnull.statsd_client.StatsObject;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main class for the server, reads arguments from command line.
 */
public final class Jedi extends JsonBase implements Runnable
{
	private static Logger log = null;
	private JediConfig config = null;
	private StatsObject so = StatsObject.getInstance();
	private Cache<String, DNSRecordSet> cache = null;

	/**
	 * Reads command line arguments and starts the service.
	 * Continues until shutdown() is called on it, useful for unit testing.
	 * <p/>
	 * Command line args:
	 * <p/>
	 * -l <log4j.conf>		Path to a log4j properties file, optional
	 * -c <jedi.conf>		Path to the json-based configuration file, required
	 *
	 * @param args The String[] array of the command-line arguments
	 * @throws Exception If there is an error reading the config files
	 */

	Jedi(final String[] args) throws Exception
	{
		//
		// set up a console logger until we've ready in the log4j config
		//
		BasicConfigurator.configure();
		log = Logger.getLogger(Jedi.class);

		for (int i = 0; i < args.length; i++)
		{
			if (args[i].trim().equals("-c"))
			{
				//
				// we found it.  Use the i+1'th arg as the filename.
				// leave the args on the command line, as they'll get ignored by the rest
				// of the processing.
				//

				String configFile = args[i + 1];
				config = mapper.readValue(new File(configFile), JediConfig.class);

				i++;
			}
			else if (args[i].trim().equals("-l"))
			{
				//
				// we found it.  Use the i+1'th arg as the filename.
				// leave the args on the command line, as they'll get ignored by the rest
				// of the processing.
				//
				i++;
				setupLogging(args[i]);
			}
			else
			{
				log.info("Unknown command line argument: " + args[i]);
			}
		}

		if (null == config)
		{
			throw new IllegalArgumentException("No configuration file specified on command line");
		}
	}

	/*
	     * main routine used when starting up Jedi, e.g.:
	     * java -jar jedi.jar -l log4j.conf -c jedi.conf
	     *
	     * @param args	a list of Strings, e.g. -l, log4j.conf, -c, jedi.conf
	     * @return	nothing
	     *
	     */
	public static void main(String args[])
	{
		try
		{
			BasicConfigurator.configure();
			Jedi p = new Jedi(args);
			p.run();
		}
		catch (Exception e)
		{
			log.error(e);
			e.printStackTrace();
			System.exit(1);
		}

		System.exit(0);
	}

	/*
	 * run() continues until shutdown() is called
	 */

	public void run()
	{
		try
		{
			//
			// instantiate the StatsdShipper and kick off the statsd shipper thread
			//
			Shipper shipper = ShipperFactory.getInstance(config.statsd_client_type);
			shipper.configure(mapper.writeValueAsString(config.statsd_config));
			Thread statsdShipperThread = new Thread(shipper, "StatsdShipper");
			statsdShipperThread.start();

			//
			// initialize LRU cache for storing records in memory
			//
			if (config.max_items_in_cache != null && config.max_items_in_cache > 0)
			{
				if (log.isDebugEnabled())
				{
					log.debug(
						"building LRU cache with " + config.max_items_in_cache + " max items");
				}

				cache = CacheBuilder.newBuilder()
						    .maximumSize(config.max_items_in_cache)
						    .build();
			}

			//
			// Initialize ThreadPool for REST Clients
			//
			ExecutorService apiPool = Executors.newFixedThreadPool(config.max_rest_client_threads);

			//
			// determine number of threads to allow for answering questions from PowerDNS.
			// config.tcp_worker_count should be >= the maximum number of client connections
			// expected from powerdns if you wish to override this value.
			//
			int poolSize = 50 * Runtime.getRuntime().availableProcessors();

			if (config.max_powerdns_connection_count != null)
			{
				poolSize = config.max_powerdns_connection_count;
			}

			if (log.isDebugEnabled())
			{
				log.debug(
					"building a fixed threadpool for answering socket connections from powerdns with " +
						poolSize + " threads");
			}

			//
			// Set up ServerSocket and answering ExecutorService
			//
			ExecutorService executor = Executors.newFixedThreadPool(poolSize);
			ServerSocket server = null;
			Thread unixSocketThread = null;

			try
			{
				if (config.unix_socket_path != null)
				{
					unixSocketThread = new Thread(new UnixSocketServer(config, executor, apiPool),
								      "UnixSocketServer");
					unixSocketThread.start();
				}

				server = new ServerSocket(config.jedi_listen_port);
				server.setSoTimeout(1000);

				Socket client = null;

				while (!Thread.currentThread().isInterrupted())
				{
					try
					{
						client = server.accept();

						if (log.isDebugEnabled())
						{
							log.debug(
								"starting a ConnectionHandler for client connection: " + client
									.toString());
						}

						so.increment("Jedi.connections_accepted");

						executor.execute(
							new PowerDNSConnectionHandler(client, config, apiPool, cache));
					}
					catch (InterruptedException e)
					{
						break;
					}
					catch (SocketTimeoutException e)
					{
					}
					catch (IOException e)
					{
						so.increment("Jedi.exceptions_in_connection_handling");
						log.info("exception handling client connection: " + e);
						e.printStackTrace();
					}
				}
			}
			catch (IOException e)
			{
				log.error("Error setting up ServerSocket and taking clients: " + e);
				log.fatal("Shutting down!");
				e.printStackTrace();
			}
			finally
			{
				if (server != null)
				{
					try
					{
						server.close();
					}
					catch (IOException e)
					{
						log.warn("Error closing ServerSocket: " + e);
					}
				}
			}

			log.info("shutting down");

			if (unixSocketThread != null)
			{
				log.info("shutting down unix server socket thread");
				unixSocketThread.interrupt();
				unixSocketThread.join(2000);
			}

			try
			{
				executor.shutdown();

				//
				// Wait a while for existing tasks to terminate
				//
				if (!executor.awaitTermination(2, TimeUnit.SECONDS))
				{
					//
					// cancel currently executing tasks
					//
					executor.shutdownNow();
					//
					// Wait a while for tasks to respond to being cancelled
					//
					if (!executor.awaitTermination(2, TimeUnit.SECONDS))
					{
						log.info("Pool did not terminate properly");
					}
				}
			}
			catch (InterruptedException ie)
			{
				executor.shutdownNow();
			}

			//
			// shut down the database API clients
			//

			shipper.shutdown();
			statsdShipperThread.join();
		}
		catch (NoClassDefFoundError e)
		{
			log.error("Exception in run(): " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
		catch (Exception e)
		{
			log.error("Exception in run(): " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
		finally
		{
			log.info("exit");
		}
	}

	/**
	 * Configures log4j using the passed-in log4j.conf properties file or the default
	 * log4j.conf included in the monolithic jarball.
	 *
	 * @param log4jConfig Path to the log4j config file on disk.
	 * @throws Exception If there are issues loading the log4j config file
	 */
	private void setupLogging(final String log4jConfig)
	{
		LogManager.resetConfiguration();

		//
		// load default log4j config included in jar file
		//
		if (null == log4jConfig)
		{
			PropertyConfigurator.configure(Jedi.class.getResource("/log4j.conf"));
		}
		else
		{
			//
			// load log4j config file
			//
			PropertyConfigurator.configure(log4jConfig);
		}

		log = Logger.getLogger(Jedi.class);
	}

	private class UnixSocketServer implements Runnable
	{
		private ExecutorService socketExecutorService;
		private ExecutorService apiExecutorService;
		private JediConfig config;
		private AFUNIXServerSocket server = null;

		public UnixSocketServer(final JediConfig config, final ExecutorService executorService,
					final ExecutorService apiPool)
			throws Exception
		{
			this.config = config;
			this.socketExecutorService = executorService;
			this.apiExecutorService = apiPool;

			server = AFUNIXServerSocket.newInstance();
			server.bind(new AFUNIXSocketAddress(new File(config.unix_socket_path)));
		}

		public void run()
		{
			try
			{
				while (!Thread.currentThread().isInterrupted())
				{
					try
					{
						Socket client = server.accept();

						if (log.isDebugEnabled())
						{
							log.debug(
								"starting a ConnectionHandler for client connection: " + client
									.toString());
						}

						so.increment("Jedi.connections_accepted");

						socketExecutorService.execute(
							new PowerDNSConnectionHandler(client, config,
										      apiExecutorService, cache));
					}
					catch (InterruptedException e)
					{
						log.debug("caught interrupt, leaving unix server socket runloop");
						break;
					}
					catch (Exception e)
					{
						so.increment("Jedi.exceptions_in_connection_handling");
						log.info("exception handling client connection", e);
					}
				}
			}
			finally
			{
				try
				{
					server.close();
				}
				catch (IOException e)
				{
					log.warn("Error closing Unix server socket", e);
				}

				server = null;
			}
		}
	}
}
