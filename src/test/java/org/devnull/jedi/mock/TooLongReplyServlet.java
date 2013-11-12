package org.devnull.jedi.mock;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class TooLongReplyServlet extends HttpServlet
{
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		response.setContentType("application/json");
		response.setStatus(HttpServletResponse.SC_OK);

		PrintWriter writer = response.getWriter();

		for (int i = 0; i < 1000; i++)
		{
			writer.print("<h1>Hello SimpleServlet</h1>");
		}
	}
}
