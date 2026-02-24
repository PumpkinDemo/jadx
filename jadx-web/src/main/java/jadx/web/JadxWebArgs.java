package jadx.web;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class JadxWebArgs {
	private static final Logger LOG = LoggerFactory.getLogger(JadxWebArgs.class);

	@Parameter(description = "<input files> (.apk, .dex, .jar, .class, .smali, .zip, .aar, .arsc, .aab, .xapk, .apkm, .apks)")
	private List<String> inputFiles = new ArrayList<>();

	@Parameter(names = { "-p", "--port" }, description = "Server port")
	private int port = 8080;

	@Parameter(names = { "--host" }, description = "Server host")
	private String host = "0.0.0.0";

	@Parameter(names = { "-h", "--help" }, description = "Print this help", help = true)
	private boolean help = false;

	public static @Nullable JadxWebArgs parse(String[] args) {
		JadxWebArgs webArgs = new JadxWebArgs();
		JCommander jc = JCommander.newBuilder()
				.addObject(webArgs)
				.programName("jadx-web")
				.build();
		try {
			jc.parse(args);
		} catch (ParameterException e) {
			LOG.error("Arguments parse error: {}", e.getMessage());
			jc.usage();
			return null;
		}
		if (webArgs.help) {
			jc.usage();
			return null;
		}
		return webArgs;
	}

	public List<File> getInputFiles() {
		List<File> files = new ArrayList<>(inputFiles.size());
		for (String path : inputFiles) {
			File file = new File(path);
			if (file.exists()) {
				files.add(file);
			} else {
				LOG.warn("Input file not found: {}", path);
			}
		}
		return files;
	}

	public int getPort() {
		return port;
	}

	public String getHost() {
		return host;
	}
}
