package se.rupy.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

/**
 * High speed HTTP async NIO client.<br>
 * <br>
 * This exists because Apache HAC:<br>
 * <br>
 * - Can't handle Murphy's law.<br>
 * - Rarely blocks forever stuck in sun.misc.Unsafe.park().<br>
 * - Is pretty slow and big (adds ~20-30 ms. and 1.5 MB).<br>
 * <br>
 * TODO: Chunks.<br>
 * 
 * @author Marc
 */
public class Async implements Runnable {
	private static final int SIZE = 1024;
	private boolean alive = true, debug;

	private CopyOnWriteArrayList calls;
	private Selector selector;
	private Daemon daemon;
	private Queue queue;
	
	protected Async(Daemon daemon, boolean debug) {
		calls = new CopyOnWriteArrayList();
		this.daemon = daemon;
		this.debug = debug;
	}

	public boolean debug() {
		debug = !debug;
		return debug;
	}

	/**
	 * This is your Async callback.
	 */
	public static abstract class Work {
		protected Event event;
		
		public Work(Event event) {
			this.event = event;
		}
		
		/**
		 * POST or GET small data with {@link Async.Call#post(String, String, byte[])} or {@link Async.Call#get(String, String)}.
		 */
		public abstract void send(Call call) throws Exception;

		/**
		 * Will probably return String headers and byte[] body at some point.
		 */
		public abstract void read(String body) throws Exception;

		/**
		 * If this happens with {@link Async.Timeout}, just resend.
		 * Exceptions you might see here:
		 * ClosedChannel and 
		 * Connect
		 */
		public abstract void fail(String host, Exception e) throws Exception;
	}

	/**
	 * Thrown when {@link SocketChannel#read(ByteBuffer)} returns -1.
	 */
	public static class Timeout extends Exception {}

	/**
	 * More workers are only important on multi-core processors.
	 */
	protected void start(int workers) throws Exception {
		selector = Selector.open();
		queue = new Queue(workers);
		new Thread(this).start();
	}

	/**
	 * Send work to some host.
	 * If you are using fuse, throw the event immediately after to avoid async cascades.
	 * @param invalidate the number of seconds after which the channel is regarded as unreliable.
	 */
	public synchronized void send(String host, Work work, int invalidate) throws Exception {
		if(invalidate > 0) {
			Iterator it = calls.iterator();

			while(it.hasNext()) {
				Call call = (Call) it.next();

				if(call.work == null && call.host.equals(host) && call.invalidate == invalidate && !remove(call)) {
					if(debug)
						call.print(" -->");

					call.work = work;
					call.state(Call.WRITE);
					selector.wakeup();
					return;
				}
			}
		}

		Call call = new Call(this, work, host, invalidate);
		call.state(Call.CONNECT);
		calls.add(call);
		selector.wakeup();

		if(debug)
			call.print("open");
	}

	/**
	 * This has little effect, the class leaks threads and selectors 
	 * on hot-deploy. We will incorporate this class with the rupy 
	 * server as soon as it is feature complete to avoid this issue.
	 */
	protected void stop() throws Exception {
		alive = false;
		selector.wakeup();
		calls.clear();
		queue.stop();
	}

	/**
	 * Holds the channel and buffer.
	 * Work in progress.
	 * TODO: Chunks.
	 */
	public class Call implements Runnable {
		private static final int CONNECT = SelectionKey.OP_CONNECT;
		private static final int WRITE = SelectionKey.OP_WRITE;
		private static final int READ = SelectionKey.OP_READ;

		/*
		 * This is crucial, since NIO has a tendency to not play well with 
		 * Murphy's law, you need to analyze your practical cluster behavior.
		 * 
		 * For me there was a grey zone between the channel.read() returning -1 
		 * and the socket throwing connection timeout; where the selector would 
		 * not select read keys for some reason.
		 */
		private int invalidate;
		private int state, run;
		private Async async;
		private Work work;
		private String host, cookie;
		private SocketChannel channel;
		private long time;
		private boolean running;

		private Call(Async async, Work work, String host, int invalidate) {
			this.invalidate = invalidate;
			this.async = async;
			this.work = work;
			this.host = host;

			time = System.currentTimeMillis();
		}

		private void state(int state) {
			this.state = state;
		}

		private int state() {
			return state;
		}

		private void select() throws Exception {
			if(state == Call.CONNECT)
				connect(selector);
			if(state == Call.WRITE)
				write(selector);
			if(state == Call.READ)
				read(selector);
		}

