package org.devnull.jedi;

/**
 * IPRecord encapsulates the string of the IP address and the type: A or AAAA used
 * in the DNS reply.  The type is set automatically when the address is set.
 */
public class IPRecord extends JsonBase
{
	private String address = null;
	private String type = "A";

	public IPRecord()
	{
	}

	/**
	 * @return the String value of the IP address or null if it is not set
	 */
	public String getAddress()
	{
		return address;
	}

	/**
	 * Sets the address string and determines if it is IPv4 or IPv6
	 *
	 * @param address	The String of the ip address
	 */
	public void setAddress(final String address)
	{
		this.address = address;

		if (address.contains(":"))
		{
			type = "AAAA";
		}
	}

	/**
	 * @return The String of the type: A or AAAA
	 */
	public String getType()
	{
		return type;
	}

	/**
	 * sets the address to null and resets the address type to IPv4 ("A")
	 */
	public void reset()
	{
		address = null;
		type = "A";
	}
}
