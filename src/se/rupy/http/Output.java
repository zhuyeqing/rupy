package se.rupy.http;

import java.io.*;
import java.nio.*;
import java.util.*;

public abstract class Output extends OutputStream implements Event.Block {
	private final static String EOL = "\r\n";
	private ByteArrayOutputStream array;
	private byte[] one = new byte[1];
	private boolean chunk, cache;
	protected int length, size;
	protected Reply reply;
	protected boolean init;

	Output(Reply reply) throws IOException {
		this.reply = reply;
		size = reply.event().daemon().size;
	}

	boolean chunk() {
		return chunk;
	}

	public void println(String s) throws IOException {
		write((s + EOL).getBytes("UTF-8"));
	}

	public void print(String s) throws IOException {
		write(s.getBytes("UTF-8"));
	}

	void init() throws IOException {
		if(init) {
			reply.event().log("already inited", Event.DEBUG);
			return;
		}
		else
			reply.event().log("init " + reply.event().query().version(), Event.DEBUG);

		chunk = reply.event().query().version().equalsIgnoreCase("HTTP/1.1");

		if(chunk) {
			/* TODO: What am I doing wrong?
			 * 
			 * Browsers do NOT support "Transfer-Encoding: Chunked" with 
			 * length 0 from status codes that they expect to have 
			 * "Content-Length: 0" for some reason it seems? Or is it my fault?
			 * 
			 * If an Event returns chunked with length 0 it will, upon next 
			 * use, leave the browser waiting for more... then "An established 
			 * connection was aborted by the software in your host machine"
			 * with IE and "Socket closed" with Firefox. Help needed!
			 * 
			 * This workaround works fine for now though.
			 * See end() and Chunked.flush().
			 */
			if(reply.code().startsWith("302") || 
					reply.code().startsWith("304")) {
				headers(0);
			}
			else {
				headers(-1);
			}
		}
		else {
			cache = true;

			if(array == null) {
				array = new ByteArrayOutputStream();
			}
		}

		reply.event().interest(Event.WRITE, false);
		
		init = true;
	}

	void end() throws IOException {
		reply.event().log("end", Event.DEBUG);

		if(!chunk) {
			if(array != null && array.size() > 0) {
				array.flush();

				int length = array.size();
				byte[] data = array.toByteArray();

				headers(length);
				write(data, 0, length);

				array.reset();
			}
			else if(reply.code().startsWith("302") || 
					reply.code().startsWith("304")) {
				headers(0);
			}
		}

		flush();
		
		if(length > 0) {
			reply.event().log("reply " + length, Event.VERBOSE);
			reply.event().interest(Event.READ, false);
		}

		length = 0;
		init = false;
	}

	void headers(int length) throws IOException {
		cache = false;

		reply.event().log(reply.code(), Event.VERBOSE);

		wrote((reply.event().query().version() + " " + reply.code() + EOL).getBytes());
		wrote(("Date: " + reply.event().DATE.format(new Date()) + EOL).getBytes());
		wrote(("Server: Rupy/0.2 (beta)" + EOL).getBytes());
		wrote(("Content-Type: " + reply.type() + EOL).getBytes());
		wrote(("Connection: Keep-Alive" + EOL).getBytes());

		if(length > -1) {
			wrote(("Content-Length: " + length + EOL).getBytes());
		}
		else {
			wrote(("Transfer-Encoding: Chunked" + EOL).getBytes());
		}

		if(reply.modified() > 0) {
			wrote(("Last-Modified: " + reply.event().DATE.format(new Date(reply.modified())) + EOL).getBytes());
		}

		if(reply.event().session() != null && !reply.event().session().set()) {
			wrote(("Cache-Control: private" + EOL).getBytes());
			wrote(("Set-Cookie: session=" + reply.event().session().key() + "; path=/" + EOL).getBytes());

			reply.event().session().set(true);
		}

		if(reply.event().close()) {
			wrote(("Connection: close" + EOL).getBytes());
		}

		HashMap headers = reply.headers();

		if(headers != null) {
			Iterator it = headers.keySet().iterator();

			while(it.hasNext()) {
				String name = (String) it.next();
				String value = (String) reply.headers().get(name);

				wrote((name + ": " + value + EOL).getBytes());
			}
		}

		wrote(EOL.getBytes());
		flush();
		length = 0;
	}

