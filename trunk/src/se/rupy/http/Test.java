package se.rupy.http;

import java.io.*;
import java.net.*;

class Test implements Runnable {
	final static int other_count = 100;
	final static int comet_count = 10;
	final static int comet_sleep = 50;
	final static String[] unit = new String[] {
			"comet", 
			"chunk", 
			"fixed", 
			"error"
		};
	
	final static String original = "bin/http.jar";

	protected boolean failed;
	protected int loop, done, http;
	protected String host, name;
	protected Service service;
	protected Daemon daemon;
	protected Thread thread;
	protected long time;
	
	protected static File file;
	protected static Test test;
	
	protected Test(Daemon daemon, int loop) {
		this.loop = loop;
		Test.file = new File(Test.original);
		this.daemon = daemon;
		
		test = this;
		
		thread = new Thread(this);
		thread.start();
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
		return failed || service.failed();
	}
	
	protected String name() {
		return name;
	}
	
	protected void done(Test test) {
		done++;
		http++;
		System.out.println(done + "/" + (unit.length + 2) + " Done: " + test.name + " (" + test.loop + ")");
		
		if(http == unit.length) {
			System.out.println((3 * other_count * loop + comet_count * loop) + " dynamic requests in " + (System.currentTimeMillis() - time) + " ms.");
		}
		
		done();
	}
	
	protected void done(String text) {
		done++;
		System.out.println(done + "/" + (unit.length + 2) + " Done: " + text);
		done();
	}
	
	protected void done() {
		if(done == unit.length + 2) {
			synchronized (thread) {
				thread.notify();
			}
		}
	}
	
	void save(String name, InputStream in) {
		int read = 0;

		try {
			File file = new File(name);
			OutputStream out = new FileOutputStream(file);
			
			read = Deploy.pipe(in, out);

			out.flush();
			out.close();

			if (file.length() != Test.file.length()) {
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
			//e.printStackTrace();
			failed = true;
			
			if(daemon != null) {
				System.exit(1);
			}
		}
		finally {
			test.done(this);
		}
	}
	
	/*
	 * Test cases are performed in parallel with one worker thread, in order to
	 * detect synchronous errors.
	 */
	void test(Daemon daemon) throws Exception {
		System.out.println("Parallel testing with one worker thread:");
		System.out.println("- Fixed and chunked, read and write.");
		System.out.println("- Asynchronous non-blocking reply.");
		System.out.println("- Session creation and timeout.");
		System.out.println("- Exception handling.");
		System.out.println("NOTICE: The test receives and sends the bin/http.jar");
		System.out.println("which is >50kb, if you wonder why it takes ~2 seconds.");
		System.out.println("             ---o---");

		Thread.sleep(100);

		time = System.currentTimeMillis();
		
		/*
		daemon.verbose = true;
		daemon.debug = true;
		*/

		Test[] test = new Test[unit.length];
		
		for(int i = 0; i < test.length; i++) {
			test[i] = new Test("localhost:" + daemon.port, unit[i], loop * (unit[i].equals("comet") ? comet_count : other_count));
			daemon.add(test[i].service());
			Thread thread = new Thread(test[i]);
			thread.start();
		}

		synchronized (thread) {
			thread.wait();
		}
		
		boolean failed = false;
		
		for(int i = 0; i < test.length; i++) {
			if(test[i].failed()) {
				failed = true;
			}
			
			new File(test[i].name).deleteOnExit();
		}
		
		System.out.println(failed ? "UNIT FAILED! (see log/error.txt)" : "UNIT SUCCESSFUL!");
		System.exit(0);
	}
	
	private void connect() throws IOException {
		URL url = new URL("http://" + host + "/" + name);

		if (name.equals("error")) {
			String error = Deploy.Client.toString(new Deploy.Client().send(url, null, null, true));
			if(error.indexOf("Error successful") == -1) {
				failed = true;
			}
		} else {
			save(name, new Deploy.Client().send(url, file, null, true));
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
				if (!Service.session) {
					test.done("Session successful.");
					Service.session = true;
				}
			} else if (type == Service.TIMEOUT) {
				if (!Service.timeout) {
					test.done("Timeout successful.");
					Service.timeout = true;
				}
			} else {
				/*
				 * FORCED, HttpURLConnection timeout, has the time to happen
				 * sometimes.
				 */
				System.out.println("Socket closed. (" + path + ")");
			}
		}

		public void filter(Event event) throws Event, Exception {
			try {
				work(event);
			}
			catch(Exception e) {
				//e.printStackTrace();
				failed = true;
				throw e;
			}
			
			if (path.equals("/error")) {
				throw new Exception("Error successful.");
			}
		}

		private void work(Event event) throws Exception {
			if (path.equals("/chunk")) {
				load(event);
				write(event.output());
			} else if (path.equals("/fixed")) {
				load(event);
				write(event.reply().output(Test.file.length()));
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
					load(event);
					this.event = event;
					new Thread(this).start();
				}
			}
		}
		
		private void load(Event event) throws IOException {
			int read = read(event.input());
			if (read != Test.file.length()) {
				failed = true;
			}
		}
		
		public void run() {
			try {
				Thread.sleep(comet_sleep);
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
