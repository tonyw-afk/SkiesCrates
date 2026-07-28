package com.pokeskies.skiescrates.data.opening.world.types

import com.google.gson.annotations.SerializedName
import com.pokeskies.skiescrates.config.SoundOption
import com.pokeskies.skiescrates.config.VecPosition
import com.pokeskies.skiescrates.data.opening.world.RewardItemEntity
import com.pokeskies.skiescrates.data.opening.world.WorldAnimationType
import com.pokeskies.skiescrates.data.opening.world.WorldOpeningAnimation
import com.pokeskies.skiescrates.data.opening.world.WorldOpeningInstance
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.events.CrateOpenedEvent
import com.pokeskies.skiescrates.integrations.ModIntegration
import com.pokeskies.skiescrates.managers.HologramsManager
import com.pokeskies.skiescrates.mixins.EntityAccessor
import com.pokeskies.skiescrates.mixins.ItemEntityAccessor
import com.pokeskies.skiescrates.utils.asNative
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3
import java.util.*


class SimpleRollWorldAnimation(
    @SerializedName("spin_count")
    val spinCount: Int = 10, // How many times the item spins before stopping
    @SerializedName("spin_interval")
    val spinInterval: Int = 1, // The amount of ticks between each spin
    @SerializedName("start_delay")
    val startDelay: Int = 5, // The amount of ticks before the first spin
    @SerializedName("change_interval")
    val changeInterval: Int = 5, // The amount of spins between each time the spinInterval is changed
    @SerializedName("change_amount")
    val changeAmount: Int = 1, // The amount to change spinInterval by
    @SerializedName("end_delay")
    val endDelay: Int = 20, // The amount of ticks to wait after the final spin
    val sound: SoundOption? = null, // The sound to play when the item spins,
    val offset: VecPosition = VecPosition(),
    @SerializedName(value = "hide_hologram")
    val hideHologram: Boolean = false,
): WorldOpeningAnimation(WorldAnimationType.SIMPLE_ROLL) {
    @Transient private lateinit var pregeneratedSlots: MutableList<Reward>
    @Transient private var currentIndex = 0 // iterates through pregeneratedSlots
    @Transient private var currentReward: Reward? = null
    @Transient private var itemEntity: RewardItemEntity? = null

    @Transient private var isStarted = false
    @Transient private var isCompleted = false
    @Transient private var isPrepared = false
    @Transient private var ticks = startDelay

    @Transient private var spinsRemaining = spinCount
    @Transient private var ticksPerSpin = spinInterval
    @Transient private var ticksUntilChange = changeInterval

    @Transient private var pos = Vec3.ZERO

    override fun instantiate(): WorldOpeningAnimation {
        return SimpleRollWorldAnimation(
            spinCount,
            spinInterval,
            startDelay,
            changeInterval,
            changeAmount,
            endDelay,
            sound,
            offset,
            hideHologram
        )
    }

    override fun setup(opening: WorldOpeningInstance) {
        prepareRewards(opening)

        if (hideHologram && ModIntegration.HOLODISPLAYS.isModLoaded()) {
            HologramsManager.hideHologramForPlayer(opening.player, opening.instance)
        }
    }

    override fun prepareRewards(opening: WorldOpeningInstance): List<Reward> {
        if (isPrepared) return pregeneratedSlots.lastOrNull()?.let(::listOf) ?: emptyList()

        pregeneratedSlots = List(spinCount) { generateItem(opening) }.filterNotNull().toMutableList()

        currentIndex = 0
        currentReward = null
        itemEntity = null

        isStarted = false
        isCompleted = false
        ticks = startDelay

        spinsRemaining = spinCount
        ticksPerSpin = spinInterval
        ticksUntilChange = changeInterval

        pos = opening.instance.pos.bottomCenter.add(offset.toVec3())
        isPrepared = true

        return pregeneratedSlots.lastOrNull()?.let(::listOf) ?: emptyList()
    }

    // Ticks the current spinner and returns if the spinner is completed
    override fun tick(opening: WorldOpeningInstance) {
        if (isStarted && !isCompleted) {
            ticks--
            if (ticks <= 0) {
                ticksUntilChange--
                if (ticksUntilChange <= 0) {
                    ticksUntilChange = changeInterval
                    ticksPerSpin += changeAmount
                }
                ticks = ticksPerSpin
                spinsRemaining--

                spin(opening)

                if (spinsRemaining <= 0) {
                    isCompleted = true
                    ticks = endDelay
                    giveCurrentReward(opening)
                }
            }
        } else if (isCompleted) {
            ticks--
            if (ticks <= 0) {
                opening.stop()
            }
        } else {
            ticks--
            if (ticks <= 0) {
                isStarted = true
                ticks = ticksPerSpin

                spin(opening)

                if (--spinsRemaining <= 0) {
                    isCompleted = true
                    ticks = endDelay
                    giveCurrentReward(opening)
                }
            }
        }
    }

    override fun stop(opening: WorldOpeningInstance) {
        opening.player.connection.send(ClientboundRemoveEntitiesPacket(itemEntity!!.id))

        if (hideHologram && ModIntegration.HOLODISPLAYS.isModLoaded()) {
            HologramsManager.showHologramForPlayer(opening.player, opening.instance)
        }
    }

    private fun spin(opening: WorldOpeningInstance) {
        val newReward = pregeneratedSlots.getOrNull(currentIndex++)
        currentReward = newReward

        if (newReward != null) {
            if (itemEntity == null) {
                itemEntity = RewardItemEntity(
                    opening.instance.level,
                    pos,
                    newReward.getDisplayItem(opening.player)
                )
                opening.player.connection.send(
                    ClientboundAddEntityPacket(
                        itemEntity!!.id,
                        itemEntity!!.uuid,
                        pos.x,
                        pos.y,
                        pos.z,
                        0f,
                        0f,
                        EntityType.ITEM,
                        0,
                        Vec3.ZERO,
                        0.0
                    )
                )
                opening.player.connection.send(ClientboundSetEntityDataPacket(itemEntity!!.id, listOf(
                    SynchedEntityData.DataValue.create(EntityAccessor.getNoGravity(), true),
                    SynchedEntityData.DataValue.create(EntityAccessor.getCustomName(), Optional.of(newReward.name.asNative())),
                    SynchedEntityData.DataValue.create(EntityAccessor.getCustomNameVisible(), true),
                    SynchedEntityData.DataValue.create(ItemEntityAccessor.getItem(), itemEntity!!.item)
                )))
            } else {
                itemEntity!!.item = newReward.getDisplayItem(opening.player)
                opening.player.connection.send(ClientboundSetEntityDataPacket(itemEntity!!.id, listOf(
                    SynchedEntityData.DataValue.create(EntityAccessor.getCustomName(), Optional.of(newReward.name.asNative())),
                    SynchedEntityData.DataValue.create(ItemEntityAccessor.getItem(), itemEntity!!.item)
                )))
            }
        }

        // Play sound
        sound?.playSound(opening.player)
    }

    private fun generateItem(opening: WorldOpeningInstance): Reward? {
        if (opening.randomBag.size() <= 0) return null
        return opening.randomBag.next()
    }

    private fun giveCurrentReward(opening: WorldOpeningInstance) {
        val reward = currentReward ?: return
        reward.giveReward(opening.player, opening.crate)
        CrateOpenedEvent.EVENT.invoker().onCrateOpened(opening.player, opening.crate, opening.openData, listOf(reward))
    }
}
