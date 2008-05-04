package se.rupy.http;

import java.util.HashMap;

public class Hash extends HashMap {
	public int integer(String key) {
		Object value = super.get(key);

		if (value == null) {
			return 0;
		} else if (value instanceof Integer) {
			return ((Integer) value).intValue();
		} else if (value instanceof String) {
			return Integer.parseInt((String) value);
		}

		throw new ClassCastException();
	}

	public boolean bool(String key) {
		Object value = super.get(key);

		if (value == null) {
			return false;
		} else if (value instanceof Boolean) {
			return ((Boolean) value).booleanValue();
		} else if (value instanceof String) {
			return Boolean.parseBoolean((String) value);
		}

		throw new ClassCastException();
	}

	public String string(String key) {
		String value = (String) super.get(key);

		if (value == null) {
			return "";
		}

		return value;
	}
	
	public void put(String key, int value) {
		super.put(key, new Integer(value));
	}
	
	public void put(String key, boolean value) {
		super.put(key, new Boolean(value));
	}
}
