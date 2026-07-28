package com.pokeskies.skiescrates.data.opening.inventory

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateOpenData
import com.pokeskies.skiescrates.data.opening.OpeningInstance
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.data.userdata.UserData
import com.pokeskies.skiescrates.gui.CrateInventory
import com.pokeskies.skiescrates.utils.RandomCollection
import net.minecraft.server.level.ServerPlayer

class InventoryOpeningInstance(
    player: ServerPlayer,
    crate: Crate,
    val animation: InventoryOpeningAnimation,
    val randomBag: RandomCollection<Reward>,
    val openData: CrateOpenData,
    val userData: UserData,
): OpeningInstance(player, crate) {
    private val gui = CrateInventory(player, this)

    override fun setup() {
        gui.open()
    }

    override fun tick() {
        gui.tick()
    }

    fun getFinalRewards(): List<Reward> {
        return gui.getFinalRewards()
    }
}
