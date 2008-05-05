package se.rupy.http;

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;

import java.nio.channels.*;

/**
 * A tiny HTTP daemon. The whole server is non-static so that you can launch
 * multiple contained HTTP servers in one application on different ports.
 * 
 * @author marc
 */

public class Daemon implements Runnable {
	int thread, port, cookie, size, index;
	public boolean verbose, debug, test;
	long timeout, delay;

	private HashMap service, content, session;
	private Chain workers, queue;
	private Selector selector;
	private String pass;

	/**
	 * Use this to start the daemon from your application.
	 * 
	 * @param pass
	 *            the pass used to deploy services via HTTP POST or null/"" to
	 *            disable remote hot-deploy
	 * @param port
	 *            which TCP port
	 * @param threads
	 *            how many worker threads, the daemon also starts one selector
	 *            thread.
	 * @param timeout
	 *            session timeout in seconds or 0 to disable sessions
	 * @param cookie
	 *            session key length; default and minimum is 4, > 10 can be
	 *            considered secure
	 * @param delay
	 *            time in seconds before started event gets dropped due to
	 *            inactivity.
	 * @param size
	 *            IO buffer size, should be proportional to the data sizes
	 *            received/sent by the server currently this is input/output
	 *            buffer sizes, chunk length and max header size! :P
	 * @param verbose
	 */
	public Daemon(String pass, int port, int threads, int timeout, int cookie,
			int delay, int size, boolean verbose, boolean debug) {
		this(pass, port, threads, timeout, cookie, delay, size, verbose, debug,
				false);
	}

