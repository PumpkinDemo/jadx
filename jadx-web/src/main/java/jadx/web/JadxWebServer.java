package jadx.web;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import io.javalin.websocket.WsContext;

import jadx.core.Jadx;
import jadx.web.api.CodeController;
import jadx.web.api.ProjectController;
import jadx.web.api.ResourceController;
import jadx.web.api.ReferencesController;
import jadx.web.api.SearchController;
import jadx.web.api.TreeController;
import jadx.web.core.WebJadxWrapper;

public class JadxWebServer {
	private static final Logger LOG = LoggerFactory.getLogger(JadxWebServer.class);

	private final JadxWebArgs webArgs;
	private final WebJadxWrapper wrapper;
	private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();
	private Javalin app;

	public JadxWebServer(JadxWebArgs webArgs) {
		this.webArgs = webArgs;
		this.wrapper = new WebJadxWrapper();
	}

	public void start() {
		LOG.info("Starting jadx-web server v{}", Jadx.getVersion());

		// Load the input file if provided via CLI
		if (!webArgs.getInputFiles().isEmpty()) {
			wrapper.load(webArgs.getInputFiles());
		}

		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		JsonMapper gsonMapper = new JsonMapper() {
			@NotNull
			@Override
			public <T> T fromJsonString(@NotNull String json, @NotNull Type targetType) {
				return gson.fromJson(json, targetType);
			}

			@NotNull
			@Override
			public <T> T fromJsonStream(@NotNull InputStream json, @NotNull Type targetType) {
				return gson.fromJson(new java.io.InputStreamReader(json), targetType);
			}

			@NotNull
			@Override
			public String toJsonString(@NotNull Object obj, @NotNull Type type) {
				return gson.toJson(obj, type);
			}
		};

		app = Javalin.create(config -> {
			config.showJavalinBanner = false;
			config.jsonMapper(gsonMapper);

			// Serve frontend static files from classpath
			config.staticFiles.add(staticFileConfig -> {
				staticFileConfig.hostedPath = "/";
				staticFileConfig.directory = "/web";
				staticFileConfig.location = Location.CLASSPATH;
			});

			// SPA: serve index.html for any non-file, non-API route
			config.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH);

			// Enable CORS for development
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(rule -> {
					rule.anyHost();
				});
			});
		});

		// Global error handler for API routes
		app.exception(IllegalStateException.class, (e, ctx) -> {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		});
		app.exception(Exception.class, (e, ctx) -> {
			LOG.error("Unhandled error for {} {}", ctx.method(), ctx.path(), e);
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		});

		// Register API routes
		registerRoutes();

		// WebSocket for real-time events
		app.ws("/ws/events", ws -> {
			ws.onConnect(ctx -> {
				wsClients.add(ctx);
				LOG.debug("WebSocket client connected, total: {}", wsClients.size());
			});
			ws.onClose(ctx -> {
				wsClients.remove(ctx);
				LOG.debug("WebSocket client disconnected, total: {}", wsClients.size());
			});
		});

		app.start(webArgs.getHost(), webArgs.getPort());
		LOG.info("jadx-web server started at http://{}:{}", webArgs.getHost(), webArgs.getPort());
	}

	private void registerRoutes() {
		ProjectController projectController = new ProjectController(wrapper);
		TreeController treeController = new TreeController(wrapper);
		CodeController codeController = new CodeController(wrapper);
		ResourceController resourceController = new ResourceController(wrapper);
		SearchController searchController = new SearchController(wrapper);
		ReferencesController referencesController = new ReferencesController(wrapper);

		// Project endpoints
		app.post("/api/v1/project/load", projectController::load);
		app.get("/api/v1/project/info", projectController::info);

		// Tree endpoints (lazy: only returns one level at a time)
		app.get("/api/v1/tree", treeController::getTree);
		app.get("/api/v1/tree/children", treeController::getChildren);
		app.get("/api/v1/tree/packages", treeController::getPackages);

		// Code endpoints
		app.get("/api/v1/class/{fullName}/code", codeController::getCode);
		app.get("/api/v1/class/{fullName}/info", codeController::getInfo);

		// Resource endpoints
		app.get("/api/v1/resources", resourceController::getResources);
		app.get("/api/v1/resources/children", resourceController::getResourceChildren);
		app.get("/api/v1/resource/*", resourceController::getResource);

		// Search endpoint
		app.get("/api/v1/search", searchController::search);

		// References & navigation endpoints
		app.get("/api/v1/references", referencesController::findReferences);
		app.get("/api/v1/resolve-symbol", referencesController::resolveSymbol);
		app.get("/api/v1/goto-definition", referencesController::gotoDefinition);
	}

	public void broadcastEvent(String type, Object data) {
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		String json = gson.toJson(Map.of("type", type, "data", data));
		for (WsContext client : wsClients) {
			try {
				client.send(json);
			} catch (Exception e) {
				LOG.debug("Failed to send WS message", e);
				wsClients.remove(client);
			}
		}
	}

	public void stop() {
		if (app != null) {
			app.stop();
		}
		wrapper.close();
	}

	public static void main(String[] args) {
		try {
			JadxWebArgs webArgs = JadxWebArgs.parse(args);
			if (webArgs == null) {
				return;
			}
			JadxWebServer server = new JadxWebServer(webArgs);
			Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
			server.start();
		} catch (Exception e) {
			LOG.error("Failed to start jadx-web server", e);
			System.exit(1);
		}
	}
}
