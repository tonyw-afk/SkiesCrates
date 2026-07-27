package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult

fun interface CrateOpenAttemptEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateOpenAttemptEvent> =
            EventFactory.createArrayBacked(CrateOpenAttemptEvent::class.java) { listeners ->
                CrateOpenAttemptEvent { player, crate, openData, forced ->
                    for (listener in listeners) {
                        val result = listener.onCrateOpenAttempt(player, crate, openData, forced)
                        if (result != InteractionResult.PASS) return@CrateOpenAttemptEvent result
                    }

                    InteractionResult.PASS
                }
            }
    }

    fun onCrateOpenAttempt(
        player: ServerPlayer,
        crate: Crate,
        openData: CrateOpenData,
        forced: Boolean,
    ): InteractionResult
}