		private void connect(Selector selector) throws Exception {
			int colon = this.host.indexOf(':');
			String host = colon > 0 ? this.host.substring(0, colon) : this.host;
			int port = colon > 0 ? Short.parseShort(this.host.substring(colon + 1, this.host.length())) : 80;

			if(debug)
				System.out.println("  connect " + host + " " + port);

			state = 0;
			channel = SocketChannel.open();
			channel.configureBlocking(false);
			channel.socket().setTcpNoDelay(true);
			channel.connect(new InetSocketAddress(InetAddress.getByName(host), port));
			channel.register(selector, SelectionKey.OP_CONNECT, this);
			time = System.currentTimeMillis();
		}

		private void write(Selector selector) throws Exception {
			state = 0;
			channel.register(selector, SelectionKey.OP_WRITE, this);
			time = System.currentTimeMillis();
		}

		private void read(Selector selector) throws Exception {
			state = 0;
			channel.register(selector, SelectionKey.OP_READ, this);
			time = System.currentTimeMillis();
		}

		/**
		 * Headers should be \r\n separated, but no trailing \r\n.
		 */
		public void get(String path, String headers) throws Exception {
			write("GET", path, headers, null);
		}

		/**
		 * Headers should be \r\n separated, but no trailing \r\n.
		 */
		public void post(String path, String headers, byte[] body) throws Exception {
			write("POST", path, headers, body);
		}

		private void write(String method, String path, String headers, byte[] body) throws Exception {
			String http = method + " " + path + " HTTP/1.1\r\n" + 
					(body == null ? "" : "Content-Length: " + body.length + "\r\n") + 
					(cookie == null ? "" : "Cookie: " + cookie + "\r\n") + 
					headers + 
					"\r\n\r\n";

			byte[] head = http.getBytes("utf-8");
			int length, write;

			if(body == null) {
				length = head.length;
				write = channel.write(ByteBuffer.wrap(head));
			}
			else {
				byte[] data = new byte[head.length + body.length];

				System.arraycopy(head, 0, data, 0, head.length);
				System.arraycopy(body, 0, data, head.length, body.length);

				length = data.length;
				write = channel.write(ByteBuffer.wrap(data));
			}

			if(debug)
				System.out.println("  write " + write + " " + length);

			if(write != length) {
				throw new Exception("Data too long?");
			}
		}

		/*
		 * Only one chunk supported.
		 */
		private String read() throws Exception {
			ByteBuffer buffer = ByteBuffer.allocate(SIZE);
			byte[] data = new byte[SIZE];
			byte[] body = null;
			
			int read = channel.read(buffer), full = 0;
			String length = null;
			String head = null;

			if(debug)
				System.out.println("  read " + read);

			try {
				while(read > 0) {
					buffer.rewind();
					buffer.get(data, 0, read);

					if(body == null) {
						head = new String(data, "utf-8");
						int boundary = head.indexOf("\r\n\r\n");

						if(boundary == -1)
							throw new Exception("Boundary not found.");

						boundary += 4;

						if(cookie == null) {
							cookie = header(head, "Set-Cookie:");
							if(cookie != null)
								cookie = cookie.substring(0, cookie.indexOf(";"));
						}

						length = header(head, "Content-Length:");
						int size = 0, part = 0;

						if(length == null) {
							String hex = head.substring(boundary, head.indexOf("\r\n", boundary));
							boundary += hex.length() + 2;

							size = Integer.parseInt(hex, 16);
							part = size;
						}
						else {
							size = Integer.parseInt(length);
							part = read - boundary;
						}

						body = new byte[size];

						System.arraycopy(data, boundary, body, 0, part);
						full += part;
					}
					else if(length != null) {
						System.arraycopy(data, 0, body, full, read);
						full += read;
					}

					if(length == null) {
						if(find(data)) {
							String text = new String(body, "utf-8");
							if(head.startsWith("HTTP/1.1 500"))
								throw new Exception(text);
							else
								return text.trim();
						}
					}
					else if(full == body.length) {
						String text = new String(body, "utf-8");
						if(head.startsWith("HTTP/1.1 500"))
							throw new Exception(text);
						else
							return text.trim();
					}

					buffer.clear();
					read = channel.read(buffer);

					while(body != null && read == 0) {
						read(selector);
						selector.wakeup();
						read = channel.read(buffer);
					}

					if(debug)
						System.out.println("  read " + read);
				}
			}
			catch(Exception e) {
				System.out.println(head);
				throw e;
			}

			if(read == -1) {
				throw new Timeout();
			}

			return null;
		}

