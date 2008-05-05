package se.rupy.http;

import java.util.HashMap;

/**
 * Convenience class to avoid casting and parsing everywhere.
 * 
 * @author marc
 */
public class Hash extends HashMap {
	public long large(String key) {
		Object value = super.get(key);

		if (value == null) {
			return 0;
		} else if (value instanceof Long) {
			return ((Long) value).longValue();
		} else if (value instanceof String) {
			return Long.parseLong((String) value);
		}

		throw new ClassCastException();
	}
	
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
	
	public short small(String key) {
		Object value = super.get(key);

		if (value == null) {
			return 0;
		} else if (value instanceof Integer) {
			return ((Short) value).shortValue();
		} else if (value instanceof String) {
			return Short.parseShort((String) value);
		}

		throw new ClassCastException();
	}

	public byte tiny(String key) {
		Object value = super.get(key);

		if (value == null) {
			return 0;
		} else if (value instanceof Integer) {
			return ((Byte) value).byteValue();
		} else if (value instanceof String) {
			return Byte.parseByte((String) value);
		}

		throw new ClassCastException();
	}
	
	/**
	 * Returns the boolean value, with a twist though 
	 * since a parameter is true if its key is present.
	 * @param key
	 * @param exist
	 * @return if the parameter is true or exists
	 */
	public boolean bool(String key, boolean exist) {
		Object value = super.get(key);

		if (value == null) {
			return false;
		} else if (value instanceof Boolean) {
			return ((Boolean) value).booleanValue();
		} else if (value instanceof String) {
			return (exist ? true : Boolean.parseBoolean((String) value));
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
