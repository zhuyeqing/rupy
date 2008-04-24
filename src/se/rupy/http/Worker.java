package se.rupy.http;

import java.nio.*;

/**
 * Worker gets the job done. The worker holds the in/out buffers in order to
 * save resources, since the worker is assigned per event until a request is
 * completed.
 * 
 * @author marc
 */
public class Worker implements Runnable, Chain.Link {
	private Daemon daemon;
	private ByteBuffer in, out;
	private Thread thread;
	private Event event;
	private int index;
	private boolean write;

	Worker(Daemon daemon, int index) {
		this.daemon = daemon;
		this.index = index;

		in = ByteBuffer.allocateDirect(daemon.size);
		out = ByteBuffer.allocateDirect(daemon.size);

		thread = new Thread(this);
		thread.start();
	}

	void write() {
		write = true;
	}

	ByteBuffer in() {
		return in;
	}

	ByteBuffer out() {
		return out;
	}

	void wakeup() {
		synchronized (thread) {
			thread.notify();
		}
	}

	void snooze() {
		snooze(0);
	}

	void snooze(long delay) {
		synchronized (thread) {
			try {
				if (delay > 0) {
					thread.wait(delay);
				} else {
					thread.wait();
				}
			} catch (InterruptedException e) {
			}
		}
	}

	void event(Event event) {
		this.event = event;
	}

	boolean busy() {
		return event != null;
	}

	public int index() {
		return index;
	}

	public void run() {
		while (true) {
			try {
				if (event != null) {
					if (write) {
						event.write();
						write = false;
					} else {
						event.read();
					}
				}
			} catch (Exception e) {
				event.disconnect(e);
			} finally {
				if (event != null) {
					event.worker(null);
					event = daemon.next(this);

					if (event != null) {
						event.worker(this);
					} else {
						snooze();
					}
				} else {
					snooze();
				}
			}
		}
	}
}
