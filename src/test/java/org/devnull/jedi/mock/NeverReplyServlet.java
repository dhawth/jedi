package org.devnull.jedi.mock;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class NeverReplyServlet extends HttpServlet
{
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		//
		// sleep long enough to trigger a timeout in the MCClient
		//
		try
		{
			Thread.sleep(5000);
			response.setContentType("application/json");
			response.setStatus(HttpServletResponse.SC_OK);
		}
		catch (InterruptedException e)
		{
		}
	}
}
