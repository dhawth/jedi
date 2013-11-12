package org.devnull.jedi;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the IP addresses and associated TTL information for a DNS reply
 */
public class DNSRecord extends JsonBase
{
	/**
	 * Default TTL is 300 seconds, 5 minutes, and can be overridden by the API server.
	 */

	private static long DEFAULT_TTL = 300;

	private List<IPRecord> records = new ArrayList<IPRecord>();
	private long timestamp = 0L;
	private long ttl = 300;

	public List<IPRecord> getRecords()
	{
		return records;
	}

	public void addRecord(final IPRecord r)
	{
		records.add(r);
		timestamp = Now.getNow();
	}

	public long getTimestamp()
	{
		return timestamp;
	}

	public void setTimestamp(final long timestamp)
	{
		this.timestamp = timestamp;
	}

	public long getTTL()
	{
		return ttl;
	}

	public void setTTL(final long ttl)
	{
		this.ttl = ttl;
	}

	public void reset()
	{
		records.clear();
		ttl = DEFAULT_TTL;
	}
}
