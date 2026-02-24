plugins {
	id("jadx-java")
	id("jadx-library")
	id("application")

	id("com.gradleup.shadow") version "8.3.8"
}

dependencies {
	implementation(project(":jadx-core"))
	implementation(project(":jadx-cli"))
	implementation(project(":jadx-plugins-tools"))
	implementation(project(":jadx-commons:jadx-app-commons"))

	runtimeOnly(project(":jadx-plugins:jadx-dex-input"))
	runtimeOnly(project(":jadx-plugins:jadx-java-input"))
	runtimeOnly(project(":jadx-plugins:jadx-java-convert"))
	runtimeOnly(project(":jadx-plugins:jadx-smali-input"))
	runtimeOnly(project(":jadx-plugins:jadx-rename-mappings"))
	runtimeOnly(project(":jadx-plugins:jadx-kotlin-metadata"))
	runtimeOnly(project(":jadx-plugins:jadx-kotlin-source-debug-extension"))
	runtimeOnly(project(":jadx-plugins:jadx-script:jadx-script-plugin"))
	runtimeOnly(project(":jadx-plugins:jadx-xapk-input"))
	runtimeOnly(project(":jadx-plugins:jadx-aab-input"))
	runtimeOnly(project(":jadx-plugins:jadx-apkm-input"))
	runtimeOnly(project(":jadx-plugins:jadx-apks-input"))

	implementation("io.javalin:javalin:6.7.0")
	implementation("org.jcommander:jcommander:2.0")
	implementation("ch.qos.logback:logback-classic:1.5.22")
	implementation("com.google.code.gson:gson:2.13.2")
}

val jadxVersion: String by rootProject.extra

application {
	applicationName = "jadx-web"
	mainClass.set("jadx.web.JadxWebServer")
	applicationDefaultJvmArgs =
		listOf(
			"-XX:+IgnoreUnrecognizedVMOptions",
			"-Xms256M",
			"-XX:MaxRAMPercentage=70.0",
			"-XX:ParallelGCThreads=3",
			"-Djdk.util.zip.disableZip64ExtraFieldValidation=true",
			"--enable-native-access=ALL-UNNAMED",
		)
	applicationDistribution.from("$rootDir") {
		include("README.md")
		include("NOTICE")
		include("LICENSE")
	}
}

// Build frontend and include in resources
val frontendDir = project.file("frontend")

val npmInstall by tasks.registering(Exec::class) {
	group = "frontend"
	description = "Install frontend dependencies"
	workingDir = frontendDir
	commandLine("npm", "install")
	inputs.file(frontendDir.resolve("package.json"))
	outputs.dir(frontendDir.resolve("node_modules"))
	onlyIf { frontendDir.resolve("package.json").exists() }
}

val npmBuild by tasks.registering(Exec::class) {
	group = "frontend"
	description = "Build frontend for production"
	dependsOn(npmInstall)
	workingDir = frontendDir
	commandLine("npm", "run", "build")
	inputs.dir(frontendDir.resolve("src"))
	inputs.file(frontendDir.resolve("package.json"))
	inputs.file(frontendDir.resolve("vite.config.ts"))
	inputs.file(frontendDir.resolve("index.html"))
	outputs.dir(frontendDir.resolve("dist"))
	onlyIf { frontendDir.resolve("package.json").exists() }
}

tasks.processResources {
	dependsOn(npmBuild)
	from(frontendDir.resolve("dist")) {
		into("web")
	}
}

tasks.shadowJar {
	isZip64 = true
	mergeServiceFiles()
	manifest {
		from(tasks.jar.get().manifest)
	}
	// include frontend resources in shadow jar
	dependsOn(npmBuild)
}

tasks.jar {
	manifest {
		attributes(mapOf("Main-Class" to application.mainClass.get()))
	}
}

tasks.startShadowScripts {
	applicationName = "jadx-web"
}