	Daemon(String pass, int port, int thread, int timeout, int cookie,
			int delay, int size, boolean verbose, boolean debug, boolean test) {
		this.pass = pass;
		this.port = port;
		this.thread = thread;
		this.timeout = timeout * 1000;
		this.cookie = cookie < 4 ? 4 : cookie;
		this.delay = delay * 1000;
		this.size = size;
		this.verbose = verbose;
		this.debug = debug;
		this.test = test;

		if (!verbose) {
			debug = false;
		}

		service = new HashMap();
		content = new HashMap();
		session = new HashMap();

		workers = new Chain();
		queue = new Chain();

		try {
			new Heart();

			for (int i = 0; i < thread; i++) {
				workers.add(new Worker(this, i));
			}

			new Thread(this).start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	HashMap session() {
		return session;
	}

	Selector selector() {
		return selector;
	}

	public void add(Service service) {
		StringTokenizer paths = new StringTokenizer(service.path(), ":");

		while (paths.hasMoreTokens()) {
			String path = paths.nextToken();
			Chain chain = null;

			synchronized (this.service) {
				chain = (Chain) this.service.get(path);
			}

			if (chain == null) {
				chain = new Chain();
				this.service.put(path, chain);
			}

			Service old = (Service) chain.put(service);

			try {
				service.init();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (verbose)
			System.out.println("init " + service.path());
	}

	public void remove(Service service) {
		Service old = null;
		StringTokenizer paths = new StringTokenizer(service.path(), ":");

		while (paths.hasMoreTokens()) {
			String path = paths.nextToken();
			Chain chain = null;

			synchronized (this.service) {
				chain = (Chain) this.service.get(path);
			}

			if (chain != null) {
				old = (Service) chain.del(service);
			}
		}

		try {
			if (old != null) {
				old.done();

				if (verbose)
					System.out.println("done " + service.path());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void add(HashMap content) {
		Iterator it = content.keySet().iterator();

		while (it.hasNext()) {
			String name = (String) it.next();
			Deploy.Stream stream = (Deploy.Stream) content.get(name);

			if (verbose)
				System.out.println(name + " " + stream.length());

			synchronized (this.content) {
				this.content.put(name, stream);
			}
		}
	}

	Deploy.Stream content(String path) {
		synchronized (this.content) {
			return (Deploy.Stream) this.content.get(path);
		}
	}

	boolean remove(String path) {
		synchronized (this.service) {
			return this.service.remove(path) != null;
		}
	}

	Chain get(String path) {
		synchronized (this.service) {
			return (Chain) this.service.get(path);
		}
	}

	public Service get(String path, int index) {
		synchronized (this.service) {
			return (Service) get(path).get(index);
		}
	}

	synchronized Event next(Worker worker) {
		synchronized (this.queue) {
			if (queue.size() > 0) {
				if (debug)
					System.out.println("worker " + worker.index()
							+ " found work " + queue);

				return (Event) queue.remove(0);
			}
		}
		return null;
	}

	public void run() {
		try {
			selector = Selector.open();
			ServerSocketChannel server = ServerSocketChannel.open();
			server.socket().bind(new InetSocketAddress(port));
			server.configureBlocking(false);
			server.register(selector, SelectionKey.OP_ACCEPT);

			DecimalFormat decimal = (DecimalFormat) DecimalFormat.getInstance();
			decimal.applyPattern("#.##");

			if (verbose)
				System.out.println("daemon started\n" + "- pass       \t"
						+ pass + "\n" + "- port       \t" + port + "\n"
						+ "- worker     \t" + thread + " thread"
						+ (thread > 1 ? "s" : "") + "\n" + "- timeout    \t"
						+ decimal.format((double) timeout / 60000) + " minute"
						+ (timeout / 60000 > 1 ? "s" : "") + "\n"
						+ "- session    \t" + cookie + " characters\n"
						+ "- IO timeout \t" + delay / 1000 + " second"
						+ (delay / 1000 > 1 ? "s" : "") + "\n"
						+ "- IO buffer  \t" + size + " bytes\n"
						+ "- debug      \t" + debug);

			if (pass != null && pass.length() > 0)
				add(new Deploy("app/", pass));

			File[] app = new File("app/").listFiles(new Filter());

			if (app != null) {
				for (int i = 0; i < app.length; i++) {
					Deploy.deploy(this, app[i]);
				}
			}

			if (test)
				test();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		Event event = null;
		SelectionKey key = null;

		while (true) {
			try {
				selector.select();
				Iterator it = selector.selectedKeys().iterator();

				while (it.hasNext()) {
					key = (SelectionKey) it.next();
					it.remove();

					if (key.isValid()) {
						if (key.isAcceptable()) {
							// TODO: Event pool?
							event = new Event(this, key, index++);
							event.log("accept ---");
						} else if (key.isReadable() || key.isWritable()) {
							key.interestOps(0);
							
							event = (Event) key.attachment();
							Worker worker = event.worker();

							if (debug) {
								if (key.isReadable())
									event.log("read ---");
								if (key.isWritable())
									event.log("write ---");
							}

							if (worker == null) {
								worker = employ(event, false);
							} else {
								worker.wakeup();
							}
						}
					}
				}
			} catch (Exception e) {
				/*
				 * Here we get mostly ClosedChannelExceptions and
				 * java.io.IOException: 'Too many open files' when the server is
				 * taking a beating. Better to drop connections than to drop the
				 * server.
				 */
				event.disconnect(e);
			}
		}
	}

	synchronized Worker employ(Event event, boolean write) {
		workers.reset();
		Worker worker = (Worker) workers.next();

		if (worker == null) {
			synchronized (this.queue) {
				queue.add(event);
			}

			if (debug)
				System.out.println("no worker found " + queue);

			return null;
		}

		while (worker.busy()) {
			worker = (Worker) workers.next();

			if (worker == null) {
				synchronized (this.queue) {
					queue.add(event);
				}

				if (debug)
					System.out.println("no worker found " + queue);

				return null;
			}
		}

		if (debug)
			System.out.println("worker " + worker.index() + " hired " + queue);

		if (write) {
			worker.write();
		}

		event.worker(worker);
		worker.event(event);
		worker.wakeup();

		return worker;
	}

	class Filter implements FilenameFilter {
		public boolean accept(File dir, String name) {
			if (name.endsWith(".jar")) {
				return true;
			}

			return false;
		}
	}

	class Heart implements Runnable {
		Heart() {
			new Thread(this).start();
		}

		public void run() {
			while (true) {
				try {
					Thread.sleep(1000);

					synchronized (session) {
						Iterator it = session.values().iterator();

						while (it.hasNext()) {
							Session s = (Session) it.next();

							if (System.currentTimeMillis() - s.date() > timeout) {
								s.remove();

								if (debug)
									System.out.println("session timeout "
											+ s.key());

								it.remove();
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void main(String[] args) {
		Properties flags = new Properties();

		for (int i = 0; i < args.length; i++) {
			String flag = args[i];
			String value = null;

			if (flag.startsWith("-") && ++i < args.length) {
				value = args[i];

				if (value.startsWith("-")) {
					i--;
					value = null;
				}
			}

			if (value == null) {
				flags.put(flag.substring(1).toLowerCase(), "true");
			} else {
				flags.put(flag.substring(1).toLowerCase(), value);
			}
		}

		if (flags.getProperty("help", "false").toLowerCase().equals("true")) {
			System.out.println("Usage: java -jar http.jar -verbose");
			return;
		}

		String pass = flags.getProperty("pass", "");

		int port = Integer.parseInt(flags.getProperty("port", "8000"));
		int threads = Integer.parseInt(flags.getProperty("threads", "5"));
		int timeout = Integer.parseInt(flags.getProperty("timeout", "300"));
		int cookie = Integer.parseInt(flags.getProperty("cookie", "4"));
		int delay = Integer.parseInt(flags.getProperty("delay", "5"));
		int size = Integer.parseInt(flags.getProperty("size", "1024"));

		boolean verbose = flags.getProperty("verbose", "false").toLowerCase()
				.equals("true");
		boolean debug = flags.getProperty("debug", "false").toLowerCase()
				.equals("true");
		boolean test = flags.getProperty("test", "false").toLowerCase().equals(
				"true");

		new Daemon(pass, port, threads, timeout, cookie, delay, size, verbose,
				debug, test);
	}

	/*
	 * Test cases are performed in parallel with one worker thread, in order to
	 * detect synchronous errors.
	 */
	void test() throws IOException {
		System.out.println("Parallel testing begins in one second:");
		System.out.println("- OP_READ, OP_WRITE and selector wakeup.");
		System.out.println("- Asynchronous non-blocking reply.");
		System.out.println("- Session creation and timeout.");
		System.out.println("- Exception handling.");
		System.out.println("Estimated duration: ~2 sec.");
		System.out.println("             ---o---");

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
		}

		add(new Test.Service("/io"));
		add(new Test.Service("/async"));
		add(new Test.Service("/error"));

		new Thread(new Test("localhost:" + port + "/io",
				new File(Test.original))).start();
		new Thread(new Test("localhost:" + port + "/async", null)).start();
		new Thread(new Test("localhost:" + port + "/error", null)).start();
	}
}
