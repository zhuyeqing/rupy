package se.rupy.http;

import java.io.*;
import java.net.*;

class Test implements Runnable {
	String[] unit = new String[] {
			"comet", 
			"chunk", 
			"fixed", 
			"error"
		};
	
	static String original = "bin/http.jar";

	protected boolean failed;

	protected static File file;

	protected int loop;
	protected String host, name;
	protected Service service;
	protected Daemon daemon;
	
	protected Test(Daemon daemon, int loop) {
		this.loop = loop;
		Test.file = new File(Test.original);
		this.daemon = daemon;
	}
	
	protected Test(String host, String name, int loop) throws IOException {
		this.host = host;
		this.name = name;
		this.loop = loop;
		this.service = new Service(name);
	}

	protected Service service() {
		return service;
	}
	
	protected boolean failed() {
		return failed && service.failed();
	}
	
	protected String name() {
		return name;
	}
	
	void save(String name, InputStream in) {
		int read = 0;

		try {
			OutputStream out = new FileOutputStream(new File(name));
			
			read = Deploy.pipe(in, out);

			out.flush();
			out.close();

			if (file.length() != new File(original).length()) {
				failed = true;
			}
		} catch (Exception e) {
			System.out.println(name + " failed. (" + read + ")");
			e.printStackTrace();
			failed = true;
		}
	}

	public void run() {
		try {
			if(daemon != null) {
				test(daemon);
				return;
			}
			
			for(int i = 0; i < loop; i++) {
				connect();
			}
		} catch (ConnectException ce) {
			System.out.println("Connection failed, is there a server on "
					+ host + "?");
		} catch (Exception e) {
			e.printStackTrace();
			failed = true;
			
			if(daemon != null) {
				System.exit(1);
			}
		}
		// finally {
		// System.exit(0);
		// }
	}
	
	/*
	 * Test cases are performed in parallel with one worker thread, in order to
	 * detect synchronous errors.
	 */
	void test(Daemon daemon) throws Exception {
		int wait = loop + 5;
		
		System.out.println("Parallel testing begins in one second:");
		System.out.println("- OP_READ, OP_WRITE and selector wakeup.");
		System.out.println("- Fixed and chunked, read and write.");
		System.out.println("- Asynchronous non-blocking reply.");
		System.out.println("- Session creation and timeout.");
		System.out.println("- Exception handling.");
		System.out.println("The test runs " + (loop * 100) + " (50kb) fixed-, " + 
				(loop * 100) + " (50kb) chunked-, " + (loop * 100) + " error-, " + 
				loop + " comet- requests and takes " + wait + " seconds.");
		System.out.println("             ---o---");

		Thread.sleep(200);

		/*
		daemon.verbose = true;
		daemon.debug = true;
		*/

		Test[] test = new Test[unit.length];
		
		for(int i = 0; i < test.length; i++) {
			test[i] = new Test("localhost:" + daemon.port, unit[i], loop * (unit[i].equals("/comet") ? 1 : 100));
			daemon.add(test[i].service());
			new Thread(test[i]).start();
		}
		
		Thread.sleep(wait * 1000);
		
		boolean failed = false;
		
		for(int i = 0; i < test.length; i++) {
			if(test[i].failed()) {
				failed = true;
			}
			
			new File(test[i].name).deleteOnExit();
		}
		
		System.out.println(failed ? "UNIT FAILED!" : "UNIT SUCCESSFUL!");
		System.exit(0);
	}
	
	private void connect() throws IOException {
		URL url = new URL("http://" + host + "/" + name);

		if (name.equals("error")) {
			if(Deploy.Client.toString(new Deploy.Client().send(url, null, null, true)).indexOf("Error Succeded") == -1) {
				failed = true;
			}
		} else if (name.equals("chunk")) {
			save("Chunk", new Deploy.Client().send(url, file, null, true));
		} else if (name.equals("fixed")) {
			save("Fixed", new Deploy.Client().send(url, file, null, false));
		} else if (name.equals("comet")) {
			save("Comet", new Deploy.Client().send(url, null, null, true));
		}
	}
	
	static class Service extends se.rupy.http.Service implements Runnable {
		protected static boolean session;
		protected static boolean timeout;
		
		protected String path;
		protected Event event;
		
		protected boolean failed;

		public Service(String name) {
			this.path = "/" + name;
		}

		public String path() {
			return path;
		}

		protected boolean failed() {
			return failed;
		}
		
		public void session(Session session, int type) {
			if (type == Service.CREATE) {
				if (!this.session) {
					System.out.println("Session successful.");
					this.session = true;
				}
			} else if (type == Service.TIMEOUT) {
				if (!this.timeout) {
					System.out.println("Timeout successful.");
					this.timeout = true;
				}
			} else {
				/*
				 * FORCED, HttpURLConnection timeout, has the time to happen
				 * sometimes.
				 */
				System.out.println("Socket closed. (" + type + ")");
			}
		}

		public void filter(Event event) throws Event, Exception {
			if (path.equals("/chunk")) {
				try {
					int read = read(event.input());
					if (read != new File(original).length()) {
						failed = true;
					}
				} catch (Exception e) {
					e.printStackTrace();
					failed = true;
				}
				write(event.output());
			} else if (path.equals("/fixed")) {
				try {
					int read = read(event.input());
					if (read != new File(original).length()) {
						failed = true;
					}
				} catch (Exception e) {
					e.printStackTrace();
					failed = true;
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
