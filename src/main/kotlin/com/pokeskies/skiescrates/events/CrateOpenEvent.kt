package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CrateOpenEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateOpenEvent> =
            EventFactory.createArrayBacked(CrateOpenEvent::class.java) { listeners ->
                CrateOpenEvent { player, crate, openData, forced ->
                    for (listener in listeners) {
                        listener.onCrateOpen(player, crate, openData, forced)
                    }
                }
            }
    }

    fun onCrateOpen(player: ServerPlayer, crate: Crate, openData: CrateOpenData, forced: Boolean)
}
