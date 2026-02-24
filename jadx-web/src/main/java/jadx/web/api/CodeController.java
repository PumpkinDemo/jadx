package jadx.web.api;

import java.util.HashMap;
import java.util.Map;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.web.core.WebJadxWrapper;

public class CodeController {

	private final WebJadxWrapper wrapper;

	public CodeController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	public void getCode(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String fullName = ctx.pathParam("fullName");
		String code = wrapper.getClassCode(fullName);
		if (code == null) {
			ctx.status(404).json(Map.of("error", "Class not found: " + fullName));
			return;
		}

		Map<String, Object> result = new HashMap<>();
		result.put("className", fullName);
		result.put("code", code);
		ctx.json(result);
	}

	public void getInfo(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String fullName = ctx.pathParam("fullName");
		JavaClass cls = wrapper.findClass(fullName);
		if (cls == null) {
			ctx.status(404).json(Map.of("error", "Class not found: " + fullName));
			return;
		}

		Map<String, Object> info = new HashMap<>();
		info.put("fullName", cls.getFullName());
		info.put("name", cls.getName());
		info.put("packageName", cls.getPackage());
		info.put("isEnum", cls.getAccessInfo().isEnum());
		info.put("isInterface", cls.getAccessInfo().isInterface());
		info.put("accessFlags", cls.getAccessInfo().toString());
		info.put("methodCount", cls.getMethods().size());
		info.put("fieldCount", cls.getFields().size());
		info.put("innerClassCount", cls.getInnerClasses().size());

		ctx.json(info);
	}
}
