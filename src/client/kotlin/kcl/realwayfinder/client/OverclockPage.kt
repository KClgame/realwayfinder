package kcl.realwayfinder.client

import kcl.realwayfinder.nav.HookPick
import kcl.realwayfinder.nav.MagnetPick
import kcl.realwayfinder.nav.RodPick
import kcl.realwayfinder.nav.UnstablePick
import kcl.realwayfinder.nav.advance
import kcl.realwayfinder.nav.menuLabel
import kcl.realwayfinder.nav.perkName
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class OverclockPage(
	private val parent: Screen,
	private val heading: String,
	private val enabled: Boolean,
	private val triple: OcTriple,
	private val onToggle: () -> Unit,
	private val onChange: (OcTriple) -> Unit
) : Screen(Component.literal(heading)) {
	override fun isPauseScreen(): Boolean = false

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}

	override fun init() {
		addRenderableWidget(button(8, 40, "开关：${if (enabled) "开" else "关"}", "这个目标是否参与钓鱼") {
			onToggle()
		})
		addRenderableWidget(button(8, 72, "Hook：${label(triple.hook.perkName())}", "左键切到下一项。保持表示不改游戏里的 Hook") {
			onChange(triple.copy(hook = advance(HookPick.entries, triple.hook)))
		})
		addRenderableWidget(button(8, 96, "Magnet：${label(triple.magnet.perkName())}", "左键切到下一项。保持表示不改游戏里的 Magnet") {
			onChange(triple.copy(magnet = advance(MagnetPick.entries, triple.magnet)))
		})
		addRenderableWidget(button(8, 120, "Rod：${label(triple.rod.perkName())}", "左键切到下一项。保持表示不改游戏里的 Rod") {
			onChange(triple.copy(rod = advance(RodPick.entries, triple.rod)))
		})
		addRenderableWidget(button(8, 144, "Unstable：${triple.unstable.menuLabel()}", "左键切换，含不启用。游戏里右键切换加成，Shift+左键才启用。") {
			onChange(triple.copy(unstable = advance(UnstablePick.entries, triple.unstable)))
		})
		addRenderableWidget(Button.builder(Component.literal("返回")) { _ -> onClose() }.bounds(8, 176, 80, 20).build())
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		graphics.fill(0, 0, width, 28, 0xF0101418.toInt())
		graphics.text(font, heading, 8, 8, 0xFFFFFFFF.toInt(), false)
		graphics.text(font, "Hook、Magnet、Rod，以及 Unstable。不启用则不点它", 8, 204, 0xFFAAAAAA.toInt(), false)
	}

	private fun label(name: String?): String = name ?: "保持"

	private fun button(x: Int, y: Int, label: String, tip: String, action: () -> Unit): Button =
		Button.builder(Component.literal(label)) { _ -> action() }
			.bounds(x, y, 220, 20)
			.tooltip(Tooltip.create(Component.literal(tip)))
			.build()
}
