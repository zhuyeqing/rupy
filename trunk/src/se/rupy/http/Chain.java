package se.rupy.http;

import java.util.*;

public class Chain extends LinkedList {
	private int next;

	/*
	 * Dynamic size list with positional integrity. If anyone has a better
	 * solution to this please tell me!
	 */
	Link put(Link link) {
		for(int i = 0; i < size(); i++) {
			Link tmp = (Link) super.get(i);
			
			if (link.index() == tmp.index()) {
				return (Link) set(i, link);
			}
			else if (link.index() < tmp.index()) {
				add(i, link);
				return null;
			}
		}
		
		add(link);
		
		return null;
	}

	public void filter(Event event) throws Event, Exception {
		for (int i = 0; i < size(); i++) {
			Service service = (Service) get(i);

			if (event.daemon().timeout > 0) {
				event.session(service);
			}

			service.filter(event);
		}
	}

	void exit(Session session, int type) throws Exception {
		for (int i = 0; i < size(); i++) {
			Service service = (Service) get(i);
			service.session(session, type);
		}
	}

	void reset() {
		next = 0;
	}

	Link next() {
		if (next >= size()) {
			next = 0;
			return null;
		}

		return (Link) get(next++);
	}

	public interface Link {
		public int index();
	}
}
