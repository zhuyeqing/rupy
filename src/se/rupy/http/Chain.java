package se.rupy.http;

import java.util.*;

class Chain extends LinkedList {
	private int next;

	/*
	 * smart add to maintain positional integrity and return old link
	 */
	Link put(Link link) {
		if (size() > link.index()) {
			Link old = (Link) super.get(link.index());

			if (link.index() == old.index()) {
				return (Link) set(link.index(), link);
			} else {
				add(link.index(), link);
			}
		} else if(size() > 0) {
			Link old = (Link) super.get(size() - 1);
			
			if(old.index() > link.index()) {
				add(size() - 1, link);
			}
			else {
				add(link);
			}
		}
		else {
			add(link);
		}

		return null;
	}

	/*
	 * smart remove to maintain positional integrity and return old link
	 */
	Link del(Link link) {
		if (size() > link.index()) {
			Link old = (Link) super.get(link.index());

			if (link.index() == old.index()) {
				return (Link) super.remove(link.index());
			}
		} else {
			remove(link);
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
