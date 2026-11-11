package kcl.realwayfinder.client

import kcl.realwayfinder.nav.FishTarget
import kcl.realwayfinder.nav.GrottoKind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SetupScreen : Screen(Component.literal("Realwayfinder")) {
	override fun isPauseScreen(): Boolean = false

	override fun init() {
		addRenderableWidget(toggle(8, 32, 120, if (RouteSettings.grottoEnabled) "Grotto 开" else "Grotto 关") {
			RouteSettings.grottoEnabled = !RouteSettings.grottoEnabled
			RouteSettings.save()
			rebuildWidgets()
		})
		addRenderableWidget(textButton(136, 32, 52, "行动") { minecraft.gui.setScreen(MoveScreen(this)) })
		addRenderableWidget(textButton(192, 32, 52, "按键") { minecraft.gui.setScreen(KeyScreen(this)) })
		addRenderableWidget(textButton(248, 32, 52, "HUD") { minecraft.gui.setScreen(HudEditScreen(this)) })

		RouteSettings.islandOrder.forEachIndexed { index, id ->
			val y = 78 + index * 18
			addRenderableWidget(textButton(8, y, 16, "↑") { moveIsland(index, -1) })
			addRenderableWidget(textButton(26, y, 16, "↓") { moveIsland(index, 1) })
			addRenderableWidget(toggle(44, y, 118, islandLabel(id)) {
				if (id in RouteSettings.islands) RouteSettings.islands.remove(id) else RouteSettings.islands.add(id)
				RouteSettings.save()
				rebuildWidgets()
			})
		}
		val fish = RouteSettings.fishOrder
		fish.forEachIndexed { index, target ->
			val y = 78 + index * 22
			addRenderableWidget(textButton(176, y, 18, "↑") { moveFish(index, -1) })
			addRenderableWidget(textButton(196, y, 18, "↓") { moveFish(index, 1) })
			addRenderableWidget(toggle(216, y, 100, fishLabel(index, target)) {
				if (target in RouteSettings.fishTargets) RouteSettings.fishTargets.remove(target) else RouteSettings.fishTargets.add(target)
				RouteSettings.save()
				rebuildWidgets()
			})
			addRenderableWidget(configButton(320, y) { openFish(target) })
		}
		val grotto = RouteSettings.grottoOrder
		grotto.forEachIndexed { index, kind ->
			val y = 78 + index * 22
			addRenderableWidget(textButton(372, y, 18, "↑") { moveGrotto(index, -1) })
			addRenderableWidget(textButton(392, y, 18, "↓") { moveGrotto(index, 1) })
			addRenderableWidget(toggle(412, y, 100, grottoLabel(index, kind)) {
				if (kind in RouteSettings.grottoTargets) RouteSettings.grottoTargets.remove(kind) else RouteSettings.grottoTargets.add(kind)
				RouteSettings.save()
				rebuildWidgets()
			})
			addRenderableWidget(configButton(516, y) { openGrotto(kind) })
		}
	}

	private fun openFish(target: FishTarget) {
		val triple = OverclockFile.fish(target)
		minecraft.gui.setScreen(OverclockPage(this, fishName(target), target in RouteSettings.fishTargets, triple, {
			if (target in RouteSettings.fishTargets) RouteSettings.fishTargets.remove(target) else RouteSettings.fishTargets.add(target)
			RouteSettings.save()
			openFish(target)
		}) { next ->
			OverclockFile.setFish(target, next)
			openFish(target)
		})
	}

	private fun openGrotto(kind: GrottoKind) {
		val triple = OverclockFile.grotto(kind)
		minecraft.gui.setScreen(OverclockPage(this, "Grotto ${grottoName(kind)}", kind in RouteSettings.grottoTargets, triple, {
			if (kind in RouteSettings.grottoTargets) RouteSettings.grottoTargets.remove(kind) else RouteSettings.grottoTargets.add(kind)
			RouteSettings.save()
			openGrotto(kind)
		}) { next ->
			OverclockFile.setGrotto(kind, next)
			openGrotto(kind)
		})
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		graphics.fill(4, 52, 168, 76, 0x88000000.toInt())
		graphics.fill(172, 52, 370, 76, 0x88000000.toInt())
		graphics.fill(374, 52, 566, 76, 0x88000000.toInt())
		graphics.text(font, grottoScanned(), 430, 38, 0xFFD0D0D0.toInt(), false)
		graphics.text(font, "岛屿顺序", 8, 60, 0xFFD0D0D0.toInt(), false)
		graphics.text(font, "钓鱼优先级", 216, 60, 0xFFD0D0D0.toInt(), false)
		graphics.text(font, "Grotto 种类", 412, 60, 0xFFD0D0D0.toInt(), false)
	}

	private fun moveIsland(index: Int, delta: Int) {
		val next = index + delta
		if (next !in RouteSettings.islandOrder.indices) return
		val item = RouteSettings.islandOrder.removeAt(index)
		RouteSettings.islandOrder.add(next, item)
		RouteSettings.save()
		rebuildWidgets()
	}

	private fun moveFish(index: Int, delta: Int) {
		val next = index + delta
		if (next !in RouteSettings.fishOrder.indices) return
		val item = RouteSettings.fishOrder.removeAt(index)
		RouteSettings.fishOrder.add(next, item)
		RouteSettings.save()
		rebuildWidgets()
	}

	private fun moveGrotto(index: Int, delta: Int) {
		val next = index + delta
		if (next !in RouteSettings.grottoOrder.indices) return
		val item = RouteSettings.grottoOrder.removeAt(index)
		RouteSettings.grottoOrder.add(next, item)
		RouteSettings.save()
		rebuildWidgets()
	}

	private fun toggle(x: Int, y: Int, width: Int, label: String, action: () -> Unit): Button =
		Button.builder(Component.literal(label)) { _ -> action() }.bounds(x, y, width, 20).build()

	private fun textButton(x: Int, y: Int, width: Int, label: String, action: () -> Unit): Button =
		Button.builder(Component.literal(label)) { _ -> action() }.bounds(x, y, width, 20).build()

	private fun configButton(x: Int, y: Int, action: () -> Unit): Button =
		Button.builder(Component.literal("配置")) { _ -> action() }
			.bounds(x, y, 44, 20)
			.tooltip(Tooltip.create(Component.literal("打开这一行的 Hook、Magnet、Rod")))
			.build()

	private fun islandLabel(id: String): String {
		val name = if (id in RouteSettings.islands) id else id.lowercase()
		val marks = ArrayList<String>(2)
		if (id in RouteSettings.scanned) marks.add("扫")
		if (id in RouteSettings.finished) marks.add("finished")
		return if (marks.isEmpty()) name else "$name ${marks.joinToString(" ")}"
	}

	private fun fishLabel(index: Int, target: FishTarget): String {
		val on = if (target in RouteSettings.fishTargets) "开" else "关"
		return "${index + 1}. ${fishName(target)} $on"
	}

	private fun fishName(target: FishTarget): String = when (target) {
		FishTarget.WISE -> "Wise"
		FishTarget.STRONG -> "Strong"
		FishTarget.ELUSIVE -> "Elusive"
		FishTarget.WAYFINDER -> "Wayfinder"
		FishTarget.XP -> "XP"
		FishTarget.PEARL -> "Pearl"
		FishTarget.TREASURE -> "Treasure"
		FishTarget.SPIRIT -> "Spirit"
	}

	private fun grottoScanned(): String =
		listOf("Temperate", "Tropical", "Barren").joinToString("  ") { id ->
			if (id.uppercase() in RouteSettings.scanned) "$id 扫" else id
		}

	private fun grottoLabel(index: Int, kind: GrottoKind): String {
		val on = if (kind in RouteSettings.grottoTargets) "开" else "关"
		return "${index + 1}. ${grottoName(kind)} $on"
	}

	private fun grottoName(kind: GrottoKind): String = when (kind) {
		GrottoKind.MAGNET_FISH -> "Magnet"
		GrottoKind.ELUSIVE_FISH -> "Elusive"
		GrottoKind.PURE_FISH -> "Pure"
		GrottoKind.PEARL -> "Pearl"
		GrottoKind.TREASURE -> "Treasure"
		GrottoKind.SPIRIT -> "Spirit"
	}
}
