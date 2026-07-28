package com.pokeskies.skiescrates.gui

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.previews.Preview
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.utils.asNative
import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

class PreviewInventory(
    player: ServerPlayer,
    val crate: Crate,
    val preview: Preview,
    userData: UserData
): SimpleGui(
    preview.type.type, player, false
) {
    private val rewards: MutableMap<String, Pair<Reward, ItemStack>> = mutableMapOf()

    private val pageSlots = preview.rewards.slots.size.takeIf { it > 0 } ?: 1
    private var page = 0
    private var maxPages = 1

    init {
        this.title = crate.parsePlaceholders(preview.title).asNative(player)

        preview.items.forEach { (id, item) ->
            item.createItemStack(player).let {
                item.slots.forEach { slot ->
                    this.setSlot(slot, GuiElementBuilder(it)
                        .setCallback { _ ->
                            item.actions.forEach { (id, action) ->
                                action.executeAction(player, this)
                            }
                        }
                    )
                }
            }
        }

        crate.rewards.forEach { (id, reward) ->
            rewards[id] = reward to preview.rewards.createItemStack(player, reward, crate, userData)
        }

        maxPages = (rewards.size + pageSlots - 1) / pageSlots

        renderRewards()
    }

    fun nextPage(wrap: Boolean) {
        if (page < (maxPages - 1)) {
            page++
            renderRewards()
        } else {
            if (wrap) {
                page = 0
                renderRewards()
            }
        }
    }

    fun previousPage(wrap: Boolean) {
        if (page > 0) {
            page--
            renderRewards()
        } else {
            if (wrap) {
                page = maxPages - 1
                renderRewards()
            }
        }
    }

    private fun renderRewards() {
        preview.rewards.slots.forEach { slot ->
            this.clearSlot(slot)
        }
        var index = 0
        for ((_, pair) in rewards.toList().subList(pageSlots * page, minOf(pageSlots * (page + 1), rewards.size))) {
            if (index < pageSlots) {
                GuiElementBuilder.from(pair.second).let {
                    this.setSlot(preview.rewards.slots[index++], it)
                }
            }
        }
    }
}
