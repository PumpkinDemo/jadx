package jadx.web.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.javalin.http.Context;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.JavaVariable;
import jadx.api.metadata.ICodeAnnotation;
import jadx.web.core.WebJadxWrapper;

public class ReferencesController {

	private final WebJadxWrapper wrapper;

	public ReferencesController(WebJadxWrapper wrapper) {
		this.wrapper = wrapper;
	}

	/**
	 * Find usages/references of a symbol.
	 * Query params:
	 * - className: fully qualified class name (required)
	 * - symbol: method or field name (optional; if omitted, finds usages of the class itself)
	 * - symbolType: "class", "method", or "field" (default: auto-detect)
	 */
	public void findReferences(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String className = ctx.queryParam("className");
		String symbol = ctx.queryParam("symbol");
		String symbolType = ctx.queryParam("symbolType");

		if (className == null || className.isEmpty()) {
			ctx.status(400).json(Map.of("error", "className is required"));
			return;
		}

		JavaClass cls = wrapper.findClass(className);
		if (cls == null) {
			ctx.status(404).json(Map.of("error", "Class not found: " + className));
			return;
		}

		// Ensure the class is decompiled so xref data is populated
		cls.getCode();

		JavaNode targetNode = resolveNode(cls, symbol, symbolType);
		if (targetNode == null) {
			ctx.status(404).json(Map.of("error", "Symbol not found: " + (symbol != null ? symbol : className)));
			return;
		}

		List<JavaNode> useIn = targetNode.getUseIn();
		List<Map<String, Object>> results = new ArrayList<>();

		for (JavaNode ref : useIn) {
			Map<String, Object> entry = new HashMap<>();
			JavaClass refClass = ref.getDeclaringClass();
			if (refClass == null) {
				refClass = (ref instanceof JavaClass) ? (JavaClass) ref : null;
			}
			if (refClass == null) {
				continue;
			}

			// Ensure referring class is decompiled
			String code = refClass.getCode();
			if (code == null) {
				continue;
			}

			String refName = ref.getName();
			int defPos = ref.getDefPos();
			int line = defPos > 0 ? posToLine(code, defPos) : 0;

			String lineText = "";
			if (line > 0) {
				lineText = getLineText(code, line);
			}

			entry.put("className", refClass.getFullName());
			entry.put("name", refName);
			entry.put("line", line);
			entry.put("lineText", lineText);
			entry.put("type", getNodeType(ref));
			results.add(entry);
		}

		Map<String, Object> response = new HashMap<>();
		response.put("symbol", targetNode.getFullName());
		response.put("symbolType", getNodeType(targetNode));
		response.put("count", results.size());
		response.put("references", results);
		ctx.json(response);
	}

