package se.rupy.http;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.CancelledKeyException;

/**
 * Worker gets the job done. The worker holds the in/out/chunk buffers in order to
 * save resources, since the worker is assigned per event until a request is
 * completed.
 * 
 * @author marc
 */
public class Worker implements Runnable, Chain.Link {
	private Daemon daemon;
	private ByteBuffer in, out;
	private byte[] chunk;
	private Thread thread;
	private Event event;
	private int index, lock;
	private boolean awake, alive;
	private long touch;

	protected Worker(Daemon daemon, int index) {
		this.daemon = daemon;
		this.index = index;

		in = ByteBuffer.allocateDirect(daemon.size);
		out = ByteBuffer.allocateDirect(daemon.size);

		alive = true;

		thread = new Thread(this);
		thread.start();
	}
	
	protected ByteBuffer in() {
		return in;
	}

	protected ByteBuffer out() {
		return out;
	}

	protected byte[] chunk() {
		if(chunk == null) {
			chunk = new byte[daemon.size + Output.Chunked.OFFSET + 2];
		}

		return chunk;
	}

	protected void wakeup() {
		if(event != null)
			event.log("wakeup", Event.DEBUG);

		synchronized (thread) {
			thread.notify();
		}

		awake = true;
	}

	protected void snooze() {
		snooze(0);
	}

	protected void snooze(long delay) {
		if(event != null)
			event.log("snooze " + delay, Event.DEBUG);

		synchronized (thread) {
			try {
				if (delay > 0) {
					if(awake) {
						awake = false;
						return;
					}

					thread.wait(delay);
				} else {
					thread.wait();
				}
			} catch (InterruptedException e) {
				event.disconnect(e);
			}

			awake = false;
		}
	}

	protected Event event() {
		return event;
	}
	
	protected void event(Event event) {
		this.event = event;
	}
	
	protected void touch() {
		touch = System.currentTimeMillis();
	}
	
	protected int lock() {
		return lock;
	}
	
	boolean busy() {
		if(event != null) {
			lock = (int) (System.currentTimeMillis() - touch);
			
			if(lock > daemon.lock) {
				reset(new Exception("Threadlock (" + index + ")"));
				return false;
			}
			
			return event.valid();
		}
		
		return false;
	}

	public int index() {
		return index;
	}

	public void stop() {
		synchronized (thread) {
			thread.notify();
		}

		alive = false;
	}

	public String toString() {
		return String.valueOf(index);
	}

	public void run() {
		touch = System.currentTimeMillis();
		
		while (alive) {
			try {
				if (event != null) {
					if (event.push()) {
						event.write();
						event.push(false);
					} else {
						event.read();
					}
				}
			} catch (Exception e) {
				reset(e);
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

	protected void reset(Exception e) {
		event.disconnect(e);
		
		//snooze(10); // to avoid deadlock when proxy closes socket
		
		out.clear();
		in.clear();
	}
}
