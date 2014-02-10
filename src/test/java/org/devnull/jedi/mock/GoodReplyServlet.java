package org.devnull.jedi.mock;

import org.devnull.jedi.DNSRecordSet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class GoodReplyServlet extends HttpServlet
{
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		response.setContentType("application/json");
		response.setStatus(HttpServletResponse.SC_OK);

		PrintWriter writer = response.getWriter();

		String reply = "{\"fqdn\":\"ttl100.google.com\",\"ttl\":100,\"records\":[{\"type\":\"A\",\"address\":\"1.1.1.1\"},{\"type\":\"AAAA\",\"address\":\"2001::fefe\"},{\"type\":\"MX\",\"priority\":10,\"address\":\"mail1.bar.com\"}]}";
		writer.print(reply);
	}
}
