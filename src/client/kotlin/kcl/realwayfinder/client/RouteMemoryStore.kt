package kcl.realwayfinder.client

import kcl.realwayfinder.nav.RouteMemory
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object RouteMemoryStore {
	private val path = FabricLoader.getInstance().configDir.resolve("realwayfinder").resolve("routes.txt")

	fun load() {
		if (!Files.exists(path)) return
		RouteMemory.load(Files.readAllLines(path, StandardCharsets.UTF_8))
	}

	fun save() {
		Files.createDirectories(path.parent)
		Files.write(path, RouteMemory.export(), StandardCharsets.UTF_8)
	}
}
