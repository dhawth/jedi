Introduction
------------

This is the JEDI server.  It works in conjunction with PowerDNS, a python port-forwarder,
and another Java server I wrote called Darkside, which is a REST API on a database that stores
and serves hostname records.  I thought it would be fun to make a Jedi turn to the Darkside
for answers.

Java
External/Extended/Enhanced/Entrapped/Enveigled
DNS
Interface


This is a backend implementation for use with PowerDNS's remotebackend.
I wrote it because I've done backend implementations for PowerDNS before using
zmq, and I thought it would be an interesting exercise to develop a very simple
authoritative DNS service using the new remotebackend's UNIX socket support.
It accepts connections over TCP, either on localhost or from remote hosts,
and can therefore be used with a cluster of PowerDNS frontends if you like.
It can currently answer with A and AAAA records, but could easily be extended
in the future to handle other types as well.  It includes an in-memory LRU
cache courtesy of Google's Guava libs, and other cache types could be added
fairly easily if you'd like to add memcache/redis or on-disk record caches.

It does not work directly with PowerDNS, as PowerDNS does not have a backend
that supports TCP connections.  PowerDNS has the pipebackend which will open a process
per PowerDNS backend thread, and it has the remotebackend which can speak over
UNIX sockets.  You will therefore need glue in the form of a stdin/stdout-to-TCP
translator or a UNIX-to-TCP port forwarder.  A UNIX-to-TCP port forwarder is provided
in python.

Everything herein is developed based on open-source software, and the end user 
is responsible for complying with any restrictions on the use thereof.  If you
are interested in having a custom backend written for you or a modification 
done, I may be available for contract work.

Building and Installation
-------------------------

The server build requires access to the statsd jar built from [this github repo] [sd].
The jar can be built and installed into your local maven repo (usually ~/.m2) via
"mvn package install".  This is the only dependency that is not available on public
maven repository sites.

The server can be built as an executable jar with a simple "mvn package" command.
The output jar file will be in the target/ directory.

  [sd]: http://www.github.com/dhawth/java_statsd

From scratch on an ubuntu box:

apt-get install ant maven git gcc openjdk-7-jdk openjdk-7-jre

build statsd:

git clone https://github.com/dhawth/java_statsd.git
cd java_statsd
mvn clean package install

Build jedi:

mvn clean package
ls target/jedi-1.0.0-jar-with-dependencies.jar

Build the junixsocket C library if you want Jedi to speak unix sockets natively.  These
libraries are only used and the unix socket support is only enabled if you specify a
unix_socket_path in the jedi.conf.  It defaults to null so you won't get any
UnsatisfiedLinkError exceptions at runtime.

cd 3rdparty/junixsocket-1.3
ant jars

This puts 2 .so files into 3rdparty/junixsocket-1.3/lib-native that will need to be in your
LD_LIBRARY_PATH (or DYLD_LIBRARY_PATH on OS X) when you start up Jedi.  Note that you need
not do anything special with the .jars it creates, the important one is already included
in the monolithic jar that Jedi's build process produces.  You only need to concern yourself
with the .so files.

Build darkside according to the instructions there.  There's a gotcha involving a localhost
mock dynamo instance requirement for the tests to pass, and it requires java 7.

fetch and build powerdns according to the instructions below

Runtime Configuration
---------------------

The server requires a configuration file as well as a log4j properties file.  These
are passed in with the -c and -l flags, respectively.  A sample log4j.conf file is
provided in src/test/resources/log4j.conf.  A sample server configuration file is also
available in src/test/resources/test.conf, although you will obviously want to
modify it to work with your REST server.  The configuration file is in json and the
options, default values, and descriptions are available in the JediConfig.java class.

Authentication against the REST server is entirely optional.  This server uses an
http client that supports digest authentication only, with a configurable username
and password.

This server supports statsd data collection internally via the java_statsd library,
which improves performance by collecting statistics in a singleton StatsObject and then
shipping them once a second with the configured statsd_client_type.  If no statsd_client_type
is specified, stats are simply discarded once a minute.  It is recommended to use
the client type "udp" if you are using stock statsd implementations provided by others.

There are several protective measures provided to limit resource usage by this application.
You can control the number of DNSRecords cached in the LRU, the number of concurrent client
requests to the REST server, and the number of concurrent client connections allowed from
PowerDNS.  The cache_timeout configurable sets the maximum lifetime in seconds of a record
in the LRU cache, after which it will be removed and re-fetched from the REST server.  This
should probably not be any larger than the TTL set on the DNSRecords.

Because powerdns does an SOA lookup before every single query to the backend, you may
want to edit the source code and change the return values of the SOA response to something
you approve of for your organization.  There are examples of the queries that PowerDNS
does and the responses that Jedi gives in the Integration wtih PowerDNS section below.

Runtime Testing / Operational Aspects
-------------------------------------

