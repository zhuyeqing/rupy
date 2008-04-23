package se.rupy.http;

public class Session {
	private Chain service;
	private Chain event;
	private Object value;
	private boolean set;
	private String key;
	private long date;

	Session(String key) {
		service = new Chain();
		event = new Chain();
		this.key = key;
		touch();
	}

	void add(Service service) {
		if(!this.service.contains(service)) {
			this.service.add(service);
		}
	}

	void add(Event event) {
		if(!this.event.contains(event)) {
			this.event.add(event);
		}
	}

	void remove() throws Exception {
		remove(null);
	}

	boolean remove(Event event) throws Exception {
		if(event == null) {
			this.event.clear();
			service.exit(this, Service.NORMAL);
			return true;
		}
		else {
			event.session(null);
			boolean found = this.event.remove(event);
			
			if(this.event.isEmpty() && found) {
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

	public String key() {
		return key;
	}

	public Object value() {
		return value;
	}

	/**
	 * If you save a class that is hot-deployed here it will throw 
	 * a ClassCastException if you re-deploy the application. We 
	 * advise to only store classpath loaded classes here.
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
