import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

repositories {
	mavenCentral()
	maven {
		name = "babbaj"
		url = uri("https://babbaj.github.io/maven/")
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("realwayfinder") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

// Baritone 26.2 sources, commit 9fadf7cf. Compiled with this mod so mappings match.
// See third_party/BARITONE.txt and third_party/baritone/LICENSE (LGPL-3.0).
val baritoneRoot = layout.projectDirectory.dir("third_party/baritone")
sourceSets.named("client") {
	java {
		srcDir(baritoneRoot.dir("src/api/java"))
		srcDir(baritoneRoot.dir("src/main/java"))
		srcDir(baritoneRoot.dir("src/launch/java"))
		// Compile-only stubs. The real Schematica or Litematica mod supplies these at runtime.
		srcDir(baritoneRoot.dir("src/schematica_api/java"))
	}
	resources {
		srcDir(baritoneRoot.dir("src/launch/resources"))
		exclude("META-INF/MANIFEST.MF")
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

	// SpotFilter is a compile dependency. GitHub Actions has no E: drive, so the workflow
	// checks out KClgame/SpotFilter 1.9.8 (bfdac6fe) into spotfilter/ and builds that jar.
	// This source does not reference KCl Utils. That jar is added only when it exists.
	val compileMods = mutableListOf<File>()
	fun addModJars(dir: File) {
		if (!dir.isDirectory) return
		dir.listFiles()?.filter { jar ->
			jar.isFile && jar.name.endsWith(".jar") &&
				!jar.name.endsWith("-sources.jar") &&
				!jar.name.contains("-mc")
		}?.let { compileMods.addAll(it) }
	}
	addModJars(file("libs"))
	addModJars(file("spotfilter/build/libs"))
	listOf(
		"E:/Mods/spotfilter/spotfilter-template-26.2/build/libs/spotfilter-1.9.8.jar",
		"E:/Mods/KClutils/kcl-utils-template-26.2/build/libs/kcl-utils-1.1.0.jar",
	).map { file(it) }.filter { it.isFile }.let { compileMods.addAll(it) }
	check(compileMods.any { it.name.startsWith("spotfilter-") }) {
		"SpotFilter jar is missing. Put spotfilter-1.9.8.jar in libs/, or build https://github.com/KClgame/SpotFilter at bfdac6fe into spotfilter/build/libs."
	}
	implementation(files(compileMods.distinctBy { it.absolutePath }))

	// Baritone elytra pathing. Nested so ElytraProcess can load. We do not call it.
	val netherPathfinder = "dev.babbaj:nether-pathfinder:1.6"
	implementation(netherPathfinder)
	include(netherPathfinder)
	compileOnly("com.google.code.findbugs:jsr305:3.0.2")

	testImplementation(kotlin("test"))
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("third_party/baritone/LICENSE") {
		rename { "LICENSE_baritone_LGPL-3.0" }
	}
	exclude("com/github/lunatrius/**")
	exclude("fi/dy/masa/**")
	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
