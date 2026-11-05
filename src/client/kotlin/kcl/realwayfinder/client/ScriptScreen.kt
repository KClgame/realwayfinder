package kcl.realwayfinder.client

import kcl.realwayfinder.nav.ScriptRole
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ScriptScreen(private val parent: Screen?) : Screen(Component.literal("回放")) {
	private var placeIndex = 0
	private var replayIndex = 0
	private var draft = ""

	override fun isPauseScreen(): Boolean = false

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}

	override fun init() {
		val places = ScanRecord.places()
		val place = places[placeIndex.coerceIn(0, places.lastIndex)]
		val replays = ReplayLibrary.names()
		if (replayIndex > replays.lastIndex) replayIndex = replays.lastIndex.coerceAtLeast(0)
		val chosen = replays.getOrNull(replayIndex) ?: "未选择"
		addRenderableWidget(button(8, 24, 24, 20, "<") {
			placeIndex = (placeIndex - 1 + places.size) % places.size
			rebuildWidgets()
		})
		addRenderableWidget(button(36, 24, 150, 20, place.second) { })
		addRenderableWidget(button(190, 24, 24, 20, ">") {
			placeIndex = (placeIndex + 1) % places.size
			rebuildWidgets()
		})
		val grotto = place.first.startsWith("grotto-")
		if (!grotto) {
			addRenderableWidget(button(8, 50, 220, 20, "扫点：${bound(place.first, ScriptRole.SCAN)}") {
				assign(place.first, ScriptRole.SCAN, chosen)
			})
		}
		addRenderableWidget(button(8, if (grotto) 50 else 74, 220, 20, "回出生点：${bound(place.first, ScriptRole.RETURN)}") {
			assign(place.first, ScriptRole.RETURN, chosen)
		})
		if (grotto) {
			addRenderableWidget(button(8, 74, 220, 20, "进入：${bound(place.first, ScriptRole.ENTER)}") {
				assign(place.first, ScriptRole.ENTER, chosen)
			})
		}
		val formY = 108
		val name = EditBox(minecraft.font, 8, formY, 160, 20, Component.literal("名称"))
		name.value = draft
		name.setMaxLength(32)
		name.setResponder { draft = it }
		addRenderableWidget(name)
		addRenderableWidget(button(172, formY, 50, 20, "录制") {
			val player = minecraft.player ?: return@button
			minecraft.gui.setScreen(null)
			ScanRecord.startNamed(player, draft)
		})
		addRenderableWidget(button(226, formY, 50, 20, "播放") {
			val player = minecraft.player ?: return@button
			val tape = ReplayLibrary.frames(chosen) ?: return@button
			minecraft.gui.setScreen(null)
			RouteRuntime.preview(player, tape, chosen)
		})
		var y = formY + 28
		replays.forEachIndexed { index, label ->
			val mark = if (index == replayIndex) "● " else ""
			addRenderableWidget(button(8, y, 200, 18, mark + label) {
				replayIndex = index
				rebuildWidgets()
			})
			y += 20
		}
		addRenderableWidget(button(220, formY + 28, 50, 20, "删除") {
			if (chosen != "未选择") {
				ReplayLibrary.delete(chosen)
				rebuildWidgets()
			}
		})
		addRenderableWidget(button(8, height - 28, 80, 20, "返回") { onClose() })
	}

	private fun bound(placeKey: String, role: ScriptRole): String =
		ReplayLibrary.binding(placeKey, role) ?: "未选"

	private fun assign(placeKey: String, role: ScriptRole, name: String) {
		if (name == "未选择") return
		ReplayLibrary.bind(placeKey, role, name)
		rebuildWidgets()
	}

	private fun button(x: Int, y: Int, w: Int, h: Int, label: String, action: () -> Unit): Button =
		Button.builder(Component.literal(label)) { _ -> action() }.bounds(x, y, w, h).build()
}
