package jadx.web.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.javalin.http.Context;

import jadx.api.ResourceFile;
import jadx.api.ResourcesLoader;
import jadx.core.utils.exceptions.JadxException;
import jadx.web.core.WebJadxWrapper;

public class ResourceController {

	private final WebJadxWrapper wrapper;

	public ResourceController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	/**
	 * Returns resource tree as a list of root nodes (folders/files).
	 * Builds a folder hierarchy from flat resource paths.
	 */
	public void getResources(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		// Build a virtual folder tree from resource paths
		FolderNode root = new FolderNode("");
		for (ResourceFile res : wrapper.getResources()) {
			String path = res.getDeobfName();
			String[] parts = path.split("/");
			FolderNode current = root;
			for (int i = 0; i < parts.length - 1; i++) {
				current = current.getOrCreateChild(parts[i]);
			}
			// leaf = file
			current.files.add(new FileEntry(parts[parts.length - 1], path, res.getType().name()));
		}

		List<Map<String, Object>> tree = buildFolderChildren(root);
		ctx.json(tree);
	}

	/**
	 * Returns children of a resource folder node.
	 * path param = folder path, e.g. "res/layout"
	 */
	public void getResourceChildren(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String folderPath = ctx.queryParam("path");
		if (folderPath == null) {
			folderPath = "";
		}

		// Build the tree and navigate to the requested folder
		FolderNode root = new FolderNode("");
		for (ResourceFile res : wrapper.getResources()) {
			String path = res.getDeobfName();
			String[] parts = path.split("/");
			FolderNode current = root;
			for (int i = 0; i < parts.length - 1; i++) {
				current = current.getOrCreateChild(parts[i]);
			}
			current.files.add(new FileEntry(parts[parts.length - 1], path, res.getType().name()));
		}

		// Navigate to the requested folder
		FolderNode target = root;
		if (!folderPath.isEmpty()) {
			for (String part : folderPath.split("/")) {
				target = target.children.get(part);
				if (target == null) {
					ctx.status(404).json(Map.of("error", "Folder not found: " + folderPath));
					return;
				}
			}
		}

		ctx.json(buildFolderChildren(target));
	}

	/**
	 * Returns content of a single resource file.
	 */
	public void getResource(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String path = ctx.path().substring("/api/v1/resource/".length());

		for (ResourceFile res : wrapper.getResources()) {
			if (res.getDeobfName().equals(path) || res.getOriginalName().equals(path)) {
				try {
					String content = ResourcesLoader.decodeStream(res, (size, is) -> {
						byte[] bytes = is.readAllBytes();
						return new String(bytes);
					});
					if (content != null) {
						Map<String, Object> result = new HashMap<>();
						result.put("name", res.getDeobfName());
						result.put("type", res.getType().name());
						result.put("content", content);
						ctx.json(result);
						return;
					}
				} catch (JadxException e) {
					ctx.status(500).json(Map.of("error", "Failed to load resource: " + e.getMessage()));
					return;
				}
			}
		}
		ctx.status(404).json(Map.of("error", "Resource not found: " + path));
	}

	// --- Build shallow children for a folder node ---

	private List<Map<String, Object>> buildFolderChildren(FolderNode folder) {
		List<Map<String, Object>> result = new ArrayList<>();

		// Sub-folders first
		for (FolderNode sub : folder.children.values()) {
			Map<String, Object> node = new HashMap<>();
			node.put("id", "res-dir:" + sub.fullPath());
			node.put("name", sub.name);
			node.put("fullName", sub.fullPath());
			node.put("type", "folder");
			node.put("hasChildren", !sub.children.isEmpty() || !sub.files.isEmpty());
			result.add(node);
		}

		// Files
		for (FileEntry file : folder.files) {
			Map<String, Object> node = new HashMap<>();
			node.put("id", "res:" + file.path);
			node.put("name", file.name);
			node.put("fullName", file.path);
			node.put("type", "resource");
			node.put("resType", file.resType);
			node.put("hasChildren", false);
			result.add(node);
		}

		return result;
	}

	// --- Internal tree structure for building resource hierarchy ---

	private static class FolderNode {
		final String name;
		final FolderNode parent;
		final LinkedHashMap<String, FolderNode> children = new LinkedHashMap<>();
		final List<FileEntry> files = new ArrayList<>();

		FolderNode(String name) {
			this(name, null);
		}

		FolderNode(String name, FolderNode parent) {
			this.name = name;
			this.parent = parent;
		}

		FolderNode getOrCreateChild(String childName) {
			return children.computeIfAbsent(childName, k -> new FolderNode(k, this));
		}

		String fullPath() {
			if (parent == null || parent.name.isEmpty()) {
				return name;
			}
			return parent.fullPath() + "/" + name;
		}
	}

	private static class FileEntry {
		final String name;
		final String path;
		final String resType;

		FileEntry(String name, String path, String resType) {
			this.name = name;
			this.path = path;
			this.resType = resType;
		}
	}
}