Obviously a graphite server is a great idea, and more statsd counters and timers could be
added to the codebase to cover any overlooked aspects of the runtime.

Testing responsiveness can be done directly by connecting to the IP and port that the
server is bound to and executing requests, or indirectly by querying the PowerDNS server
in front of it for a full end-to-end test.  In reality, both are recommended to narrow down
the source of failure quicker.  Examples of request and response formats are available in
the test classes, specifically src/test/java/org/devnull/jedi/JediTest.java and
PowerDNSConnectionHandlerTest.java.  All requests/responses are in json and must end in a
newline.

Errors reading input from a client will result in a summary teardown of the client connection
in a typical fail-fast pattern.  No attempt is made to massage the client connection back into
a known state.  It is assumed that the client will simply open a new connection, as this is
what powerdns is written to do.

Tuning will probably be mostly focused on memory utilization and the size of the LRU cache.
The internal PowerDNS caches should probably be avoided, as they can introduce unexpected delays
in seeing changes to the records served after those records are updated in the main database.
There's already a cache lifetime and expiration, as well as a TTL associated with each record,
and those should provide ample tuning for record change response times.

Integration with PowerDNS
-------------------------

This is the annoying part.  PowerDNS doesn't support TCP backends yet, but will soon.  There is
a remotebackend that works over UNIX sockets, however, and a python script has been provided that
port forwards UNIX to TCP.  This python script should be run as a server.

Alternately, small effort could change the PowerDNS remotebackend to use TCP instead of UNIX sockets
for a more native solution.  This will be better for performance as the python script seems to rely
on a call to sleep() and can therefore incur performance penalties at higher traffic levels.

Requests look like this:

initialization requests: { "method" : "initialize" }

lookup requests:
{
    "method" : "lookup",
    "parameters": {
	"qname": "example.com",
	"qtype": "ANY"
    }
}

Responses look like this:

for initialization requests: { "result" : true }

for lookup requests:

on fail: { "result" : false }

on success:

{
   "result" : [
      {
         "priority" : 0,
         "domain_id" : -1,
         "ttl" : 3600,
         "content" : "dns1.icann.org. hostmaster.icann.org. 2012080849 7200 3600 1209600 3600",
         "qname" : "example.com",
         "qtype" : "SOA"
      }
   ]
}

For every lookup that powerdns does, it actually does 2:  first it requests an SOA, then the
actual records asked for.  That should be fixed for performance reasons within remotebackend.cc
where you can hard-code the return values from getSOA and avoid talking to the remote process
at all.  Here's output from the python port forwarding script (which prints everything to stdout 
and probably shouldn't):

This was from a dig @powerdns foo.bar.baz. A

{"method":"lookup","parameters":{"qtype":"SOA","qname":"foo.bar.baz","remote":"127.0.0.1","local":"0.0.0.0","real-remote":"127.0.0.1/32","zone-id":-1}}
{"result":[{"qtype":"SOA","qname":"foo.bar.baz","content":"dns1.icann.org. hostmaster.icann.org. 2001010000 7200 3600 1209600 3600","ttl":3600,"priority":0,"domain_id":-1}]}

{"method":"lookup","parameters":{"qtype":"ANY","qname":"foo.bar.baz","remote":"127.0.0.1","local":"0.0.0.0","real-remote":"127.0.0.1/32","zone-id":-1}}
{"result":[{"qname":"foo.bar.baz","qtype":"A","content":"1.1.1.1","ttl":10,"priority":0,"auth":1},{"qname":"foo.bar.baz","qtype":"A","content":"2.2.2.2","ttl":10,"priority":0,"auth":1},{"qname":"foo.bar.baz","qtype":"AAAA","content":"2001::fefe","ttl":10,"priority":0,"auth":1}]}

PowerDNS gave me back this:

oot@ubuntu:~# dig @localhost foo.bar.baz. A

; <<>> DiG 9.8.1-P1 <<>> @localhost foo.bar.baz. A
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 65340
;; flags: qr aa rd; QUERY: 1, ANSWER: 2, AUTHORITY: 0, ADDITIONAL: 0
;; WARNING: recursion requested but not available

;; QUESTION SECTION:
;foo.bar.baz.			IN	A

;; ANSWER SECTION:
foo.bar.baz.		10	IN	A	2.2.2.2
foo.bar.baz.		10	IN	A	1.1.1.1

;; Query time: 14 msec
;; SERVER: 127.0.0.1#53(127.0.0.1)
;; WHEN: Sat Nov  9 14:48:55 2013
;; MSG SIZE  rcvd: 61


Building PowerDNS
-----------------

This requires powerdns 3.3 with remotebackend support.  PowerDNS does not build for me
on OS X 10.8.5, so don't be surprised by that.

./configure --with-modules="pipe remote" --without-lua
make
make install

An example config is available in examples/pdns.conf

Running powerdns with:

pdns_server --config-dir=examples/

and it will look for a pdns.conf in that directory (not exactly what you expect).
