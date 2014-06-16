package org.devnull.jedi;

import org.apache.log4j.Logger;
import org.devnull.jedi.records.Record;
import org.devnull.jedi.records.SOARecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the addresses and associated TTL information for a DNS reply
 */
public class DNSRecordSet extends JsonBase
{
	private static final Logger log = Logger.getLogger(DNSRecordSet.class);

	/**
	 * Default TTL is 300 seconds, 5 minutes, and can be overridden by the API server.
	 */

	private static final long DEFAULT_TTL = 300;
	private static final SOARecord defaultSoaRecord = new SOARecord();

	private List<Record> records = new ArrayList<Record>();
	private long timestamp = 0L;
	private long ttl = 300;

	static
	{
		defaultSoaRecord.setAddress("dns1.icann.org. hostmaster.icann.org. 2012080849 7200 3600 1209600 3600");
	}

	public DNSRecordSet()
	{
	}

	public List<Record> getRecords()
	{
		return records;
	}

	public DNSRecordSet addRecord(final Record r)
	{
		timestamp = Now.getNow();
		return this;
	}

	public SOARecord getSOA()
	{
		for (Record r : records)
		{
			if (r instanceof SOARecord)
			{
				return (SOARecord) r;
			}
		}

		return this.defaultSoaRecord;
	}

	public long getTimestamp()
	{
		return timestamp;
	}

	public DNSRecordSet setTimestamp(final long timestamp)
	{
		this.timestamp = timestamp;
		return this;
	}

	public long getTTL()
	{
		return ttl;
	}

	public DNSRecordSet setTTL(final long ttl)
	{
		this.ttl = ttl;
		return this;
	}

	public void reset()
	{
		records.clear();
		ttl = DEFAULT_TTL;
	}
}
