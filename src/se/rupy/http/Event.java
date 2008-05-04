package se.rupy.http;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.text.*;
import java.util.*;

import java.nio.channels.*;
import javax.activation.*;

/**
 * Asynchronous HTTP request/response, this virtually represents a client
 * socket, but in the case where the server is behind a proxy we cannot depend
 * on that fact since sockets will be reused by multiple different external
 * clients. It's a performance tradeoff that we gladly accept though, since
 * hiding behind an Apache or some other proxy is this servers most probable
 * use.
 * 
 * @author marc
 */
public class Event extends Throwable implements Chain.Link {
	static int READ = 1 << 0;
	static int WRITE = 1 << 2;
	static int VERBOSE = 1 << 0;
	static int DEBUG = 1 << 1;

	private static char[] BASE_24 = { 'B', 'C', 'D', 'F', 'G', 'H', 'J', 'K',
			'M', 'P', 'Q', 'R', 'T', 'V', 'W', 'X', 'Y', '2', '3', '4', '6',
			'7', '8', '9' };
	private static MimetypesFileTypeMap MIME;
	static DateFormat DATE;

	static {
		MIME = new MimetypesFileTypeMap();
		DATE = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
		DATE.setTimeZone(TimeZone.getTimeZone("GMT"));
		READ = SelectionKey.OP_READ;
		WRITE = SelectionKey.OP_WRITE;
	}

	private SocketChannel channel;
	private SelectionKey key;

	private Query query;
	private Reply reply;
	private Session session;

	private Daemon daemon;
	private Worker worker;

	private int index, interest;
	private String remote;
	private boolean close;

	Event(Daemon daemon, SelectionKey key, int index) throws IOException {
		channel = ((ServerSocketChannel) key.channel()).accept();
		channel.configureBlocking(false);

		this.daemon = daemon;
		this.index = index;
		this.key = key;

		query = new Query(this);
		reply = new Reply(this);

		key = channel.register(key.selector(), READ, this);
		key.selector().wakeup();
	}

	int interest() {
		return interest;
	}

	void interest(int interest) {
		this.interest = interest;
	}

	public Daemon daemon() {
		return daemon;
	}

	public Query query() {
		return query;
	}

	public Reply reply() {
		return reply;
	}

	public Session session() {
		return session;
	}

	void session(Session session) {
		this.session = session;
	}

	public String remote() {
		return remote;
	}

	public boolean close() {
		return close;
	}

	public Worker worker() {
		return worker;
	}

	public int index() {
		return index;
	}

	void close(boolean close) {
		this.close = close;
	}

	void worker(Worker worker) {
		this.worker = worker;
		register(READ);
	}

	SocketChannel channel() {
		return channel;
	}

	void log(Object o) {
		log(o, Event.DEBUG);
	}

	void log(Object o, int level) {
		if (o instanceof Exception && daemon.debug) {
			((Exception) o).printStackTrace();
		} else if (daemon.debug || daemon.verbose && level == Event.VERBOSE)
			System.out.println("["
					+ (worker == null ? "*" : "" + worker.index()) + "-"
					+ index + "] " + o);
	}

	/**
	 * @return same as {@link Query#integer(String)}.
	 */
	public int integer(String key) {
		return query.integer(key);
	}

	/**
	 * @return same as {@link Query#bool(String)}.
	 */
	public boolean bool(String key) {
		return query.bool(key);
	}

	/**
	 * @return same as {@link Query#parameter(String)}.
	 */
	public String parameter(String key) {
		return query.parameter(key);
	}

	/**
	 * @return same as {@link Query#input()}.
	 */
	public Input input() {
		return query.input();
	}

	/**
	 * @return same as {@link Reply#output()}.
	 * @throws IOException
	 */
	public Output output() throws IOException {
		return reply.output();
	}

	void read() throws IOException {
		query.headers();
		remote = address();

		if (!content() && !service()) {
			reply.code("404 Not Found");
			reply.output().print(
					"<pre>'" + query.path() + "' was not found.</pre>");
		}

		reply.done();
		query.done();
	}

