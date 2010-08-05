package se.rupy.http;

import java.io.*;
import java.net.*;

class Test implements Runnable {
	static String original = "bin/http.jar";
	static String chunk = "chunk.jar";
	static String fixed = "fixed.jar";

	static boolean done;

	File file;
	String host;

	Test(String host, File file) throws IOException {
		this.host = host;
		this.file = file;
	}

	void save(String name, InputStream in) throws IOException {
		File file = new File(chunk);
		OutputStream out = new FileOutputStream(file);
		int read = 0;

		try {
			read = Deploy.pipe(in, out);

			out.flush();
			out.close();

			if (file.length() == new File(original).length())
				System.out.println(name + " successful. (" + read + ")");
		} catch (Exception e) {
			System.out.println(name + " failed. (" + read + ")");
			e.printStackTrace();
		}
	}

	public void run() {
		try {
			URL url = new URL("http://" + host);
			InputStream in = new Deploy.Client().send(url, file, null, !host.endsWith("/fixed"));

			if (host.endsWith("/error")) {
				System.out.println(Deploy.Client.toString(in));
			} else if (host.endsWith("/chunk")) {
				save("Chunk", in);
			} else if (host.endsWith("/fixed")) {
				save("Fixed", in);
			} else if (host.endsWith("/comet")) {
				save("Comet", in);
			}
		} catch (ConnectException ce) {
			System.out.println("Connection failed, is there a server on "
					+ host + "?");
		} catch (IOException e) {
			e.printStackTrace();
		}
		// finally {
		// System.exit(0);
		// }
	}

	static class Service extends se.rupy.http.Service implements Runnable {
		String path;
		Event event;

		public Service(String identifier) {
			this.path = identifier;
		}

		public String path() {
			return path;
		}

		public void session(Session session, int type) {
			if (type == Service.CREATE) {
				if (!done) {
					System.out.println("Session successful.");
					done = true;
				}
			} else if (type == Service.TIMEOUT) {
				System.out.println("Timeout successful.");
				new File(chunk).delete();
				new File(fixed).delete();
				System.exit(0);
			} else {
				/*
				 * FORCED, HttpURLConnection timeout, has the time to happen
				 * sometimes.
				 */
				System.out.println("Socket closed.");
			}
		}

		public void filter(Event event) throws Event, Exception {
			if (path.equals("/chunk")) {
				try {
					int read = read(event.input());
					if (read == new File(original).length()) {
						System.out.println("Chunk successful. (" + read + ")");
					}
				} catch (Exception e) {
					System.out.println("Chunk failed.");
					e.printStackTrace();
				}
				write(event.output());
			} else if (path.equals("/fixed")) {
				try {
					int read = read(event.input());
					if (read == new File(original).length()) {
						System.out.println("Fixed successful. (" + read + ")");
					}
				} catch (Exception e) {
					System.out.println("Fixed failed.");
					e.printStackTrace();
				}
				
				write(event.reply().output(new File(original).length()));
			} else if (path.equals("/comet")) {
				if (event.push()) {
					write(event.output());
					event.output().finish(); // important
				} else {
					/*
					 * In a real application managing the push events is the
					 * tricky part, making sure there is no memory leak can be
					 * very difficult. See our Comet tutorial for more info.
					 */
					this.event = event;
					new Thread(this).start();
				}
			} else {
				throw new Exception("Error successful.");
			}
		}

		public void run() {
			try {
				Thread.sleep(1000);
				event.reply().wakeup();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		int read(InputStream in) throws IOException {
			OutputStream out = new ByteArrayOutputStream();
			return Deploy.pipe(in, out);
		}

		int write(OutputStream out) throws IOException {
			File file = new File(original);
			InputStream in = new FileInputStream(file);
			return Deploy.pipe(in, out);
		}
	}

}
