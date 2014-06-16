package org.devnull.jedi.records;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.devnull.jedi.JsonBase;

@JsonTypeInfo(
	use = JsonTypeInfo.Id.NAME,
	include = JsonTypeInfo.As.PROPERTY,
	property = "type"
)
@JsonSubTypes(
	{
		@JsonSubTypes.Type(value = SOARecord.class, name = "SOA"),
		@JsonSubTypes.Type(value = NSRecord.class, name = "NS"),
		@JsonSubTypes.Type(value = MXRecord.class, name = "MX"),
		@JsonSubTypes.Type(value = ARecord.class, name = "A"),
		@JsonSubTypes.Type(value = TXTRecord.class, name = "TXT"),
		@JsonSubTypes.Type(value = CNAMERecord.class, name = "CNAME"),
		@JsonSubTypes.Type(value = AAAARecord.class, name = "AAAA")
	}
)
public abstract class Record extends JsonBase
{
	@JsonIgnore
	protected String type;

	@JsonIgnore
	public String getType()
	{
		return type;
	}

	public abstract String getAddress();

	public abstract void setAddress(final String address) throws Exception;
}
