package se.rupy.http;

import java.io.*;
import java.net.*;

public class Test implements Runnable {
	static String original = "lib/activation.jar";
	static String copy = "copy.jar";

	File file;
	String host;

	Test(String host, File file) throws IOException {
		this.host = host;
		this.file = file;
	}

	void save(String name, InputStream in) throws IOException {
		File file = new File(copy);
		OutputStream out = new FileOutputStream(file);
		int read = 0;

		try {
			read = Deploy.pipe(in, out);

			out.flush();
			out.close();

			if (file.length() == new File(original).length())
				System.out.println(name + " successful.");
		} catch (Exception e) {
			System.out.println(name + " failed. (" + read + ")");
			e.printStackTrace();
		}
	}

	public void run() {
		try {
			URL url = new URL("http://" + host);
			InputStream in = new Deploy.Client().send(url, file, null);

			if (host.endsWith("/error")) {
				System.out.println(Deploy.Client.toString(in));
			} else if (host.endsWith("/io")) {
				save("IO Write", in);
			} else {
				save("Asynchronous", in);
			}
		} catch (ConnectException ce) {
			System.out
					.println("Connection failed, is there a server running on "
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
		boolean update;

		public Service(String identifier) {
			this.path = identifier;
		}

		public String path() {
			return path;
		}

		public void exit(Session session, int type) {
			if (type == 1) {
				System.out.println("Timeout successful.");
				new File(copy).delete();
				System.exit(0);
			} else if (type == 2)
				System.out.println("Socket closed.");
		}

		public void filter(Event event) throws Event, Exception {
			if (path.equals("/io")) {
				try {
					if (read(event.input()) == new File(original).length()) {
						System.out.println("IO Read successful.");
					}
				} catch (Exception e) {
					System.out.println("IO Read failed.");
				}
				write(event.output());
			} else if (path.equals("/async")) {
				if (update) {
					write(event.output());
				} else {
					this.event = event;
					new Thread(this).start();
					update = true;
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
