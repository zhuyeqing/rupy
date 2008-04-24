package se.rupy.http;

/**
 * The service filter is like a servlet which describes its own identifier. You
 * have to be careful how you write your hot-deployable code, see the wiki for
 * more information. The service becomes a singleton instance on the server, so
 * use of static methods is recommended.
 * 
 * @author marc
 */
public abstract class Service implements Chain.Link {
	/**
	 * Normal timeout, the client simply was inactive for too long.
	 */
	public final static int NORMAL = 1;

	/**
	 * The client actively disconnected it's last TCP socket. This will not work
	 * correctly if the server is placed behind a proxy.
	 */
	public final static int FORCED = 2;

	/**
	 * Where in the filter chain is this service? Default position is first
	 * (index 0).
	 * 
	 * @return the index of the service in it's filter chain.
	 */
	public int index() {
		return 0;
	}

	/**
	 * The identifier that should trigger this service. For example "/admin".
	 * You can specify a service that should filter multiple identifiers by
	 * separating them with a ':' character. For example: if you want to
	 * identify a user before multiple services, return "/update:/query" here
	 * and set the index to 0 on the identity service and set index to 1 on the
	 * update and query filters. Then you use the following code to redirect to
	 * the login page for example:
	 * 
	 * <pre>
	 * public void filter(Event event) throw Event {
	 *     event.reply().header(&quot;Location&quot;, &quot;/login&quot;);
	 *     event.reply().code(&quot;302 Found&quot;);
	 *     throw event; // stop the chain
	 * }
	 * </pre>
	 * 
	 * @return the path (URI) to the service(s).
	 */
	public abstract String path();

	/**
	 * Initiate service dependencies. This is called when you hot-deploy the
	 * application / start the server.
	 * 
	 * @throws Exception
	 */
	public void init() throws Exception {
	}

	/**
	 * Free service dependencies. This is called when you hot-deploy the
	 * application.
	 * 
	 * @throws Exception
	 */
	public void done() throws Exception {
	}

	/**
	 * If sessions are used and a session invokes a service, that service will
	 * then be notified when and how that session was removed with a call to
	 * this method.
	 * 
	 * @param session
	 * @param type
	 * @throws Exception
	 */
	public void exit(Session session, int type) throws Exception {
	}

	/**
	 * The service method, equivalent of HttpServlet.service().
	 * 
	 * @param event
	 * @throws Event
	 *             if you want to break the filter chain and disconnect the
	 *             client
	 * @throws Exception
	 */
	public abstract void filter(Event event) throws Event, Exception;

	/**
	 * This method is just a convention, if you use internal redirection. The
	 * filter method should place all it's code into this method so that other
	 * services can execute the service remotely without having to redirect the
	 * client externally with a 302.
	 * 
	 * <pre>
	 * public void filter(Event event) throw Event {
	 *     act(event);
	 * }
	 * </pre>
	 * 
	 * @param event
	 * @throws Event
	 *             if you want to break the filter chain and disconnect the
	 *             client
	 * @throws Exception
	 */
	public static void act(Event event) throws Event, Exception {
	}
}
