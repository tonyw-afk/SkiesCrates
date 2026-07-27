package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CrateCooldownCheckEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateCooldownCheckEvent> =
            EventFactory.createArrayBacked(CrateCooldownCheckEvent::class.java) { listeners ->
                CrateCooldownCheckEvent { player, crate, openData, lastOpened, endsAt, onCooldown ->
                    for (listener in listeners) {
                        listener.onCrateCooldownCheck(player, crate, openData, lastOpened, endsAt, onCooldown)
                    }
                }
            }
    }

    fun onCrateCooldownCheck(
        player: ServerPlayer,
        crate: Crate,
        openData: CrateOpenData,
        lastOpened: Long?,
        endsAt: Long?,
        onCooldown: Boolean,
    )
}
