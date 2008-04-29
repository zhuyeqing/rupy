package se.rupy.http;

public class Session {
	private Daemon daemon;
	private Chain service;
	private Chain event;
	private Object value;
	private boolean set;
	private String key, domain;
	private long date, expires;

	Session(Daemon daemon) {
		this.daemon = daemon;
		
		service = new Chain();
		event = new Chain();
		
		touch();
	}

	void add(Service service) {
		if (!this.service.contains(service)) {
			this.service.add(service);
		}
	}

	void add(Event event) {
		if (!this.event.contains(event)) {
			this.event.add(event);
		}
	}

	void remove() throws Exception {
		remove(null);
	}

	boolean remove(Event event) throws Exception {
		if (event == null) {
			this.event.clear();
			service.exit(this, Service.NORMAL);
			return true;
		} else {
			boolean found = this.event.remove(event);
			if (this.event.isEmpty() && found) {
				service.exit(this, Service.FORCED);
				return true;
			}
		}

		return false;
	}

	boolean set() {
		return set;
	}

	void set(boolean set) {
		this.set = set;
	}

	long expires() {
		return expires;
	}

	void expires(long expires) {
		this.expires = expires;
	}

	public String key() {
		return key;
	}

	void key(String key) {
		this.key = key;
		set = false;
	}
	
	public String domain() {
		return domain;
	}
	
	/**
	 * Set the key you wish to store in the clients cookie here, together with
	 * the expire date. You can only set one at the time and it will be for the
	 * path=/;
	 * 
	 * @param key
	 * @param expires
	 */
	public void key(String key, String domain, long expires) {
		if(key == null)
			return;
		
		synchronized (daemon.session()) {
			daemon.session().remove(this.key);
			this.key = key;
			daemon.session().put(key, this);
		}
		
		this.domain = domain;
		this.expires = expires;
		
		set = false;
	}

	public Object value() {
		return value;
	}

	/**
	 * If you save a class that is hot-deployed here it will throw a
	 * ClassCastException if you re-deploy the application. We advise to only
	 * store bootclasspath loaded classes here.
	 * 
	 * @param value
	 */
	public void value(Object value) {
		this.value = value;
	}

	public long date() {
		return date;
	}

	void touch() {
		date = System.currentTimeMillis();
	}
}