	void wrote(int b) throws IOException {
		one[0] = (byte) b;
		wrote(one);
	}

	void wrote(byte[] b) throws IOException {
		wrote(b, 0, b.length);
	}

	void wrote(byte[] b, int off, int len) throws IOException {
		try {
			if(cache) {
				array.write(b, off, len);
			}
			else {
				ByteBuffer out = reply.event().worker().out();
				int remaining = out.remaining();

				while(len > remaining) {
					out.put(b, off, remaining);

					internal(false);

					off += remaining;
					len -= remaining;
				}

				if(len > 0) {
					out.put(b, off, len);
				}
			}
		}
		catch(IOException e) {
			Failure.chain(e);
		}
	}

	void internal(boolean debug) throws IOException {
		ByteBuffer out = reply.event().worker().out();

		if(out.remaining() < size) {
			out.flip();

			while(out.remaining() > 0) {
				int sent = fill(debug);

				if(debug) {
					reply.event().log("sent " + sent, Event.DEBUG);
				}

				if(sent == 0) {
					reply.event().block(this);

					if(debug) {
						reply.event().log("still in buffer " + out.remaining(), Event.DEBUG);
					}
				}
			}
		}

		out.clear();
	}

	public void flush() throws IOException {
		reply.event().log("flush " + length, Event.DEBUG);
		internal(true);
	}

	public int fill(boolean debug) throws IOException {
		ByteBuffer out = reply.event().worker().out();

		int remaining = 0;

		if(debug) {
			remaining = out.remaining();
		}

		int sent = reply.event().channel().write(out);

		if(debug) {
			reply.event().log("filled " + sent + " out of " + remaining, Event.DEBUG);
		}

		length += sent;
		return sent;
	}

	/* Borrowed from sun.net.httpserver.ChunkedOutputStream.java
	 */
	static class Chunked extends Output {
		private static int OFFSET = 6;
		private int cursor = OFFSET, count = 0;
		private byte[] chunk = new byte[size + OFFSET + 2];

		Chunked(Reply reply) throws IOException {
			super(reply);
		}

		public void write (int b) throws IOException {
			chunk[cursor++] = (byte) b;
			count++;

			if(count == size) {
				write();
			}
		}

		public void write(byte[] b) throws IOException {
			write(b, 0, b.length);
		}

		public void write (byte[] b, int off, int len) throws IOException {
			if(!chunk()) {
				wrote(b, off, len);
				return;
			}

			int remain = size - count;

			if(len > remain) {
				System.arraycopy(b, off, chunk, cursor, remain);

				count = size;
				write();

				len -= remain;
				off += remain;

				while(len > size) {
					System.arraycopy(b, off, chunk, OFFSET, size);

					len -= size;
					off += size;

					count = size;
					write();
				}

				cursor = OFFSET;
			}
			if(len > 0) {
				System.arraycopy(b, off, chunk, cursor, len);
				count += len;
				cursor += len;
			}
		}

		void write() throws IOException {
			char[] header = Integer.toHexString(count).toCharArray();
			int length = header.length, start = 4 - length, cursor;

			for(cursor = 0; cursor < length; cursor++) {
				chunk[start + cursor] = (byte) header[cursor];
			}

			chunk[start + (cursor++)] = '\r';
			chunk[start + (cursor++)] = '\n';
			chunk[start + (cursor++) + count] = '\r';
			chunk[start + (cursor++) + count] = '\n';

			wrote(chunk, start, cursor + count);

			count = 0;
			this.cursor = OFFSET;
		}

		public void flush() throws IOException {
			if(chunk() && init) {
				if(reply.code().startsWith("302") || 
						reply.code().startsWith("304")) {
					reply.event().log("length " + length, Event.DEBUG);
				}
				else {
					if(count > 0) {
						write();
					}

					write();

					reply.event().log("chunk flush " + length, Event.DEBUG);
				}
			}

			super.flush();
		}
	}
}
