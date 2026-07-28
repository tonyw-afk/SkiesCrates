package com.pokeskies.skiescrates.data.opening.world

import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.CrateInstance
import com.pokeskies.skiescrates.data.CrateOpenData
import com.pokeskies.skiescrates.data.opening.OpeningInstance
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.utils.RandomCollection
import net.minecraft.server.level.ServerPlayer

class WorldOpeningInstance(
    player: ServerPlayer,
    crate: Crate,
    val instance: CrateInstance,
    val animation: WorldOpeningAnimation,
    val randomBag: RandomCollection<Reward>,
    val openData: CrateOpenData,
): OpeningInstance(player, crate) {
    override fun tick() {
        animation.tick(this)
    }

    override fun setup() {
        animation.setup(this)
    }

    fun prepareRewards(): List<Reward> {
        return animation.prepareRewards(this)
    }

    override fun stop() {
        super.stop()
        animation.stop(this)
    }
}
