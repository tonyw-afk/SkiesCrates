package com.pokeskies.skiescrates.data

import com.pokeskies.skiescrates.events.CrateInteractionEvent
import net.minecraft.world.item.ItemStack

/*
 * This class is used to contain information of how a crate was opened
 */
class CrateOpenData(
    val location: DimensionalBlockPos?, // This is the location the crate was used on. This is used for in-world based crates ONLY
    val itemStack: ItemStack?, // This is the item that was used to open the crate. This is used for inventory based crates NOT for keys
    val interaction: CrateInteractionEvent.InteractionType
) {
    // this is a cached list of keys and their amounts for quick-access
    var selectedKeys: Map<String, Int> = emptyMap()

    override fun toString(): String {
        return "CrateOpenData(location=$location, itemStack=$itemStack, interaction=$interaction, selectedKeys=$selectedKeys)"
    }
}