		private boolean find(byte[] data) {
			for(int i = 0; i < data.length - 5; i++) {
				if(data[i] == '0' && data[i + 1] == '\r' && data[i + 2] == '\n' && data[i + 3] == '\r' && data[i + 4] == '\n')
					return true;
			}

			return false;
		}

		private String header(String head, String name) {
			int index = head.indexOf(name);

			if(index == -1)
				return null;

			return head.substring(index + name.length(), head.indexOf("\r\n", index)).trim();
		}

		public void run() {
			try {
				if(run == CONNECT) {
					channel.finishConnect();
					state(Call.WRITE);
					async.wakeup();
				}

				if(run == WRITE) {
					if(daemon.host) {
						try {
							final Deploy.Archive archive = daemon.archive(work.event.query().header("host"));
							final Call call = this;
							Thread.currentThread().setContextClassLoader(archive);
							AccessController.doPrivileged(new PrivilegedExceptionAction() {
								public Object run() throws Exception {
									work.send(call);
									return null;
								}
							}, archive.access());
						}
						catch(PrivilegedActionException e) {
							e.printStackTrace();
						}
					}
					else {
						work.send(this);
					}
					
					state(Call.READ);
					async.wakeup();
				}

				if(run == READ) {
					if(daemon.host) {
						try {
							final Deploy.Archive archive = daemon.archive(work.event.query().header("host"));
							Thread.currentThread().setContextClassLoader(archive);
							AccessController.doPrivileged(new PrivilegedExceptionAction() {
								public Object run() throws Exception {
									work.read(read());
									return null;
								}
							}, archive.access());
						}
						catch(PrivilegedActionException e) {
							e.printStackTrace();
						}
					}
					else {
						work.read(read());
					}
					
					remove(this);
					work = null;
				}
			}
			catch(Exception e) {
				try {
					failure(e);
				}
				catch(Exception ex) {
					ex.printStackTrace();
				}
			}
		}

		private void failure(Exception e) throws Exception {
			async.remove(this);

			if(work != null) {
				work.fail(host, e);
			}
			else {
				e.printStackTrace();
			}
		}

		private boolean invalidate() {
			return time + invalidate * 1000 < System.currentTimeMillis();
		}

		private void print(String pre) {
			System.out.println(pre + " [" + host + "][" + invalidate + "][" + cookie + "]");
		}
	}

	private class Queue {
		private final Worker[] threads;
		private final LinkedList queue;

		private Queue(int length) {
			queue = new LinkedList();
			threads = new Worker[length];

			for(int i = 0; i < threads.length; i++) {
				threads[i] = new Worker();
				threads[i].start();
			}
		}

		private void execute(Call call) {
			if(!call.running)
				synchronized(queue) {
					call.running = true;
					queue.addLast(call);
					queue.notify();
				}
		}

		private void stop() {
			for(int i = 0; i < threads.length; i++) {
				threads[i].interrupt();
			}
		}

		private class Worker extends Thread {
			public void run() {
				Call call;

				while(alive) {
					synchronized(queue) {
						while (queue.isEmpty()) {
							try {
								queue.wait();
							}
							catch (InterruptedException e) {
								if(!alive)
									return;
							}
						}

						call = (Call) queue.removeFirst();
					}

					try {
						call.run();
						call.running = false;
					}
					catch (RuntimeException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	private void wakeup() {
		selector.wakeup();
	}

	private boolean remove(Call call) throws Exception {
		if(call.invalidate()) {
			try {
				call.channel.close();
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			calls.remove(call);

			if(debug)
				call.print("kill");

			return true;
		}

		return false;
	}

	private void select() throws Exception {
		Iterator it = calls.iterator();

		while(it.hasNext()) {
			Call call = (Call) it.next();
			call.select();
		}
	}

	public void run() {
		while(alive) {
			try {
				select();
				selector.select();
				Set set = selector.selectedKeys();
				Iterator it = set.iterator();

				while(it.hasNext()) {
					SelectionKey key = (SelectionKey) it.next();
					it.remove();

					if(key.isValid()) {
						Call call = (Call) key.attachment();

						if(key.isConnectable() || key.isReadable() || key.isWritable()) {
							call.run = key.interestOps();
							key.interestOps(0);
							queue.execute(call);
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if(selector != null) {
			try {
				selector.close();
			}
			catch(Exception e) {}
		}
	}
}