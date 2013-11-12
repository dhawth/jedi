package org.devnull.jedi.mock;

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

		String reply = "{\"ttl\":100,\"records\":[{\"address\":\"1.1.1.1\"},{\"address\":\"2001:fefe\"}]}";
		writer.print(reply);
	}
}
