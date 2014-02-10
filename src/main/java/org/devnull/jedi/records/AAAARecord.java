package org.devnull.jedi.records;

/**
 * IPRecord encapsulates the string of the IP address and the type: A or AAAA used
 * in the DNS reply.  The type is set automatically when the address is set.
 */
public class AAAARecord extends Record
{
	private String address = null;

	public AAAARecord()
	{
		this.type = "AAAA";
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
	public void setAddress(final String address)
		throws Exception
	{
		if (address == null)
		{
			throw new NullPointerException("address cannot be null");
		}

		if (!address.contains(":"))
		{
			throw new IllegalArgumentException("IPv6 address is malformed");
		}

		this.address = address;
	}
}
