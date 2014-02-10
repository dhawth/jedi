package org.devnull.jedi.records;

import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * IPRecord encapsulates the string of the IP address and the type: A or AAAA used
 * in the DNS reply.  The type is set automatically when the address is set.
 */
public class ARecord extends Record
{
	private String address = null;

	public ARecord()
	{
		this.type = "A";
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
		if (!InetAddressValidator.getInstance().isValidInet4Address(address))
		{
			throw new Exception("address is not valid: " + address);
		}

		this.address = address;
	}
}
