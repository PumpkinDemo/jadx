package jadx.web.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaPackage;
import jadx.api.ResourceFile;
import jadx.api.impl.InMemoryCodeCache;
import jadx.cli.JadxAppCommon;
import jadx.cli.plugins.JadxFilesGetter;
import jadx.plugins.tools.JadxExternalPluginsLoader;

public class WebJadxWrapper {
	private static final Logger LOG = LoggerFactory.getLogger(WebJadxWrapper.class);

	private volatile @Nullable JadxDecompiler decompiler;
	private volatile boolean loading;
	private volatile @Nullable String loadedFileName;

	public synchronized void load(List<File> inputFiles) {
		close();
		loading = true;
		try {
			LOG.info("Loading files: {}", inputFiles);
			JadxArgs args = new JadxArgs();
			args.setInputFiles(inputFiles);
			args.setCodeCache(new InMemoryCodeCache());
			args.setPluginLoader(new JadxExternalPluginsLoader());
			args.setFilesGetter(JadxFilesGetter.INSTANCE);
			JadxAppCommon.applyEnvVars(args);

			JadxDecompiler jadx = new JadxDecompiler(args);
			jadx.load();

			this.decompiler = jadx;
			this.loadedFileName = inputFiles.stream()
					.map(File::getName)
					.reduce((a, b) -> a + ", " + b)
					.orElse("unknown");

			LOG.info("Loaded successfully. Classes: {}, Packages: {}, Resources: {}",
					jadx.getClasses().size(),
					jadx.getPackages().size(),
					jadx.getResources().size());
		} catch (Exception e) {
			LOG.error("Failed to load files", e);
			throw new RuntimeException("Failed to load: " + e.getMessage(), e);
		} finally {
			loading = false;
		}
	}

	public synchronized void close() {
		if (decompiler != null) {
			try {
				decompiler.close();
			} catch (Exception e) {
				LOG.error("Error closing decompiler", e);
			}
			decompiler = null;
			loadedFileName = null;
		}
	}

	public boolean isLoaded() {
		return decompiler != null;
	}

	public boolean isLoading() {
		return loading;
	}

	public @Nullable String getLoadedFileName() {
		return loadedFileName;
	}

	public JadxDecompiler getDecompiler() {
		JadxDecompiler d = decompiler;
		if (d == null) {
			throw new IllegalStateException("No project loaded");
		}
		return d;
	}

	public List<JavaClass> getClasses() {
		return getDecompiler().getClasses();
	}

	public List<JavaPackage> getPackages() {
		return getDecompiler().getPackages();
	}

	public List<ResourceFile> getResources() {
		return getDecompiler().getResources();
	}

	public @Nullable JavaClass findClass(String fullName) {
		for (JavaClass cls : getDecompiler().getClasses()) {
			if (cls.getFullName().equals(fullName)) {
				return cls;
			}
		}
		// Also search inner classes
		for (JavaClass cls : getDecompiler().getClassesWithInners()) {
			if (cls.getFullName().equals(fullName)) {
				return cls;
			}
		}
		return null;
	}

	public String getClassCode(String fullName) {
		JavaClass cls = findClass(fullName);
		if (cls == null) {
			return null;
		}
		return cls.getCode();
	}

	public int getClassCount() {
		if (!isLoaded()) {
			return 0;
		}
		return getDecompiler().getClasses().size();
	}

	public int getErrorsCount() {
		if (!isLoaded()) {
			return 0;
		}
		return getDecompiler().getErrorsCount();
	}

	public List<SearchResult> search(String query, String type, int limit) {
		if (!isLoaded() || query == null || query.isEmpty()) {
			return Collections.emptyList();
		}
		String lowerQuery = query.toLowerCase();
		List<SearchResult> results = new ArrayList<>();

		for (JavaClass cls : getDecompiler().getClassesWithInners()) {
			if (results.size() >= limit) {
				break;
			}
			switch (type) {
				case "class":
					if (cls.getFullName().toLowerCase().contains(lowerQuery)) {
						results.add(new SearchResult(cls.getFullName(), cls.getName(), "class", 0, cls.getFullName()));
					}
					break;
				case "method":
					for (JavaMethod method : cls.getMethods()) {
						if (results.size() >= limit) {
							break;
						}
						if (method.getName().toLowerCase().contains(lowerQuery)) {
							results.add(new SearchResult(
									cls.getFullName(), method.getName(), "method",
									0, cls.getFullName() + "." + method.getName()));
						}
					}
					break;
				case "field":
					for (JavaField field : cls.getFields()) {
						if (results.size() >= limit) {
							break;
						}
						if (field.getName().toLowerCase().contains(lowerQuery)) {
							results.add(new SearchResult(
									cls.getFullName(), field.getName(), "field",
									0, cls.getFullName() + "." + field.getName()));
						}
					}
					break;
				case "code":
				default:
					searchInCode(cls, lowerQuery, results, limit);
					break;
			}
		}
		return results;
	}

	private void searchInCode(JavaClass cls, String lowerQuery, List<SearchResult> results, int limit) {
		try {
			String code = cls.getCode();
			if (code == null) {
				return;
			}
			String lowerCode = code.toLowerCase();
			int idx = 0;
			while (idx < lowerCode.length() && results.size() < limit) {
				int found = lowerCode.indexOf(lowerQuery, idx);
				if (found == -1) {
					break;
				}
				int line = countLines(code, found);
				// Extract the matching line for context
				int lineStart = code.lastIndexOf('\n', found);
				int lineEnd = code.indexOf('\n', found);
				if (lineStart == -1) {
					lineStart = 0;
				}
				if (lineEnd == -1) {
					lineEnd = code.length();
				}
				String lineText = code.substring(lineStart, lineEnd).trim();
				if (lineText.length() > 200) {
					lineText = lineText.substring(0, 200) + "...";
				}
				results.add(new SearchResult(cls.getFullName(), lineText, "code", line, cls.getFullName()));
				idx = found + lowerQuery.length();
			}
		} catch (Exception e) {
			LOG.debug("Error searching in class {}: {}", cls.getFullName(), e.getMessage());
		}
	}

	private int countLines(String text, int upToIndex) {
		int count = 1;
		for (int i = 0; i < upToIndex && i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				count++;
			}
		}
		return count;
	}

	public static class SearchResult {
		public final String className;
		public final String match;
		public final String type;
		public final int line;
		public final String location;

		public SearchResult(String className, String match, String type, int line, String location) {
			this.className = className;
			this.match = match;
			this.type = type;
			this.line = line;
			this.location = location;
		}
	}
}
