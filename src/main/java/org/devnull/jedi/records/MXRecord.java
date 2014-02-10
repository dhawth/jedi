package org.devnull.jedi.records;

import java.util.StringTokenizer;

/**
 * IPRecord encapsulates the string of the IP address and the type: A or AAAA used
 * in the DNS reply.  The type is set automatically when the address is set.
 */
public class MXRecord extends Record
{
	private String address = null;
	private Integer priority = 0;

	public MXRecord()
	{
		this.type = "MX";
	}

	/**
	 * @return the String value of the IP address or null if it is not set
	 */
	public String getAddress()
	{
		return address;
	}

	/**
	 * Sets the address string
	 *
	 * @param address The String of the ip address
	 */
	public void setAddress(final String address) throws Exception
	{
		if (null == address)
		{
			throw new NullPointerException("address cannot be null");
		}

		String[] fields = address.split("\\s+");

		if (fields.length == 1)
		{
			this.address = address;
		}
		else if (fields.length == 2)
		{
			this.priority = Integer.parseInt(fields[0]);
			this.address = fields[1];
		}
		else
		{
			throw new Exception("Too many fields in address: " + address);
		}
	}

	public void setPriority(final int p)
	{
		priority = p;
	}

	public int getPriority()
	{
		return priority;
	}
}
