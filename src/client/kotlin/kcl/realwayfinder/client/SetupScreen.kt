package kcl.realwayfinder.client

import kcl.realwayfinder.nav.FishTarget
import kcl.realwayfinder.nav.GrottoKind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
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
		addRenderableWidget(textButton(136, 32, 52, "脚本") { minecraft.gui.setScreen(ScriptScreen(this)) })
		addRenderableWidget(textButton(192, 32, 52, "行动") { minecraft.gui.setScreen(MoveScreen(this)) })
		addRenderableWidget(textButton(248, 32, 52, "按键") { minecraft.gui.setScreen(KeyScreen(this)) })
		addRenderableWidget(textButton(304, 32, 52, "HUD") { minecraft.gui.setScreen(HudEditScreen(this)) })

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
			addRenderableWidget(toggle(216, y, 140, fishLabel(index, target)) {
				if (target in RouteSettings.fishTargets) RouteSettings.fishTargets.remove(target) else RouteSettings.fishTargets.add(target)
				RouteSettings.save()
				rebuildWidgets()
			})
		}
		val grotto = RouteSettings.grottoOrder
		grotto.forEachIndexed { index, kind ->
			val y = 78 + index * 22
			addRenderableWidget(textButton(372, y, 18, "↑") { moveGrotto(index, -1) })
			addRenderableWidget(textButton(392, y, 18, "↓") { moveGrotto(index, 1) })
			addRenderableWidget(toggle(412, y, 140, grottoLabel(index, kind)) {
				if (kind in RouteSettings.grottoTargets) RouteSettings.grottoTargets.remove(kind) else RouteSettings.grottoTargets.add(kind)
				RouteSettings.save()
				rebuildWidgets()
			})
		}
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
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

	private fun islandLabel(id: String): String {
		val name = if (id in RouteSettings.islands) id else id.lowercase()
		val marks = ArrayList<String>(2)
		if (id in RouteSettings.scanned) marks.add("扫")
		if (id in RouteSettings.finished) marks.add("finished")
		return if (marks.isEmpty()) name else "$name ${marks.joinToString(" ")}"
	}

	private fun fishLabel(index: Int, target: FishTarget): String {
		val on = if (target in RouteSettings.fishTargets) "开" else "关"
		val name = when (target) {
			FishTarget.WISE -> "Wise"
			FishTarget.STRONG -> "Strong"
			FishTarget.ELUSIVE -> "Elusive"
			FishTarget.WAYFINDER -> "Wayfinder"
			FishTarget.PEARL -> "Pearl"
			FishTarget.TREASURE -> "Treasure"
			FishTarget.SPIRIT -> "Spirit"
		}
		return "${index + 1}. $name $on"
	}

	private fun grottoScanned(): String =
		listOf("Temperate", "Tropical", "Barren").joinToString("  ") { id ->
			if (id.uppercase() in RouteSettings.scanned) "$id 扫" else id
		}

	private fun grottoLabel(index: Int, kind: GrottoKind): String {
		val on = if (kind in RouteSettings.grottoTargets) "开" else "关"
		val name = when (kind) {
			GrottoKind.MAGNET_FISH -> "Magnet"
			GrottoKind.ELUSIVE_FISH -> "Elusive"
			GrottoKind.PURE_FISH -> "Pure"
			GrottoKind.PEARL -> "Pearl"
			GrottoKind.TREASURE -> "Treasure"
			GrottoKind.SPIRIT -> "Spirit"
		}
		return "${index + 1}. $name $on"
	}
}
