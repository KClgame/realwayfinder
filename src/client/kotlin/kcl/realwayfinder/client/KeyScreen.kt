package kcl.realwayfinder.client

import kcl.spotfilter.client.Hotkeys
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Same A/B layout as SpotFilter keys. A is the modifier stored here. B is the Controls binding. */
class KeyScreen(private val parent: Screen) : Screen(Component.literal("Realwayfinder 按键")) {
	private var recording: String? = null

	override fun isPauseScreen(): Boolean = false

	override fun init() {
		var y = 36
		row("toggle", RealwayfinderClient.toggle, RouteSettings.modToggle, y)
		y += 24
		row("setup", RealwayfinderClient.setup, RouteSettings.modSetup, y)
		y += 24
		row("scan", RealwayfinderClient.scan, RouteSettings.modScan, y)
		y += 24
		row("skip", RealwayfinderClient.skip, RouteSettings.modSkip, y)
		y += 24
		row("record", RealwayfinderClient.record, RouteSettings.modRecord, y)
		y += 24
		row("reset", RealwayfinderClient.resetPath, RouteSettings.modReset, y)
		addRenderableWidget(Button.builder(Component.literal("返回")) { _ -> onClose() }.bounds(width / 2 - 40, height - 28, 80, 20).build())
	}

	private fun row(id: String, mapping: KeyMapping, modifier: Int, y: Int) {
		val recordingThis = recording == id
		val modText = if (recordingThis) "> press <" else "A: ${modifierName(modifier)}"
		addRenderableWidget(Button.builder(Component.literal(modText)) { _ ->
			recording = id
			rebuildWidgets()
		}.bounds(150, y, 90, 20).build())
		addRenderableWidget(Button.builder(Component.literal("B: ").append(mapping.translatedKeyMessage)) { _ -> }
			.bounds(246, y, 120, 20).build())
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		graphics.text(font, title, 12, 12, 0xFFFFFFFF.toInt(), false)
		val names = listOf("开关", "配置", "扫点", "跳过", "录制", "重置寻路")
		names.forEachIndexed { index, name ->
			graphics.text(font, name, 12, 42 + index * 24, 0xFFE0E0E0.toInt(), false)
		}
		graphics.text(font, "A 是修饰键，右键清空。B 在控制设置里改。", 12, height - 46, 0xFFAAAAAA.toInt(), false)
	}

	override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
		if (event.button() == 1 && event.x().toInt() in 150 until 240) {
			val index = (event.y().toInt() - 36) / 24
			when (index) {
				0 -> RouteSettings.modToggle = 0
				1 -> RouteSettings.modSetup = 0
				2 -> RouteSettings.modScan = 0
				3 -> RouteSettings.modSkip = 0
				4 -> RouteSettings.modRecord = 0
				5 -> RouteSettings.modReset = 0
				else -> return super.mouseClicked(event, doubled)
			}
			recording = null
			RouteSettings.save()
			rebuildWidgets()
			return true
		}
		return super.mouseClicked(event, doubled)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		val id = recording
		if (id != null) {
			if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
				when (id) {
					"toggle" -> RouteSettings.modToggle = event.key()
					"setup" -> RouteSettings.modSetup = event.key()
					"scan" -> RouteSettings.modScan = event.key()
					"skip" -> RouteSettings.modSkip = event.key()
					"record" -> RouteSettings.modRecord = event.key()
					"reset" -> RouteSettings.modReset = event.key()
				}
				RouteSettings.save()
			}
			recording = null
			rebuildWidgets()
			return true
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose()
			return true
		}
		return super.keyPressed(event)
	}

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}

	private fun modifierName(glfwKey: Int): String =
		Hotkeys.modifierLabel(glfwKey).string
}
