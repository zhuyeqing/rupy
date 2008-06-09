package se.rupy.http;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;

/**
 * Hot-deploys an application containing one or many service filters from disk
 * with simplistic dynamic class loading, eventually after receiving it through
 * a HTTP POST. Observe that content resources need to have a matching service
 * path in order to deploy, otherwise deployments could overwrite each others
 * content.
 * 
 * @author marc
 */
public class Deploy extends Service {
	private static String path, pass;

	public Deploy(String path, String pass) {
		Deploy.path = path;
		Deploy.pass = pass;

		new File(path).mkdirs();
	}

	public String path() {
		return "/deploy";
	}

	public void filter(Event event) throws Event, Exception {
		String name = event.query().header("file");
		String pass = event.query().header("pass");

		if (name.equals("0")) {
			throw new Failure("File header missing.");
		}

		if (pass.equals("0")) {
			throw new Failure("Pass header missing.");
		} else if (!Deploy.pass.equals(pass)) {
			throw new Failure("Pass verification failed. (" + pass + ")");
		}

		File file = new File(path + name);
		OutputStream out = new FileOutputStream(file);
		InputStream in = event.query().input();

		pipe(in, out, 10240);

		out.flush();
		out.close();

		String deploy = deploy(event.daemon(), file);

		event.reply().output().print("Application '" + deploy + "' deployed.");
	}

