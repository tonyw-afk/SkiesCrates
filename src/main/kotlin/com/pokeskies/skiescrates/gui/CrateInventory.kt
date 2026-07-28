package com.pokeskies.skiescrates.gui

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.opening.inventory.InventoryOpeningAnimation
import com.pokeskies.skiescrates.data.opening.inventory.InventoryOpeningInstance
import com.pokeskies.skiescrates.data.opening.inventory.spinners.AnimatedSpinnerInstance
import com.pokeskies.skiescrates.data.opening.inventory.spinners.RewardSpinnerInstance
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.events.CrateOpenedEvent
import com.pokeskies.skiescrates.utils.RandomCollection
import com.pokeskies.skiescrates.utils.Utils
import com.pokeskies.skiescrates.utils.asNative
import eu.pb4.sgui.api.gui.SimpleGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

class CrateInventory(
    player: ServerPlayer,
    val opening: InventoryOpeningInstance
): SimpleGui(opening.animation.type.type, player, false) {
    private var isFinished = false
    private var closeTicks = 0

    // This is a map of "Animated Preset ID" to a RandomCollection of ItemStacks for that preset
    private val cachedAnimatedPresets: MutableMap<String, RandomCollection<ItemStack>> = mutableMapOf()
    private var animatedSpinners: MutableMap<String, AnimatedSpinnerInstance> = mutableMapOf()

    private var cachedRewardStacks: MutableMap<String, ItemStack> = mutableMapOf()
    private var rewardSpinners: MutableMap<String, RewardSpinnerInstance> = mutableMapOf()

    private val userData = opening.userData

    private val crate: Crate = opening.crate
    private val animation: InventoryOpeningAnimation = opening.animation
    private var randomBag: RandomCollection<Reward> = opening.randomBag

    init {
        this.title = opening.crate.parsePlaceholders(animation.title).asNative(player)

        animation.items.static.forEach { (id, item) ->
            item.slots.forEach { slot ->
                this.setSlot(slot, item.createItemStack(player))
            }
        }

        // Setup animated spinners
        animation.presets.animations.forEach { (id, presetItems) ->
            val collection = RandomCollection<ItemStack>()
            presetItems.forEach { animatedItem ->
                val itemStack = animatedItem.createItemStack(player)
                collection.add(itemStack, animatedItem.weight.toDouble())
            }
            cachedAnimatedPresets[id] = collection
        }
        animation.items.animated.forEach { (id, spinningItem) ->
            if (spinningItem.preset.isEmpty()) return@forEach
            val bag = cachedAnimatedPresets[spinningItem.preset] ?: run {
                Utils.printError("Animated preset ${spinningItem.preset} not found for spinner $id")
                return@forEach
            }

            animatedSpinners[id] = AnimatedSpinnerInstance(spinningItem, bag).also { it.pregenerate() }
        }

        // Setup rewards spinners
        crate.rewards.forEach { (id, reward) ->
            cachedRewardStacks[id] = reward.getDisplayItem(player, reward.getPlaceholders(userData, crate))
        }
        animation.items.rewards.forEach { (id, item) ->
            val spinner = RewardSpinnerInstance(item, randomBag, animation.winSlots).also {
                it.pregenerate()
            }

            val returnBag = spinner.validateRewards(crate, userData)

            if (returnBag == null) {
                Utils.printError("No rewards were possible for spinner $id in crate ${crate.id} for player ${player.name.string}. Cancelling crate!")
                player.sendMessage(Component.text("An error occurred while opening the crate. Please contact an administrator.", NamedTextColor.RED))
                isFinished = true
                close()
                return@forEach
            }

            randomBag = returnBag
            rewardSpinners[id] = spinner
        }
    }

    fun tick() {
        if (isFinished) {
            if (closeTicks++ >= animation.closeDelay) {
                this.close()
                return
            }
        } else {
            var allCompleted = true
            rewardSpinners.forEach { (_, spinner) ->
                if (spinner.isCompleted()) {
                    return@forEach
                }
                allCompleted = false
                spinner.tick(player, this)
            }

            if (allCompleted) {
                completeOpening()
            }
        }

        animatedSpinners.forEach { (_, spinner) ->
            if (spinner.isCompleted()) return@forEach
            spinner.tick(player, this)
        }
    }

    fun updateRewardSlot(slot: Int, reward: Reward) {
        this.setSlot(slot, cachedRewardStacks[reward.id] ?: reward.getDisplayItem(player, reward.getPlaceholders(userData, crate)))
    }

    override fun onClose() {
        if (!isFinished) {
            if (animation.skippable) {
                completeOpening()
            } else {
                this.open()
                return
            }
        }

        opening.stop()
    }

    private fun completeOpening() {
        if (isFinished) return
        isFinished = true
        val rewards = getFinalRewards()
        rewards.forEach { it.giveReward(player, crate) }
        CrateOpenedEvent.EVENT.invoker().onCrateOpened(player, crate, opening.openData, rewards)
    }

    fun getFinalRewards(): List<Reward> {
        return rewardSpinners.flatMap { (_, data) -> data.getFinalRewards() }
    }
}
