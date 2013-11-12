package org.devnull.jedi;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

/**
 * Documentation of Request API
 * <p/>
 * The following methods are used:
 * 74
 * 75	Method: lookup
 * 76	Parameters: qtype, qname (domain), remote, local, real-remote, zone_id
 * *
 * ** We do not care about remote IP, local IP, real-remote IP, or zone_id
 * *
 * 77	Reply: array of <qtype,qname,content,ttl,domaind_id,priority,scopeMask>
 * 78	Optional values: domain_id and scopeMask
 * 79
 * <p/>
 * Scenario: SOA lookup via pipe or unix
 * <p/>
 * Query:
 * <p/>
 * {
 * "method": "lookup",
 * "parameters": {
 * "qname": "example.com",
 * "qtype": "SOA",
 * "zone_id": "-1"
 * }
 * }
 * <p/>
 * ** NB, qtype in request is usually "ANY", and qtype of reply needs to be "A" or "AAAA", etc.
 * They gave us a bum example with SOA request/response samples.
 */

/**
 * PDNSRequest encapsulates a request object from PowerDNS.  This can be an initialize or lookup request with
 * associated hostname, query type, etc.  The only fields we care about are method, parameters:qname, and parameters:qtype.
 */
public class PDNSRequest extends JsonBase
{
	private String method = null;
	private Map<String, String> parameters = new HashMap<String, String>();

	public PDNSRequest()
	{
	}

	/**
	 * getParameters returns the internal parameters map, and is required by Jackson for inserting qname and qtype.
	 * @return	HashMap of string to string representing the parameters list.
	 */
	public Map<String, String> getParameters()
	{
		return parameters;
	}

	public void setParameters(Map<String, String> paramMap)
	{
		this.parameters = paramMap;
	}

	/**
	 * accessor for the method string
	 * @return String, e.g. initialize|lookup
	 */
	public String getMethod()
	{
		return method;
	}

	public void setMethod(final String method)
	{
		this.method = method;
	}

	/**
	 * Return the value of the "qname" parameter if it is set.
	 * @return String or null if the parameter is not set, ie in an initialization request.
	 */
	@JsonIgnore
	public String getDomain()
	{
		if (parameters == null)
		{
			return null;
		}

		return parameters.get("qname");
	}

	/**
	 * Returns the value of the "qtype" parameter if it is set.
	 * @return String (SOA|ANY|A|AAAA, etc) or null if the parameter is not set, ie in an initialization request.
	 */
	@JsonIgnore
	public String getQType()
	{
		if (parameters == null)
		{
			return null;
		}

		return parameters.get("qtype");
	}
}
