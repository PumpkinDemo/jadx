package jadx.web.api;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.http.Context;

import jadx.core.Jadx;
import jadx.web.core.WebJadxWrapper;

public class ProjectController {
	private static final Logger LOG = LoggerFactory.getLogger(ProjectController.class);

	private final WebJadxWrapper wrapper;

	public ProjectController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	public void load(Context ctx) {
		try {
			var uploadedFile = ctx.uploadedFile("file");
			if (uploadedFile == null) {
				ctx.status(400).json(errorMap("No file uploaded"));
				return;
			}

			// Save uploaded file to temp directory
			Path tempDir = Files.createTempDirectory("jadx-web-");
			Path tempFile = tempDir.resolve(uploadedFile.filename());
			Files.copy(uploadedFile.content(), tempFile);

			wrapper.load(Collections.singletonList(tempFile.toFile()));

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("fileName", uploadedFile.filename());
			result.put("classCount", wrapper.getClassCount());
			ctx.json(result);
		} catch (Exception e) {
			LOG.error("Failed to load uploaded file", e);
			ctx.status(500).json(errorMap("Failed to load file: " + e.getMessage()));
		}
	}

	public void info(Context ctx) {
		Map<String, Object> info = new HashMap<>();
		info.put("version", Jadx.getVersion());
		info.put("loaded", wrapper.isLoaded());
		info.put("loading", wrapper.isLoading());

		if (wrapper.isLoaded()) {
			info.put("fileName", wrapper.getLoadedFileName());
			info.put("classCount", wrapper.getClassCount());
			info.put("errorsCount", wrapper.getErrorsCount());
		}
		ctx.json(info);
	}

	private Map<String, String> errorMap(String message) {
		Map<String, String> map = new HashMap<>();
		map.put("error", message);
		return map;
	}
}
