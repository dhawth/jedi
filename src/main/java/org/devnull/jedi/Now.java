package org.devnull.jedi;

import java.util.concurrent.atomic.AtomicLong;

/**
 * This class simply updates a long that holds the current system time in milliseconds, for
 * use in determining if records are stale, etc.  This value is used a lot, so caching it
 * seems like a good idea to avoid excessive calls to System.currentTimeMillis()
 */
public class Now extends JsonBase
{
	private static AtomicLong now = new AtomicLong(System.currentTimeMillis());
	private static Thread nowUpdater = new Thread()
	{
		public void run()
		{
			while (true)
			{
				try
				{
					Thread.sleep(1000);
				}
				catch (InterruptedException ie)
				{
				}

				now.set(System.currentTimeMillis());
			}
		}
	};

	static
	{
		nowUpdater.start();
	}

	/**
	 * Retrieve the most current timestamp cached.
	 *
	 * @return long representing the System.currentTimeMillis() that was recently cached.  This value is updated
	 * once per second.
	 */
	public static long getNow()
	{
		return now.get();
	}
}