	/**
	 * Resolve a symbol at a given code position using jadx code metadata.
	 * Falls back to name-based lookup if position info is unavailable.
	 *
	 * Query params:
	 * - word: the identifier under cursor (required)
	 * - contextClass: fully qualified name of the class being viewed (required)
	 * - line: 1-based line number in the decompiled code (optional)
	 * - column: 1-based column number (optional)
	 */
	public void resolveSymbol(Context ctx) {
		if (!wrapper.isLoaded()) {
			ctx.status(400).json(Map.of("error", "No project loaded"));
			return;
		}

		String word = ctx.queryParam("word");
		String contextClassName = ctx.queryParam("contextClass");
		String lineStr = ctx.queryParam("line");
		String colStr = ctx.queryParam("column");

		if (word == null || word.isEmpty()) {
			ctx.status(400).json(Map.of("error", "word is required"));
			return;
		}

		// Try metadata-based resolution first if we have position info
		if (contextClassName != null && lineStr != null && colStr != null) {
			JavaClass contextClass = wrapper.findClass(contextClassName);
			if (contextClass != null) {
				String code = contextClass.getCode();
				if (code != null) {
					int line = Integer.parseInt(lineStr);
					int col = Integer.parseInt(colStr);
					int offset = lineColToOffset(code, line, col);

					ICodeInfo codeInfo = contextClass.getCodeInfo();
					if (codeInfo != null && codeInfo.hasMetadata()) {
						// Try to resolve via code metadata at the cursor position
						JavaNode resolved = null;
						boolean isLocal = false;
						try {
							resolved = wrapper.getDecompiler().getJavaNodeAtPosition(codeInfo, offset);
							if (resolved == null) {
								// Try the exact start of the word (offset might be mid-word)
								resolved = wrapper.getDecompiler().getJavaNodeAtPosition(codeInfo, offset - 1);
							}
						} catch (Exception ignored) {
						}

						if (resolved instanceof JavaVariable) {
							isLocal = true;
							resolved = null;
						}

						// If high-level API didn't find it, check raw annotation for VAR/VAR_REF
						if (resolved == null && !isLocal) {
							ICodeAnnotation ann = codeInfo.getCodeMetadata().getAt(offset);
							if (ann != null) {
								ICodeAnnotation.AnnType t = ann.getAnnType();
								if (t == ICodeAnnotation.AnnType.VAR || t == ICodeAnnotation.AnnType.VAR_REF) {
									isLocal = true;
								}
							}
						}

						if (resolved instanceof JavaClass) {
							ctx.json(Map.of(
									"className", resolved.getFullName(),
									"symbolType", "class"));
							return;
						}
						if (resolved instanceof JavaMethod) {
							JavaClass declClass = resolved.getDeclaringClass();
							ctx.json(Map.of(
									"className", declClass.getFullName(),
									"symbol", resolved.getName(),
									"symbolType", "method"));
							return;
						}
						if (resolved instanceof JavaField) {
							JavaClass declClass = resolved.getDeclaringClass();
							ctx.json(Map.of(
									"className", declClass.getFullName(),
									"symbol", resolved.getName(),
									"symbolType", "field"));
							return;
						}
						// Metadata detected a local variable — but only trust it
						// if the word doesn't match any known class/method/field name
						if (isLocal && !isKnownSymbolName(word, contextClassName)) {
							ctx.status(400).json(Map.of(
									"error", "'" + word + "' is a local variable",
									"isLocal", true));
							return;
						}
					}
				}
			}
		}

	// Fallback: name-based resolution
	resolveByName(ctx, word, contextClassName);
}

/**
 * Go to definition: resolve the symbol at cursor and return its definition location.
 * Query params: word, contextClass, line, column (same as resolveSymbol)
 * Returns: { className, name, line }
 */
public void gotoDefinition(Context ctx) {
	if (!wrapper.isLoaded()) {
		ctx.status(400).json(Map.of("error", "No project loaded"));
		return;
	}

	String word = ctx.queryParam("word");
	String contextClassName = ctx.queryParam("contextClass");
	String lineStr = ctx.queryParam("line");
	String colStr = ctx.queryParam("column");

	if (word == null || word.isEmpty()) {
		ctx.status(400).json(Map.of("error", "word is required"));
		return;
	}

	JavaNode resolved = null;

	if (contextClassName != null && lineStr != null && colStr != null) {
		JavaClass contextClass = wrapper.findClass(contextClassName);
		if (contextClass != null) {
			String code = contextClass.getCode();
			if (code != null) {
				int line = Integer.parseInt(lineStr);
				int col = Integer.parseInt(colStr);
				int offset = lineColToOffset(code, line, col);

				ICodeInfo codeInfo = contextClass.getCodeInfo();
				if (codeInfo != null && codeInfo.hasMetadata()) {
					try {
						resolved = wrapper.getDecompiler().getJavaNodeAtPosition(codeInfo, offset);
						if (resolved == null) {
							resolved = wrapper.getDecompiler().getJavaNodeAtPosition(codeInfo, offset - 1);
						}
					} catch (Exception ignored) {
					}

					if (resolved instanceof JavaVariable) {
						ctx.status(400).json(Map.of("error", "Local variable", "isLocal", true));
						return;
					}
				}
			}
		}
	}

	if (resolved == null) {
		resolved = resolveByNameNode(word, contextClassName);
	}

	if (resolved == null) {
		ctx.status(404).json(Map.of("error", "Definition not found: " + word));
		return;
	}

	returnDefinitionLocation(ctx, resolved);
}

