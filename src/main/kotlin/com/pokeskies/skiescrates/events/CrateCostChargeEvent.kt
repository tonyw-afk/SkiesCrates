package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.config.CostOptions
import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CrateCostChargeEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateCostChargeEvent> =
            EventFactory.createArrayBacked(CrateCostChargeEvent::class.java) { listeners ->
                CrateCostChargeEvent { player, crate, openData, cost ->
                    for (listener in listeners) {
                        listener.onCrateCostCharge(player, crate, openData, cost)
                    }
                }
            }
    }

    fun onCrateCostCharge(player: ServerPlayer, crate: Crate, openData: CrateOpenData, cost: CostOptions)
}
