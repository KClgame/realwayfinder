package kcl.realwayfinder.client.mixin

import kcl.realwayfinder.client.RouteRuntime
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPacketListener::class)
abstract class ActionBarMixin {
	@Inject(method = ["setActionBarText"], at = [At("TAIL")])
	private fun realwayfinderReadActionBar(packet: ClientboundSetActionBarTextPacket, ci: CallbackInfo) {
		RouteRuntime.onActionBar(packet.text().string)
	}
}
