package se.rupy.http;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.text.*;
import java.util.*;

/**
 * HTTP request and query in one.
 * 
 * @author marc
 */
public class Query extends Hash {
	public final static int GET = 1 << 0, POST = 1 << 1;
	private String path, version, parameters;
	private HashMap headers;
	private Input input;
	private int length, method;
	private long modified;
	private boolean done;

	Query(Event event) throws IOException {
		headers = new HashMap();
		input = new Input.Chunked(event);
	}

	void headers() throws IOException {
		String line = input.line();
		StringTokenizer http = new StringTokenizer(line, " ");
		String method = http.nextToken();

		if (method.equalsIgnoreCase("get")) {
			this.method = GET;
		} else if (method.equalsIgnoreCase("post")) {
			this.method = POST;
		} else {
			throw new IOException("Unsupported method.");
		}

		String get = http.nextToken();
		int index = get.indexOf('?');

		if (index > 0) {
			path = get.substring(0, index);
			parameters = get.substring(index + 1);
		} else {
			path = get;
			parameters = null;
		}

		version = http.nextToken();
		line = input.line();
		int lines = 0;

		while (line != null && !line.equals("")) {
			int colon = line.indexOf(":");

			if (colon > -1) {
				String name = line.substring(0, colon).toLowerCase();
				String value = line.substring(colon + 1).trim();

				headers.put(name, value);
			}

			line = input.line();
			lines++;

			if (lines > 30) {
				throw new IOException("Too many headers.");
			}
		}

		if (header("transfer-encoding").equalsIgnoreCase("chunked")) {
			length = -1;
		} else {
			length = Integer.parseInt(header("content-length"));
		}

		String since = header("if-modified-since");

		if (!since.equals("0")) {
			try {
				modified = input.event().DATE.parse(since).getTime();
			} catch (ParseException e) {
				e.printStackTrace();
			}
		}

		if (header("connection").equalsIgnoreCase("close")) {
			input.event().close(true);
		}

		clear();

		input.event().log(
				method + " " + (length > -1 ? "" + length : "*") + " " + path,
				Event.VERBOSE);
		input.init();
	}

	/**
	 * Parse the parameters from GET or POST. This method will only parse once
	 * per query and cache the result so don't be afraid of calling this method.
	 * 
	 * @throws Exception
	 */
	public void parse() throws Exception {
		parse(input.event().daemon().size);
	}

	void parse(int size) throws Exception {
		if (parameters == null) {
			if (method == POST && length > 0) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				if (Deploy.pipe(input, out, size, size) > 0)
					parameters = new String(out.toByteArray());
			} else {
				return;
			}
		} else if (!isEmpty()) {
			return;
		}

		parameters = new URLDecoder().decode(parameters, "UTF-8");

		input.event().log(parameters, Event.VERBOSE);

		if (parameters != null) {
			StringTokenizer amp = new StringTokenizer(parameters, "&");

			while (amp.hasMoreTokens()) {
				StringTokenizer equ = new StringTokenizer(amp.nextToken(), "=");

				String key = equ.nextToken();
				String value = "";

				if (equ.hasMoreTokens()) {
					value = equ.nextToken();
				}

				put(key, value);
			}
		}
	}

	void done() throws IOException {
		headers.clear();
		input.end();
		modified = 0;
	}

	public int method() {
		return method;
	}

	public String path() {
		return path;
	}

	public String version() {
		return version;
	}

	public String type() {
		return header("content-type");
	}

	public long modified() {
		return modified;
	}

	public int length() {
		return length;
	}

	/**
	 * Important: this returns "0" if the header is not found!
	 * 
	 * @param name
	 * @return the header value or "0".
	 */
	public String header(String name) {
		String value = (String) headers.get(name);
		return value == null ? "0" : value;
	}

	public Input input() {
		return input;
	}
}
