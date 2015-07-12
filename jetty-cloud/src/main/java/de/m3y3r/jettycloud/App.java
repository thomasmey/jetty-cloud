package de.m3y3r.jettycloud;

import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.sql.DataSource;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.plus.jndi.Resource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Start en embedded Jetty for usage in an cloudfoundry environment
 * see also https://eclipse.org/jetty/documentation/current/embedding-jetty.html
 * @author thomas
 *
 */
public class App implements Runnable {

	private static final String CF_ENV_PORT = "PORT";
	private static final String CF_VCAP_SERVICES = "VCAP_SERVICES";

	private final Logger log;

	public static void main(String... args) {
		App app = new App();
		app.run();
	}

	public App() {
		log = Logger.getLogger(App.class.getName());
	}

	public void run() {

		/* for environment variables see:
		 * http://docs.run.pivotal.io/devguide/deploy-apps/environment-variable.html
		 */
		int port = Integer.valueOf(System.getenv(CF_ENV_PORT));
		int maxThreads = 10;

		try {
			QueuedThreadPool threadPool = new QueuedThreadPool();
			threadPool.setMaxThreads(maxThreads);

			Server server = new Server(threadPool);

			HttpConfiguration httpConfig = new HttpConfiguration();
//			httpConfig.setSecureScheme("https");
//			httpConfig.setSecurePort(8443);
			httpConfig.setOutputBufferSize(32768);
			httpConfig.setRequestHeaderSize(8192);
			httpConfig.setResponseHeaderSize(8192);
			httpConfig.setSendServerVersion(true);
			httpConfig.setSendDateHeader(false);

			ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
			httpConnector.setPort(port);
			httpConnector.setIdleTimeout(30000);
			server.addConnector(httpConnector);

			// JSP support
			ServletHolder jspHolder = new ServletHolder(new org.eclipse.jetty.jsp.JettyJspServlet());
			jspHolder.setName("jsp");
			jspHolder.setInitParameter("xpoweredBy", "false");
			jspHolder.setInitParameter("fork", "false");
			jspHolder.setInitParameter("logVerbosityLevel", "DEBUG");
			jspHolder.setInitParameter("compilerTargetVM", "1.7");
			jspHolder.setInitParameter("compilerSourceVM", "1.7");
			jspHolder.setInitOrder(0);

			WebAppContext wac = new WebAppContext();
			wac.setContextPath("/contextpath");
			URL warUrl = App.class.getClassLoader().getResource("war/yourapp.war");
			wac.setWar(warUrl.toString());
			wac.addServlet(jspHolder, "*.jsp");
			wac.addEventListener(new org.eclipse.jetty.servlet.listener.ELContextCleaner());
			wac.addEventListener(new org.eclipse.jetty.servlet.listener.IntrospectorCleaner());

			// WAT?! - Inspired from https://github.com/jetty-project/embedded-jetty-jsp/blob/master/src/main/java/org/eclipse/jetty/demo/Main.java
			JettyJasperInitializer sci = new JettyJasperInitializer();
			ContainerInitializer initializer = new ContainerInitializer(sci, null);
			List<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
			initializers.add(initializer);
			wac.setAttribute("org.eclipse.jetty.containerInitializers", initializers);
			wac.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());
			wac.addBean(new ServletContainerInitializersStarter(wac), true);

//			ClassLoader classLoader = new URLClassLoader(new URL[0], this.getClass().getClassLoader());
//			wac.setClassLoader(classLoader);
//			wac.setResourceBase(System.getProperty("java.io.tmpdir"));

//			ResourceHandler resourceHandler = new ResourceHandler();
//			resourceHandler.setDirectoriesListed(false);
//			resourceHandler.setWelcomeFiles(new String[] { "index.html" });
//			resourceHandler.setResourceBase("static-content");

			HandlerList handlers = new HandlerList();
			handlers.setHandlers(new Handler[] { wac, /* resourceHandler, */ new DefaultHandler() });
			server.setHandler(handlers);

			/* parse service and bind as DataSource via JNDI */
			Map<String, DataSource> datasource = getDataSourceFromVcapService();
			for(String key: datasource.keySet()) {
				new Resource("jdbc/" + key, datasource.get(key));
			}

			server.setDumpAfterStart(false);
			server.setDumpBeforeStop(false);
			server.setStopAtShutdown(true);

			server.start();
//			server.dumpStdErr();
			server.join();

		} catch(Exception e) {
			log.log(Level.SEVERE, "server failed", e);
		}
	}

	/**
	 * this expects VCAP_SERVICES v2 style json
	 * @return
	 */
	private Map<String, DataSource> getDataSourceFromVcapService() {

		Map<String, DataSource> datasources = new HashMap<>();

		// http://docs.run.pivotal.io/devguide/deploy-apps/environment-variable.html
		String vcapService = System.getenv(CF_VCAP_SERVICES);
		if(vcapService == null) {
			return datasources;
		}

		StringReader reader = new StringReader(vcapService);
		JsonReader jsonReader = Json.createReader(reader);

		JsonStructure js = jsonReader.read();
		assert js.getValueType() == ValueType.OBJECT;

		// extract database services
		JsonObject jo = (JsonObject) js;
		for(String serviceType: jo.keySet()) {

			JsonArray jaSqldb = jo.getJsonArray(serviceType);

			switch(serviceType) {
			case "elephantsql":
				for(JsonValue entry: jaSqldb) {
					assert entry.getValueType() == ValueType.OBJECT;
					JsonObject dbEntry = (JsonObject) entry;

					JsonObject jsCred = dbEntry.getJsonObject("credentials");
					String pUri = jsCred.getString("uri");

					DataSource ds = pgDataSourceFromUrl(pUri);
					datasources.put(dbEntry.getString("name"), ds);
				}
				break;

			case "sqldb":
				break;
			}
		}

		return datasources;
	}

	public static DataSource pgDataSourceFromUrl(String pUri) {

		/* sadly the postgres jdbc driver has no convert utility
		 * to convert a connection string to a jdbc url :-(
		 */
		Pattern pattern = Pattern.compile("postgres://(.+):(.+)@(.+):(\\d+)/(.+)");
		Matcher matcher = pattern.matcher(pUri);
		if(!matcher.matches())
			return null;

		String username = matcher.group(1);
		String password = matcher.group(2);
		String hostname = matcher.group(3);
		String port = matcher.group(4);
		String databaseName = matcher.group(5);

		PGSimpleDataSource datasource = new PGSimpleDataSource();
		datasource.setServerName(hostname);
		datasource.setPassword(password);
		datasource.setPortNumber(Integer.valueOf(port));
		datasource.setUser(username);
		datasource.setDatabaseName(databaseName);

		return datasource;
	}
}
