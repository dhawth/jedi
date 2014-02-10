package org.devnull.jedi;

import org.devnull.jedi.records.Record;
import org.devnull.jedi.records.SOARecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the IP addresses and associated TTL information for a DNS reply
 */
public class DNSRecordSet extends JsonBase
{
	/**
	 * Default TTL is 300 seconds, 5 minutes, and can be overridden by the API server.
	 */

	private static long DEFAULT_TTL = 300;

	private List<Record> records = new ArrayList<Record>();
	private long timestamp = 0L;
	private long ttl = 300;
	private SOARecord soa;

	public DNSRecordSet()
	{
		//
		// set a default soa record
		//
		soa = new SOARecord();
		soa.setAddress("dns1.icann.org. hostmaster.icann.org. 2012080849 7200 3600 1209600 3600");
	}

	public List<Record> getRecords()
	{
		return records;
	}

	public void addRecord(final Record r)
	{
		if (r instanceof SOARecord)
		{
			soa = (SOARecord)r;
		}

		timestamp = Now.getNow();
	}

	public SOARecord getSOA()
	{
		return this.soa;
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
