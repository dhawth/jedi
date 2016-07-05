package org.devnull.jedi.configs;

import org.devnull.jedi.JsonBase;

import java.util.Map;

/**
 * JediConfig is the primary configuration file for Jedi.
 */
public class JediConfig extends JsonBase
{
	/**
	 * The default statsd shipper instantiated will be the NullStatsdShipper, which simply clears the
	 * StatsObject every second.
	 */
	public String statsd_client_type = null;

	/**
	 * A pointer to a map that holds the contents of a UDPStatsdShipperConfig or a ZMQStatsdShipperConfig.
	 * Leave null if the statsd_client_type is not "udp" or "zmq".
	 */
	public Map<String, Object> statsd_config = null;

	/**
	 * The hostname for the REST server or REST server VIP
	 */
	public String rest_server_hostname = "localhost";

	/**
	 * The port that the REST server or VIP runs on
	 */
	public int rest_server_port = 8080;

	/**
	 * Authentication information: username and password for authenticating against the REST server for fetches.
	 * Authentication is done using DIGEST.
	 */
	public String rest_username = "foo";
	public String rest_password = "bar";

	/**
	 * The maximum number of milliseconds to wait for the REST server to give us an answer.
	 */
	public long rest_fetch_timeout = 1000;

	/**
	 * maximum number of client threads to allow for talking to the REST server at the same time.
	 * This is a tunable to protect the REST server.
	 */
	public int max_rest_client_threads = 40;

	/**
	 * Maximum number of incoming sockets/threads to allow at the same time for answering powerdns requests
	 * default value of null means it will be 50 * number of cpu cores.
	 * This number should be larger than the number of expected connections.
	 */
	public Integer max_powerdns_connection_count = null;

	/**
	 * What port to listen on for connections from PowerDNS
	 */
	public int jedi_listen_port = 5300;

	/**
	 * The path to the UNIX domain socket to bind to for listening to connections from PowerDNS.  This will be used
	 * iff the junixsocket library is installed.  I was going to use libmatthew for this, but the lack of documentation
	 * and Makefile to build it were a problem.
	 * <p/>
	 * If the value is null, a unix socket server is not instantiated, and there will be no UnsatisfiedLinkError
	 * exceptions if it cannot load the junixsocket library.
	 */
	public String unix_socket_path = null;

	/**
	 * Timeout value (in milliseconds) for the unix socket. Without this sockets get stuck in the CLOSE_WAIT status and file descriptors leak.
	 */
	public Integer unix_socket_timeout = 5000;

	/**
	 * maximum number of DNSRecord objects to cache in memory in the LRU.  Tune this to protect memory usage.
	 */
	public Long max_items_in_cache = 10000L;

	/**
	 * maximum number of seconds to hold a cached record in the LRU before it expires and must be refetched from Dynamo
	 */
	public Integer cache_timeout = 300;
}
