package se.rupy.http;

import java.util.*;

class Chain extends LinkedList {
	private int next;

	Link put(Link link) {
		if(size() > 0) {
			int index = 0;
			Link old = null;
			
			if(size() > link.index()) {
				index = link.index();
				old = (Link) super.get(link.index());
			}
			else {
				index = size() - 1;
				old = (Link) super.get(index);

				while(old.index() > link.index() && index > 0) {
					index--;
					old = (Link) super.get(index);
				}
			}
			
			if (link.index() < old.index()) {
				add(index, link);
			} else if (link.index() == old.index()) {
				return (Link) set(index, link);
			} else {
				add(link);
			}
		}
		else {
			add(link);
		}

		return null;
	}

	void filter(Daemon daemon, Event event) throws Event, Exception {
		for (int i = 0; i < size(); i++) {
			Service service = (Service) get(i);

			if (daemon.timeout > 0) {
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
