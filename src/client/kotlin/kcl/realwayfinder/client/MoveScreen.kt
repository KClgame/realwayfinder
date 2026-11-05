package kcl.realwayfinder.client

import baritone.api.BaritoneAPI
import baritone.api.utils.SettingsUtil
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class MoveScreen(private val parent: Screen) : Screen(Component.literal("行动")) {
	private val settings = BaritoneAPI.getSettings()
	private var fall = settings.maxFallHeightNoWater.value.toString()
	private var jump = plain(settings.jumpPenalty.value)
	private var lookJitter = plain(settings.randomLooking.value.let { if (it == 0.0) 0.01 else it })
	private var yawJitter = plain(settings.randomLooking113.value.let { if (it == 0.0) 2.0 else it })
	private var jitter = settings.randomLooking.value > 0.0 || settings.randomLooking113.value > 0.0
	private var smoothTicks = settings.smoothLookTicks.value.toString()
	private var smooth = settings.smoothLook.value
	private var keepLook = settings.remainWithExistingLookDirection.value
	private var diagUp = settings.allowDiagonalAscend.value
	private var diagDown = settings.allowDiagonalDescend.value
	private var dive = settings.allowDownwardSwim.value
	private var parkour = settings.allowParkour.value
	private var arriveX = RouteSettings.arriveX.toString()
	private var arriveZ = RouteSettings.arriveZ.toString()
	private var arriveY = RouteSettings.arriveY.toString()
	private var standX = RouteSettings.standX.toString()
	private var standZ = RouteSettings.standZ.toString()
	private var standY = RouteSettings.standY.toString()

	override fun isPauseScreen(): Boolean = false

	override fun onClose() {
		minecraft.gui.setScreen(parent)
	}

	override fun init() {
		addRenderableWidget(box(8, 32, fall) { fall = it })
		addRenderableWidget(box(8, 54, jump) { jump = it })
		addRenderableWidget(box(8, 76, lookJitter) { lookJitter = it })
		addRenderableWidget(box(8, 98, yawJitter) { yawJitter = it })
		addRenderableWidget(box(8, 120, smoothTicks) { smoothTicks = it })
		addRenderableWidget(toggle(8, 148, 100, "随机抖动", jitter) {
			jitter = !jitter
			rebuildWidgets()
		})
		addRenderableWidget(toggle(116, 148, 92, "平滑视线", smooth) {
			smooth = !smooth
			rebuildWidgets()
		})
		addRenderableWidget(toggle(216, 148, 92, "保持朝向", keepLook) {
			keepLook = !keepLook
			rebuildWidgets()
		})
		addRenderableWidget(toggle(8, 172, 92, "斜向登高", diagUp) {
			diagUp = !diagUp
			rebuildWidgets()
		})
		addRenderableWidget(toggle(108, 172, 92, "斜向下落", diagDown) {
			diagDown = !diagDown
			rebuildWidgets()
		})
		addRenderableWidget(toggle(208, 172, 100, "潜水游泳", dive) {
			dive = !dive
			rebuildWidgets()
		})
		addRenderableWidget(toggle(316, 172, 72, "跑跳", parkour) {
			parkour = !parkour
			rebuildWidgets()
		})
		addRenderableWidget(box(8, 220, arriveX) { arriveX = it })
		addRenderableWidget(box(8, 242, arriveZ) { arriveZ = it })
		addRenderableWidget(box(8, 264, arriveY) { arriveY = it })
		addRenderableWidget(box(8, 308, standX) { standX = it })
		addRenderableWidget(box(8, 330, standZ) { standZ = it })
		addRenderableWidget(box(8, 352, standY) { standY = it })
		addRenderableWidget(Button.builder(Component.literal("保存")) { _ ->
			applyBaritone()
			RouteSettings.arriveX = whole(arriveX, 0, 8, RouteSettings.arriveX)
			RouteSettings.arriveZ = whole(arriveZ, 0, 8, RouteSettings.arriveZ)
			RouteSettings.arriveY = whole(arriveY, 0, 8, RouteSettings.arriveY)
			RouteSettings.standX = whole(standX, 0, 64, RouteSettings.standX)
			RouteSettings.standZ = whole(standZ, 0, 64, RouteSettings.standZ)
			RouteSettings.standY = whole(standY, 0, 32, RouteSettings.standY)
			RouteSettings.save()
			onClose()
		}.bounds(8, 380, 80, 20).build())
		addRenderableWidget(Button.builder(Component.literal("返回")) { _ -> onClose() }.bounds(96, 380, 80, 20).build())
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
		super.extractRenderState(graphics, mouseX, mouseY, delta)
		val title = 0xFFE8E8E8.toInt()
		val hint = 0xFFB0B0B0.toInt()
		graphics.text(font, "走路", 8, 14, title, false)
		line(graphics, 32, "下落高度", "无水时允许下落的格数。范围 0–20，3 及以下没有摔落伤害", hint)
		line(graphics, 54, "跳跃代价", "越大越少跳。范围 0–40", hint)
		line(graphics, 76, "视线抖动", "随机抖动打开时，每拍俯仰和偏航的随机角度。范围 0–20", hint)
		line(graphics, 98, "偏航抖动", "随机抖动打开时，每拍额外的水平随机角度。范围 0–20", hint)
		line(graphics, 120, "平滑拍数", "平滑视线打开时，取最近这么多拍的平均。范围 1–40", hint)
		graphics.text(font, "钓鱼到达", 8, 200, title, false)
		line(graphics, 220, "东西 X", "相对水面格，东西允许偏差的格数。范围 0–8", hint)
		line(graphics, 242, "南北 Z", "相对水面格，南北允许偏差的格数。范围 0–8", hint)
		line(graphics, 264, "高度 Y", "相对水面格，高度允许偏差的格数。范围 0–8", hint)
		graphics.text(font, "终点偏差", 8, 290, title, false)
		line(graphics, 308, "东西 X", "终点和钓鱼点的东西差。范围 0–64", hint)
		line(graphics, 330, "南北 Z", "终点和钓鱼点的南北差。范围 0–64", hint)
		line(graphics, 352, "高度 Y", "终点和钓鱼点的高度差。范围 0–32，默认 4", hint)
	}

	private fun applyBaritone() {
		settings.maxFallHeightNoWater.value = whole(fall, 0, 20, settings.maxFallHeightNoWater.value)
		settings.jumpPenalty.value = decimal(jump, 0.0, 40.0, settings.jumpPenalty.value)
		settings.randomLooking.value = if (jitter) decimal(lookJitter, 0.0, 20.0, 0.01).let { if (it == 0.0) 0.01 else it } else 0.0
		settings.randomLooking113.value = if (jitter) decimal(yawJitter, 0.0, 20.0, 2.0).let { if (it == 0.0) 2.0 else it } else 0.0
		settings.smoothLookTicks.value = whole(smoothTicks, 1, 40, settings.smoothLookTicks.value)
		settings.smoothLook.value = smooth
		settings.remainWithExistingLookDirection.value = keepLook
		settings.allowDiagonalAscend.value = diagUp
		settings.allowDiagonalDescend.value = diagDown
		settings.allowDownwardSwim.value = dive
		settings.allowParkour.value = parkour
		settings.allowParkourAscend.value = parkour
		SettingsUtil.save(settings)
	}

	private fun line(graphics: GuiGraphicsExtractor, y: Int, name: String, detail: String, hint: Int) {
		graphics.text(font, name, 72, y + 6, 0xFFF0F0F0.toInt(), false)
		graphics.text(font, detail, 140, y + 6, hint, false)
	}

	private fun toggle(x: Int, y: Int, width: Int, name: String, on: Boolean, action: () -> Unit): Button =
		Button.builder(Component.literal("$name ${if (on) "开" else "关"}")) { _ -> action() }.bounds(x, y, width, 20).build()

	private fun box(x: Int, y: Int, value: String, onChange: (String) -> Unit): EditBox {
		val field = EditBox(minecraft.font, x, y, 56, 20, Component.empty())
		field.value = value
		field.setMaxLength(8)
		field.setResponder(onChange)
		return field
	}

	private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

	private fun whole(text: String, min: Int, max: Int, fallback: Int): Int =
		text.toIntOrNull()?.coerceIn(min, max) ?: text.toDoubleOrNull()?.toInt()?.coerceIn(min, max) ?: fallback

	private fun decimal(text: String, min: Double, max: Double, fallback: Double): Double =
		text.toDoubleOrNull()?.coerceIn(min, max) ?: fallback
}
