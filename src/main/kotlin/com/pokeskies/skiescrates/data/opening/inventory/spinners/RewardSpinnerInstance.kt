package com.pokeskies.skiescrates.data.opening.inventory.spinners

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.opening.inventory.items.SpinMode
import com.pokeskies.skiescrates.data.opening.inventory.items.SpinningItem
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.gui.CrateInventory
import com.pokeskies.skiescrates.utils.RandomCollection

class RewardSpinnerInstance(
    spinningItem: SpinningItem,
    isCompleted: Boolean, // Indicates if this Spinner is finished
    isStarted: Boolean, // Indicates if this Spinner has started. If not, ticks is the time until start
    spinsRemaining: Int, // The number of spins remaining before finishing
    ticksPerSpin: Int, // Once spun, this is the number of ticks until the next spin
    ticks: Int, // The number of ticks until the next spin OR until the animation starts
    ticksUntilChange: Int, // The number of spins until the ticksPerSpin is changed
    rewards: MutableMap<Int, Reward>, // The rewards displayed in the current slots
    private val randomBag: RandomCollection<Reward>, // The current random bag being used to generate rewards
    private val winSlots: List<Int>, // The slots that will be given to the player as rewards
): ISpinner<Reward>(spinningItem, isCompleted, isStarted, spinsRemaining, ticksPerSpin, ticks, ticksUntilChange, rewards) {
    constructor(
        spinningItem: SpinningItem,
        rewardBag: RandomCollection<Reward>,
        winSlots: List<Int>,
    ) : this(
        spinningItem,
        false,
        false,
        spinningItem.spinCount,
        spinningItem.spinInterval,
        ticks = if (spinningItem.startDelay > 0) spinningItem.startDelay else spinningItem.spinInterval,
        spinningItem.changeInterval,
        mutableMapOf(),
        rewardBag,
        winSlots
    )

    override fun generateItem(): Reward? {
        if (randomBag.size() <= 0) return null
        return randomBag.next()
    }

    override fun updateSlot(gui: CrateInventory, slot: Int, value: Reward) {
        gui.updateRewardSlot(slot, value)
    }

    fun getFinalRewards(): List<Reward> {
        if (pregeneratedSlots.isEmpty()) return emptyList()
        return when (spinningItem.mode) {
            SpinMode.RANDOM, SpinMode.SEQUENTIAL, SpinMode.INDEPENDENT -> {
                val winIndexes = winSlots.map { spinningItem.slots.indexOf(it) }.filter { it >= 0 }

                val winRewards = winIndexes.map { index -> pregeneratedSlots[(pregeneratedSlots.size - 1) - index] }

                winRewards
            }
            SpinMode.SYNCED -> {
                val reward = pregeneratedSlots.lastOrNull() ?: return emptyList()
                List(winSlots.size) { reward }
            }
        }
    }

    // Check if any rewards cause limit exceptions, remove and regenerate them if so
    // Return null if the list couldn't be regenerated (i.e. all rewards hit their limits)
    // Return the modified bag if successful
    fun validateRewards(crate: Crate, userData: UserData): RandomCollection<Reward>? {
        val copyData = UserData(userData)
        val rewards = getFinalRewards()
        for ((i, reward) in rewards.withIndex()) {
            val limit = reward.getPlayerLimit()
            if (limit > 0) {
                // If the player can't receive this reward, remove it from the bag and regenerate the slot
                if (!reward.canReceive(copyData, crate)) {
                    randomBag.remove(reward)
                    if (randomBag.size() <= 0) {
                        return null
                    }
                    val index = when (spinningItem.mode) {
                        SpinMode.RANDOM, SpinMode.SEQUENTIAL, SpinMode.INDEPENDENT -> {
                            (pregeneratedSlots.size - 1) - spinningItem.slots.indexOf(winSlots[i])
                        }
                        SpinMode.SYNCED -> pregeneratedSlots.size - 1
                    }

                    val newReward = generateItem() ?: return null
                    pregeneratedSlots[index] = newReward
                } else {
                    // If the user can receive it, increment their uses and check if we have hit the limit
                    val uses = copyData.getRewardLimits(crate, reward)
                    copyData.addRewardUse(crate, reward)
                    if ((uses + 1) > limit) {
                        randomBag.remove(reward)
                        if (randomBag.size() <= 0) {
                            return null
                        }
                    }
                }
            }
        }

        if (randomBag.size() <= 0) {
            return null
        }

        return randomBag
    }
}
