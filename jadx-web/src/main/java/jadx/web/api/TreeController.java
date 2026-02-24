package jadx.web.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaPackage;
import jadx.web.core.WebJadxWrapper;

/**
 * Lazy tree API: each request returns only one level of children.
 * - GET /api/v1/tree              → top-level packages (roots)
 * - GET /api/v1/tree/children?id= → children of a given node id
 */
public class TreeController {

	private final WebJadxWrapper wrapper;

	public TreeController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	/**
	 * Returns only the top-level (root) packages as shallow nodes.
	 */
	public void getTree(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		List<Map<String, Object>> roots = new ArrayList<>();
		for (JavaPackage pkg : wrapper.getPackages()) {
			roots.add(shallowPackageNode(pkg));
		}
		ctx.json(roots);
	}

	/**
	 * Returns the direct children of a node identified by its id.
	 * <p>
	 * id format:
	 * - "pkg:com.example"    → children of package com.example
	 * - "cls:com.example.Foo" → members (fields, methods, inner classes) of class Foo
	 */
	public void getChildren(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String nodeId = ctx.queryParam("id");
		if (nodeId == null || nodeId.isEmpty()) {
			ctx.status(400).json(Map.of("error", "Missing 'id' parameter"));
			return;
		}

		int colon = nodeId.indexOf(':');
		if (colon < 0) {
			ctx.status(400).json(Map.of("error", "Invalid id format"));
			return;
		}
		String kind = nodeId.substring(0, colon);
		String fullName = nodeId.substring(colon + 1);

		List<Map<String, Object>> children;
		switch (kind) {
			case "pkg":
				children = getPackageChildren(fullName);
				break;
			case "cls":
				children = getClassMembers(fullName);
				break;
			default:
				children = List.of();
				break;
		}

		if (children == null) {
			ctx.status(404).json(Map.of("error", "Node not found: " + nodeId));
			return;
		}
		ctx.json(children);
	}

	/**
	 * Flat list of packages (kept for backward compat).
	 */
	public void getPackages(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		List<Map<String, Object>> packages = new ArrayList<>();
		for (JavaPackage pkg : wrapper.getPackages()) {
			Map<String, Object> node = new HashMap<>();
			node.put("name", pkg.getName());
			node.put("fullName", pkg.getFullName());
			node.put("classCount", pkg.getClasses().size());
			packages.add(node);
		}
		ctx.json(packages);
	}

	// ---- helpers: build shallow (one-level) nodes ----

	private List<Map<String, Object>> getPackageChildren(String pkgFullName) {
		JavaPackage pkg = findPackage(pkgFullName, wrapper.getPackages());
		if (pkg == null) {
			return null;
		}
		List<Map<String, Object>> children = new ArrayList<>();
		for (JavaPackage sub : pkg.getSubPackages()) {
			children.add(shallowPackageNode(sub));
		}
		for (JavaClass cls : pkg.getClassesNoDup()) {
			children.add(shallowClassNode(cls));
		}
		return children;
	}

	private List<Map<String, Object>> getClassMembers(String clsFullName) {
		JavaClass cls = wrapper.findClass(clsFullName);
		if (cls == null) {
			return null;
		}
		List<Map<String, Object>> members = new ArrayList<>();
		for (JavaField field : cls.getFields()) {
			Map<String, Object> n = new HashMap<>();
			n.put("id", "fld:" + cls.getFullName() + "." + field.getName());
			n.put("name", field.getName());
			n.put("fullName", cls.getFullName() + "." + field.getName());
			n.put("type", "field");
			n.put("hasChildren", false);
			members.add(n);
		}
		for (JavaMethod method : cls.getMethods()) {
			Map<String, Object> n = new HashMap<>();
			n.put("id", "mth:" + cls.getFullName() + "." + method.getName());
			n.put("name", method.getName());
			n.put("fullName", cls.getFullName() + "." + method.getName());
			n.put("type", "method");
			n.put("hasChildren", false);
			members.add(n);
		}
		for (JavaClass inner : cls.getInnerClasses()) {
			members.add(shallowClassNode(inner));
		}
		return members;
	}

	private Map<String, Object> shallowPackageNode(JavaPackage pkg) {
		Map<String, Object> node = new HashMap<>();
		node.put("id", "pkg:" + pkg.getFullName());
		node.put("name", pkg.getName());
		node.put("fullName", pkg.getFullName());
		node.put("type", "package");
		node.put("hasChildren", !pkg.getSubPackages().isEmpty() || !pkg.getClassesNoDup().isEmpty());
		return node;
	}

	private Map<String, Object> shallowClassNode(JavaClass cls) {
		Map<String, Object> node = new HashMap<>();
		node.put("id", "cls:" + cls.getFullName());
		node.put("name", cls.getName());
		node.put("fullName", cls.getFullName());
		node.put("type", cls.getAccessInfo().isEnum() ? "enum" : (cls.getAccessInfo().isInterface() ? "interface" : "class"));
		boolean hasChildren = !cls.getMethods().isEmpty() || !cls.getFields().isEmpty() || !cls.getInnerClasses().isEmpty();
		node.put("hasChildren", hasChildren);
		return node;
	}

	private JavaPackage findPackage(String fullName, List<JavaPackage> packages) {
		for (JavaPackage pkg : packages) {
			if (pkg.getFullName().equals(fullName)) {
				return pkg;
			}
			JavaPackage found = findPackage(fullName, pkg.getSubPackages());
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