	String address() {
		String remote = query.header("x-forwarded-for");

		if (remote.equals("0")) {
			InetSocketAddress address = (InetSocketAddress) channel.socket()
					.getRemoteSocketAddress();
			remote = address.getAddress().getHostAddress();
		}

		log("remote " + remote, VERBOSE);

		return remote;
	}

	boolean content() throws IOException {
		Deploy.Stream stream = daemon.content(query.path());

		if (stream == null)
			return false;

		String type = MIME.getContentType(query.path());

		reply.type(type);
		reply.modified(stream.date());

		if (query.modified() == 0 || query.modified() < reply.modified()) {
			Deploy.pipe(stream.input(), reply.output());
			log(type, VERBOSE);
		} else {
			reply.code("304 Not Modified");
		}

		return true;
	}

	boolean service() throws IOException {
		Chain chain = daemon.get(query.path());

		if (chain == null)
			return false;

		try {
			chain.filter(daemon, this);
		} catch (Failure f) {
			throw f;
		} catch (Event e) {
			// Break the filter chain.
		} catch (Exception e) {
			log(e);

			StringWriter trace = new StringWriter();
			PrintWriter print = new PrintWriter(trace);
			e.printStackTrace(print);

			reply.code("500 Internal Server Error");
			reply.output().print("<pre>" + trace.toString() + "</pre>");
		}

		return true;
	}

	void write() throws IOException {
		service();
		reply.done();
		query.done();
	}

	void register() throws IOException {
		if (interest != key.interestOps()) {
			key = channel.register(key.selector(), interest, this);
		}

		key.selector().wakeup();

		log((interest == READ ? "read" : "write") + " interest ", DEBUG);
	}

	void register(int interest) {
		interest(interest);

		try {
			if (channel != null)
				register();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	int block(Block block) throws Exception {
		long max = System.currentTimeMillis() + daemon.delay;

		register();

		while (System.currentTimeMillis() < max) {
			int available = block.fill(true);

			if (available > 0) {
				long delay = daemon.delay - (max - System.currentTimeMillis());
				log("delay " + delay, VERBOSE);
				return available;
			}

			worker.snooze(daemon.delay);
		}

		throw new Exception("IO timeout.");
	}

	interface Block {
		public int fill(boolean debug) throws IOException;
	}

	void disconnect(Exception e) {
		try {
			if (channel != null) {
				channel.close();
				channel = null;
			}

			if (session != null) {
				session.remove(this);
			}

			log(e);
		} catch (Exception de) {
			de.printStackTrace();
		}
	}

	final void session(Service service) {
		String key = cookie(query.header("cookie"), "key");

		if (key != null) {
			session = (Session) daemon.session().get(key);
		}

		if (session != null) {
			log("old key " + key, VERBOSE);

			session.add(this);
			session.touch();

			return;
		}

		session = new Session(daemon);

		session.add(service);
		session.add(this);

		session.key(key);

		try {
			service.session(session, Service.CREATE);
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (session.key() == null) {
			do {
				key = random(daemon.cookie);
			} while (daemon.session().get(key) != null);
			session.key(key);
		}

		synchronized (daemon.session()) {
			log("new key " + session.key(), VERBOSE);
			daemon.session().put(session.key(), session);
		}
	}

	public static String cookie(String cookie, String key) {
		String value = null;

		if (cookie != null) {
			StringTokenizer tokenizer = new StringTokenizer(cookie, " ");

			while (tokenizer.hasMoreTokens()) {
				String part = tokenizer.nextToken();
				int equals = part.indexOf("=");

				if (equals > -1 && part.substring(0, equals).equals(key)) {
					String subpart = part.substring(equals + 1);

					if (subpart.endsWith(";")) {
						value = subpart.substring(0, subpart.length() - 1);
					} else {
						value = subpart;
					}
				}
			}
		}

		return value;
	}

	public static String random(int length) {
		Random random = new Random();
		StringBuffer buffer = new StringBuffer();

		while (buffer.length() < length) {
			buffer.append(BASE_24[Math.abs(random.nextInt() % 24)]);
		}

		return buffer.toString();
	}

	public String toString() {
		return String.valueOf(index);
	}
}