	public static String deploy(Daemon daemon, File file) {
		try {
			Loader loader = new Loader(daemon, file);
			HashMap content = loader.content();
			Iterator it = loader.service().iterator();

			daemon.add(content);

			while (it.hasNext()) {
				Service service = (Service) it.next();
				daemon.remove(service);
				daemon.add(service);
			}

			return loader.name();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	static class Loader extends ClassLoader {
		HashSet service;
		HashMap content;
		String name;
		long date;

		Loader(Daemon daemon, File file) {
			service = new HashSet();
			content = new HashMap();

			name = file.getName();
			date = file.lastModified();

			try {
				JarInputStream in = new JarInput(new FileInputStream(file));
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				JarEntry entry = null;

				Vector classes = new Vector();
				Vector content = new Vector();

				while ((entry = in.getNextJarEntry()) != null) {
					if (entry.getName().endsWith(".class")) {
						pipe(in, out);
						byte[] data = out.toByteArray();
						out.reset();

						String name = name(entry.getName());
						classes.add(new Small(name, data));
					} else if (!entry.isDirectory()) {
						content.add(new Big("/" + entry.getName(), in, date));
					}
				}

				int length = classes.size();
				Small small = null;

				while (classes.size() > 0) {
					try {
						small = (Small) classes.elementAt(0);
						classes.removeElement(small);
						instantiate(small);
					} catch (NoClassDefFoundError e) {
						// the superclass has still not been loaded yet
						classes.addElement(small);
						length--;
						if (length < 0) {
							throw e;
						}
					}
				}

				Stream stream = null;
				Iterator it = service.iterator();

				while (it.hasNext()) {
					Service service = (Service) it.next();

					StringTokenizer paths = new StringTokenizer(service.path(),
							":");

					while (paths.hasMoreTokens()) {
						String path = paths.nextToken();

						Enumeration en = content.elements();

						while (en.hasMoreElements()) {
							stream = (Stream) en.nextElement();

							// so that deploys can't overwrite each others
							// content
							if (stream.name().startsWith(path)) {
								this.content.put(stream.name(), stream);
							}
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		void instantiate(Small small) throws Exception {
			if (small.clazz == null) {
				small.clazz = defineClass(small.name, small.data, 0,
						small.data.length);
			}

			Class service = small.clazz.getSuperclass();

			if (service != null
					&& service.getCanonicalName()
							.equals("se.rupy.http.Service")) {
				this.service.add((Service) small.clazz.newInstance());
			}
		}

		String name(String name) {
			name = name.substring(0, name.indexOf("."));
			name = name.replace("/", ".");
			return name;
		}

		public String name() {
			return name;
		}

		public long date() {
			return date;
		}

		public HashMap content() {
			return content;
		}

		public HashSet service() {
			return service;
		}
	}

	static class Big implements Stream {
		private File file;
		private String name;
		private long date;

		public Big(String name, InputStream in, long date) throws IOException {
			String path = name.substring(0, name.lastIndexOf("/"));
			String folder = Deploy.path + "content";

			new File(folder + path).mkdirs();

			file = new File(folder + name);
			file.createNewFile();

			pipe(in, new FileOutputStream(file), 10240);

			this.name = name;
			this.date = date - date % 1000;
		}

		public String name() {
			return name;
		}

		public InputStream input() {
			try {
				return new FileInputStream(file);
			} catch (FileNotFoundException e) {
				return null;
			}
		}

		public long length() {
			return file.length();
		}

		public long date() {
			return date;
		}
	}

	static class Small implements Stream {
		private String name;
		private byte[] data;
		private long date;
		private Class clazz;

		public Small(String name, byte[] data) {
			this(name, data, 0);
		}

		public Small(String name, byte[] data, long date) {
			this.name = name;
			this.data = data;
			this.date = date - date % 1000;
		}

		public String name() {
			return name;
		}

		public InputStream input() {
			return new ByteArrayInputStream(data);
		}

		public long length() {
			return data.length;
		}

		public long date() {
			return date;
		}

		byte[] data() {
			return data;
		}
	}

	static interface Stream {
		public String name();
		public InputStream input();
		public long length();
		public long date();
	}

	static class Client {
		InputStream send(URL url, File file, String pass) throws IOException {
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");

			OutputStream out = null;
			InputStream in = null;

			if (file != null) {
				conn.addRequestProperty("File", file.getName());

				if (pass != null) {
					conn.addRequestProperty("Pass", pass);
				}

				conn.setChunkedStreamingMode(0);
				conn.setDoOutput(true);

				out = conn.getOutputStream();
				in = new FileInputStream(file);

				pipe(in, out);

				out.flush();
				in.close();
			}

			int code = conn.getResponseCode();

			if (code == 200) {
				in = conn.getInputStream();
			} else if (code < 0) {
				throw new IOException("HTTP response unreadable.");
			} else {
				in = conn.getErrorStream();
			}

			return in;
		}

		static String toString(InputStream in) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			pipe(in, out);

			out.close();
			in.close();

			return new String(out.toByteArray());
		}
	}

	public static String name(String name) {
		name = name.substring(0, name.indexOf("."));
		name = name.replace("/", ".");
		return name;
	}
	
	public static int pipe(InputStream in, OutputStream out) throws IOException {
		return pipe(in, out, 1024, 0);
	}

	public static int pipe(InputStream in, OutputStream out, int length)
			throws IOException {
		return pipe(in, out, length, 0);
	}

	public static int pipe(InputStream in, OutputStream out, int length,
			int limit) throws IOException {
		byte[] data = new byte[length];
		int total = 0, read = in.read(data);
		while (read > -1) {
			if (limit > 0 && total > limit) {
				throw new IOException("Max allowed bytes read. (" + limit + ")");
			}
			total += read;
			out.write(data, 0, read);
			read = in.read(data);
		}
		return total;
	}
	
	/**
	 * Avoids the jar stream being cutoff.
	 * @author marc.larue
	 */
	public static class JarInput extends JarInputStream {
		public JarInput(InputStream in) throws IOException {
			super(in);
		}

		public void close() {
			// geez
		}
	}
	
	/**
	 * <pre>
	 * &lt;target name="deploy"&gt;
	 * &lt;java fork="yes" 
	 *     classname="se.rupy.http.Deploy" 
	 *     classpath="http.jar"&gt;
     *      &lt;arg line="localhost:8000"/&gt;&lt;!-- any host:port --&gt;
     *      &lt;arg line="service.jar"/&gt;&lt;!-- your application jar --&gt;
     *      &lt;arg line="secret"/&gt;&lt;!-- see run.bat and run.sh --&gt;
     * &lt;/java&gt;
     * &lt;/target&gt;
     * </pre>
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length > 2) {
			try {
				URL url = new URL("http://" + args[0] + "/deploy");
				File file = new File(args[1]);
				InputStream in = new Client().send(url, file, args[2]);
				System.out.println(Client.toString(in));
			} catch (ConnectException ce) {
				System.out
						.println("Connection failed, is there a server running on "
								+ args[0] + "?");
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			System.out.println("Usage: Deploy [host] [file] [pass]");
		}
	}
}