	private boolean isKnownSymbolName(String word, String contextClassName) {
		// Check class names
		for (JavaClass cls : wrapper.getDecompiler().getClassesWithInners()) {
			if (cls.getName().equals(word)) {
				return true;
			}
		}
		// Check members of context class
		if (contextClassName != null) {
			JavaClass ctx = wrapper.findClass(contextClassName);
			if (ctx != null) {
				for (JavaMethod m : ctx.getMethods()) {
					if (m.getName().equals(word)) {
						return true;
					}
				}
				for (JavaField f : ctx.getFields()) {
					if (f.getName().equals(word)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void returnDefinitionLocation(Context ctx, JavaNode node) {
		JavaClass targetClass;
		if (node instanceof JavaClass) {
			targetClass = (JavaClass) node;
		} else {
			targetClass = node.getDeclaringClass();
			if (targetClass == null) {
				ctx.status(404).json(Map.of("error", "Cannot determine declaring class"));
				return;
			}
		}

		String code = targetClass.getCode();
		int defLine = 1;
		if (code != null) {
			int defPos = node.getDefPos();
			if (defPos > 0) {
				defLine = posToLine(code, defPos);
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("className", targetClass.getFullName());
		result.put("name", targetClass.getName());
		result.put("line", defLine);
		ctx.json(result);
	}

	private JavaNode resolveByNameNode(String word, String contextClassName) {
		for (JavaClass cls : wrapper.getDecompiler().getClassesWithInners()) {
			if (cls.getName().equals(word)) {
				return cls;
			}
		}
		if (contextClassName != null && !contextClassName.isEmpty()) {
			JavaClass contextClass = wrapper.findClass(contextClassName);
			if (contextClass != null) {
				contextClass.getCode();
				for (JavaMethod m : contextClass.getMethods()) {
					if (m.getName().equals(word)) {
						return m;
					}
				}
				for (JavaField f : contextClass.getFields()) {
					if (f.getName().equals(word)) {
						return f;
					}
				}
				for (JavaClass inner : contextClass.getInnerClasses()) {
					if (inner.getName().equals(word)) {
						return inner;
					}
				}
			}
		}
		return null;
	}

	private void resolveByName(Context ctx, String word, String contextClassName) {
		// 1. Check if word is a known class simple name
		for (JavaClass cls : wrapper.getDecompiler().getClassesWithInners()) {
			if (cls.getName().equals(word)) {
				ctx.json(Map.of("className", cls.getFullName(), "symbolType", "class"));
				return;
			}
		}

		// 2. Check context class members
		if (contextClassName != null && !contextClassName.isEmpty()) {
			JavaClass contextClass = wrapper.findClass(contextClassName);
			if (contextClass != null) {
				contextClass.getCode();
				for (JavaMethod m : contextClass.getMethods()) {
					if (m.getName().equals(word)) {
						ctx.json(Map.of("className", contextClassName, "symbol", word, "symbolType", "method"));
						return;
					}
				}
				for (JavaField f : contextClass.getFields()) {
					if (f.getName().equals(word)) {
						ctx.json(Map.of("className", contextClassName, "symbol", word, "symbolType", "field"));
						return;
					}
				}
				for (JavaClass inner : contextClass.getInnerClasses()) {
					if (inner.getName().equals(word)) {
						ctx.json(Map.of("className", inner.getFullName(), "symbolType", "class"));
						return;
					}
				}
			}
		}

		ctx.status(404).json(Map.of("error", "Symbol not found: " + word));
	}

	private static int lineColToOffset(String code, int line, int col) {
		int currentLine = 1;
		for (int i = 0; i < code.length(); i++) {
			if (currentLine == line) {
				return Math.min(i + col - 1, code.length() - 1);
			}
			if (code.charAt(i) == '\n') {
				currentLine++;
			}
		}
		return code.length() - 1;
	}

	private JavaNode resolveNode(JavaClass cls, String symbol, String symbolType) {
		if (symbol == null || symbol.isEmpty()) {
			return cls;
		}

		if ("field".equals(symbolType)) {
			return findField(cls, symbol);
		}
		if ("method".equals(symbolType)) {
			return findMethod(cls, symbol);
		}

		// Auto-detect: try method first, then field, then inner class
		JavaNode node = findMethod(cls, symbol);
		if (node != null) {
			return node;
		}
		node = findField(cls, symbol);
		if (node != null) {
			return node;
		}
		for (JavaClass inner : cls.getInnerClasses()) {
			if (inner.getName().equals(symbol)) {
				return inner;
			}
		}
		return null;
	}

	private JavaMethod findMethod(JavaClass cls, String name) {
		for (JavaMethod m : cls.getMethods()) {
			if (m.getName().equals(name)) {
				return m;
			}
		}
		return null;
	}

	private JavaField findField(JavaClass cls, String name) {
		for (JavaField f : cls.getFields()) {
			if (f.getName().equals(name)) {
				return f;
			}
		}
		return null;
	}

	private static String getNodeType(JavaNode node) {
		if (node instanceof JavaClass) {
			return "class";
		}
		if (node instanceof JavaMethod) {
			return "method";
		}
		if (node instanceof JavaField) {
			return "field";
		}
		return "unknown";
	}

	private static int posToLine(String code, int pos) {
		if (pos <= 0 || pos >= code.length()) {
			return 1;
		}
		int line = 1;
		for (int i = 0; i < pos; i++) {
			if (code.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static String getLineText(String code, int lineNum) {
		int currentLine = 1;
		int start = 0;
		for (int i = 0; i < code.length(); i++) {
			if (currentLine == lineNum) {
				start = i;
				int end = code.indexOf('\n', i);
				if (end == -1) {
					end = code.length();
				}
				String text = code.substring(start, end).trim();
				return text.length() > 200 ? text.substring(0, 200) + "..." : text;
			}
			if (code.charAt(i) == '\n') {
				currentLine++;
			}
		}
		return "";
	}
}
