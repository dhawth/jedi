package org.devnull.jedi;

import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertTrue;

public class NowTest
{
	@Test
	public void testGetNow() throws Exception
	{
		long now = 0;

		now = Now.getNow();

		assertTrue(now != 0);

		Thread.sleep(2000);

		long newNow = Now.getNow();

		assertTrue(now != newNow);
		assertTrue(newNow > now);
	}
}
