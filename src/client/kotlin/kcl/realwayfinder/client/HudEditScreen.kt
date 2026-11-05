package kcl.realwayfinder.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Click the screen to park the spot panel. Auto movement keeps running behind this screen. */
class HudEditScreen(private val parent: Screen) : Screen(Component.literal("编辑 HUD")) {
	override fun isPauseScreen(): Boolean = false

	override fun init() {
		addRenderableWidget(Button.builder(Component.literal("右上角")) { _ ->
			RouteSettings.hudX = -1
			RouteSettings.hudY = -1
			RouteSettings.save()
		}.bounds(8, height - 28, 70, 20).build())
		addRenderableWidget(Button.builder(Component.literal("返回")) { _ -> onClose() }.bounds(84, height - 28, 60, 20).build())
	}

	override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
		if (event.y().toInt() < height - 32) {
			RouteSettings.hudX = event.x().toInt()
			RouteSettings.hudY = event.y().toInt()
			RouteSettings.save()
			return true
		}
		return super.mouseClicked(event, doubled)
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		graphics.text(font, "点击画面，把点位面板放到那里", 8, 8, 0xFFFFFFFF.toInt(), false)
		val x = if (RouteSettings.hudX >= 0) RouteSettings.hudX else width - 140
		val y = if (RouteSettings.hudY >= 0) RouteSettings.hudY else 8
		graphics.fill(x, y, x + 120, y + 36, 0xA0000000.toInt())
		graphics.text(font, "当前点位", x + 6, y + 6, 0xFFFFFFFF.toInt(), false)
		graphics.text(font, "下一个点位", x + 6, y + 20, 0xFFAAAAAA.toInt(), false)
	}

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}
}
