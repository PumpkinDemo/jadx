package jadx.web.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.javalin.http.Context;

import jadx.web.core.WebJadxWrapper;

public class SearchController {

	private final WebJadxWrapper wrapper;

	public SearchController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	public void search(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String query = ctx.queryParam("query");
		String type = ctx.queryParamAsClass("type", String.class).getOrDefault("code");
		int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);

		if (query == null || query.trim().isEmpty()) {
			ctx.status(400).json(Map.of("error", "Query parameter is required"));
			return;
		}

		List<WebJadxWrapper.SearchResult> results = wrapper.search(query, type, limit);

		Map<String, Object> response = new HashMap<>();
		response.put("query", query);
		response.put("type", type);
		response.put("count", results.size());
		response.put("results", results);
		ctx.json(response);
	}
}
