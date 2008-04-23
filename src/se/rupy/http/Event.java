package se.rupy.http;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.text.*;
import java.util.*;

import java.nio.channels.*;
import javax.activation.*;

/**
 * Asynchronous HTTP request/response, this virtually represents a 
 * client socket, but in the case where the server is behind a proxy 
 * we cannot depend on that fact since sockets will be reused by 
 * multiple different external clients. It's a performance tradeoff 
 * that we gladly accept though, since hiding behind an Apache or 
 * some other proxy is this servers most probable use.
 * @author marc
 */
public class Event extends Throwable implements Chain.Link {
	static int READ = 1 << 0;
	static int WRITE = 1 << 2;
	static int VERBOSE = 1 << 0;
	static int DEBUG = 1 << 1;
	
	private static char[] BASE_24 = {'B','C','D','F','G','H','J','K','M','P','Q','R','T','V','W','X','Y','2','3','4','6','7','8','9'};
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

		interest(READ, true);
	}
	
	void interest(int interest, boolean set) throws IOException {
		if(set || this.interest != interest) {
			key = channel.register(key.selector(), interest, this);
			key.selector().wakeup();
			
			if(daemon.debug)
				System.out.println("[" + (worker == null ? "*" : "" + worker.index()) + "-" + index + "] " + (interest == READ ? "read" : "write") + " interest set");
		}
		else {
			if(daemon.debug)
				System.out.println("[" + (worker == null ? "*" : "" + worker.index()) + "-" + index + "] " + (interest == READ ? "read" : "write") + " interest prepared");
		}
		
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
	}

	SocketChannel channel() {
		return channel;
	}

	void log(Object o, int level) {
		worker.log(o, level);
	}

	void log(Object o) {
		worker.log(o);
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

		if(daemon.timeout > 0) {
			session(query.header("cookie"), this);
		}

		remote = address();

		if(!content() && !service()) {
			reply.code("404 Not Found");
			reply.output().print("<pre>'" + query.path() + "' was not found.</pre>");
		}

		reply.done();
		query.done();
	}

	String address() {
		String remote = query.header("x-forwarded-for");

		if(remote.equals("0")) {
			InetSocketAddress address = (InetSocketAddress) channel.socket().getRemoteSocketAddress();
			remote = address.getAddress().getHostAddress();
		}

		log("remote " + remote, VERBOSE);

		return remote;
	}

	boolean content() throws IOException {
		Deploy.Stream stream = daemon.content(query.path());

		if(stream == null)
			return false;

		String type = MIME.getContentType(query.path());

		reply.type(type);
		reply.modified(stream.date());

		if(query.modified() == 0 || query.modified() < reply.modified()) {
			Deploy.pipe(stream.input(), reply.output());
			log(type, VERBOSE);
		}
		else {
			reply.code("304 Not Modified");
		}

		return true;
	}

	boolean service() throws IOException {
		Chain chain = daemon.get(query.path());

		if(chain == null)
			return false;

		try {
			chain.filter(this);
		}
		catch(Failure f) {
			throw f;
		}
		catch(Event e) {
			// Break the filter chain.
		}
		catch(Exception e) {
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

	int block(Block block) throws IOException {
		long max = System.currentTimeMillis() + daemon.delay;
		
		interest(interest, true);
		
		while(System.currentTimeMillis() < max) {
			int available = block.fill(true);

			if(available > 0) {
				long delay = daemon.delay - (max - System.currentTimeMillis());
				log("delay " + delay, VERBOSE);
				return available;
			}

			worker.snooze(daemon.delay);
		}

		throw new IOException("IO timeout.");
	}

	interface Block {
		public int fill(boolean debug) throws IOException;
	}

	void disconnect() {
		try {
			if(session != null) {
				daemon.remove(this, session);
			}
			if(channel.isConnected() && channel.isOpen()) {
				channel.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	final void session(String cookie, Event event) {
		String session = cookie(cookie, "session");

		if(this.session != null) {
			this.session = (Session) daemon.session().get(session);
		}

		log((this.session == null ? "new" : "old") + " cookie " + session, VERBOSE);
		
		if(this.session != null) {
			this.session.add(event);
			this.session.touch();
			return;
		}
		
		do {
			session = random(daemon.cookie);
		}
		while(daemon.session().get(session) != null);

		this.session = new Session(session);
		this.session.add(event);

		synchronized(daemon.session()) {
			daemon.session().put(session, this.session);
		}
	}

	public static String cookie(String cookie, String key) {
		String value = null;
		
		if(cookie != null) {
			StringTokenizer tokenizer = new StringTokenizer(cookie, " ");

			while(tokenizer.hasMoreTokens()) {
				String part = tokenizer.nextToken();
				int equals = part.indexOf("=");

				if(equals > -1 && part.substring(0, equals).equals(key)) {
					String subpart = part.substring(equals + 1);

					if(subpart.endsWith(";")) {
						value = subpart.substring(0, subpart.length() - 1);
					}
					else {
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

		while(buffer.length() < length) {
			buffer.append(BASE_24[Math.abs(random.nextInt() % 24)]);
		}

		return buffer.toString();
	}
	
	public String toString() {
		return String.valueOf(index);
	}
}
