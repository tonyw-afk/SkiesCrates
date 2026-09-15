package com.pokeskies.skiescrates.events

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.previews.Preview
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.server.level.ServerPlayer

fun interface CratePreviewEvent {
    companion object {
        @JvmField
        val EVENT: Event<CratePreviewEvent> =
            EventFactory.createArrayBacked(CratePreviewEvent::class.java) { listeners ->
                CratePreviewEvent { player, crate, preview ->
                    for (listener in listeners) {
                        listener.onCratePreview(player, crate, preview)
                    }
                }
            }
    }

    fun onCratePreview(player: ServerPlayer, crate: Crate, preview: Preview)
}
