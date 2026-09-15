package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import com.pokeskies.skiescrates.data.key.Key
import com.pokeskies.skiescrates.data.key.KeyCheckResult
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CrateKeyCheckEvent {
    companion object {
        @JvmField
        val EVENT: Event<CrateKeyCheckEvent> =
            EventFactory.createArrayBacked(CrateKeyCheckEvent::class.java) { listeners ->
                CrateKeyCheckEvent { player, crate, openData, keyId, key, amount, requiresHolding, result ->
                    var currentResult = result
                    for (listener in listeners) {
                        currentResult = listener.onCrateKeyCheck(
                            player,
                            crate,
                            openData,
                            keyId,
                            key,
                            amount,
                            requiresHolding,
                            currentResult
                        )
                    }

                    currentResult
                }
            }
    }

    fun onCrateKeyCheck(
        player: ServerPlayer,
        crate: Crate,
        openData: CrateOpenData,
        keyId: String,
        key: Key?,
        amount: Int,
        requiresHolding: Boolean,
        result: KeyCheckResult,
    ): KeyCheckResult
}
