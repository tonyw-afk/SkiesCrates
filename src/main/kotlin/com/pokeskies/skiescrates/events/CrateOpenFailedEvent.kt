package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CrateOpenFailedEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateOpenFailedEvent> =
            EventFactory.createArrayBacked(CrateOpenFailedEvent::class.java) { listeners ->
                CrateOpenFailedEvent { player, crate, openData, forced, reason ->
                    for (listener in listeners) {
                        listener.onCrateOpenFailed(player, crate, openData, forced, reason)
                    }
                }
            }
    }

    fun onCrateOpenFailed(
        player: ServerPlayer,
        crate: Crate,
        openData: CrateOpenData,
        forced: Boolean,
        reason: Reason,
    )

    enum class Reason {
        INTERACTION_LIMITED,
        ATTEMPT_CANCELLED,
        ALREADY_OPENING,
        NO_PERMISSION,
        INVENTORY_SPACE,
        INVALID_ECONOMY_PROVIDER,
        INSUFFICIENT_FUNDS,
        BALANCE_CHANGED,
        COOLDOWN,
        NO_REWARDS,
        KEY_NOT_FOUND,
        KEY_NOT_HOLDING,
        NOT_ENOUGH_KEYS,
        INVALID_KEY,
        KEYS_CHANGED,
        NO_CRATE_ITEM,
        STORAGE,
        INVALID_ANIMATION,
    }
}
