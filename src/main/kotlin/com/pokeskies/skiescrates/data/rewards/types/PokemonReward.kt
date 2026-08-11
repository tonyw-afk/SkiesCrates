package com.pokeskies.skiescrates.data.rewards.types

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.pokeskies.skiescrates.config.item.GenericItem
import com.pokeskies.skiescrates.data.Crate
import com.pokeskies.skiescrates.data.rewards.Reward
import com.pokeskies.skiescrates.data.rewards.RewardLimits
import com.pokeskies.skiescrates.data.rewards.RewardType
import com.pokeskies.skiescrates.data.rewards.options.bool.BooleanChance
import com.pokeskies.skiescrates.data.rewards.options.bool.BooleanOption
import com.pokeskies.skiescrates.data.rewards.options.bool.BooleanValue
import com.pokeskies.skiescrates.data.rewards.options.int.IntOption
import com.pokeskies.skiescrates.placeholders.PlaceholderManager
import com.pokeskies.skiescrates.utils.FlexibleListAdaptorFactory
import com.pokeskies.skiescrates.utils.Utils
import com.pokeskies.skiescrates.utils.asNative
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore

class PokemonReward(
    name: String = "",
    description: List<String> = emptyList(),
    display: GenericItem? = null,
    weight: Int = 1,
    limits: RewardLimits? = null,
    broadcast: Boolean = false,
    private val pokemon: PokemonRewardOptions = PokemonRewardOptions(),
) : Reward(RewardType.POKEMON, name, description, display, weight, limits, broadcast) {
    companion object {
        private val DEFAULT_DISPLAY = GenericItem("cobblemon:poke_ball", name = "Pokemon Reward")
    }

    override fun giveReward(player: ServerPlayer, crate: Crate) {
        // Super to call the message
        super.giveReward(player, crate)

        val pokemonInstance = pokemon.createPokemon() ?: run {
            Utils.printError("Failed to create Pokemon for reward '$name' when giving to player ${player.name}.")
            player.sendMessage(net.kyori.adventure.text.Component.text("Your Pokemon Reward could not be created! Please contact an administrator.",
                NamedTextColor.RED))
            return
        }

        if (!Cobblemon.storage.getParty(player).add(pokemonInstance)) {
            Utils.printInfo("There was an error while giving a player their Pokemon Reward (${pokemon}).")
            player.sendMessage(net.kyori.adventure.text.Component.text("Your Pokemon Reward could not be added to your party! Please contact an administrator.",
                NamedTextColor.RED))
            return
        }
    }

    override fun getGenericDisplay(): GenericItem {
        return display ?: DEFAULT_DISPLAY
    }

    override fun getDisplayItem(player: ServerPlayer, placeholders: Map<String, String>): ItemStack {
        // If a display item is set that is not a Pokemon Model item, use that instead
        if (display != null && display.item.isNotEmpty() && !display.item.equals("cobblemon:pokemon_model", ignoreCase = true)) {
            return display.also {
                if (it.name == null) it.name = name
            }.createItemStack(player, placeholders)
        }

        val itemStack = if (!pokemon.isRandom()) {
            pokemon.createDisplayItem() ?: DEFAULT_DISPLAY.createItemStack(player, placeholders)
        } else {
            DEFAULT_DISPLAY.createItemStack(player, placeholders)
        }

        // Set the name onto the ItemStack, which may get overridden by the display
        DataComponentPatch.builder().let {
            it.set(DataComponents.ITEM_NAME, Component.empty().setStyle(Style.EMPTY.withItalic(false))
                    .append(name.asNative(player, placeholders)))
            itemStack.applyComponents(it.build())
        }

        display?.let {
            val dataComponents = DataComponentPatch.builder()

            if (display.name != null) {
                dataComponents.set(
                    DataComponents.ITEM_NAME, Component.empty().setStyle(Style.EMPTY.withItalic(false))
                        .append(name.asNative(player, placeholders)))
            }

            if (!display.lore.isNullOrEmpty()) {
                val parsedLore: MutableList<String> = mutableListOf()
                for (line in display.lore.stream().map { it }.toList()) {
                    val parsedLine = PlaceholderManager.parse(player, line)
                    if (parsedLine.contains("\n")) {
                        parsedLine.split("\n").forEach { parsedLore.add(it) }
                    } else {
                        parsedLore.add(parsedLine)
                    }
                }
                dataComponents.set(DataComponents.LORE, ItemLore(parsedLore.stream().map {
                    Component.empty().setStyle(Style.EMPTY.withItalic(false)).append(it.asNative()) as Component
                }.toList()))
            }

            itemStack.applyComponents(dataComponents.build())

            itemStack
        }

        return itemStack
    }

    class PokemonRewardOptions(
        val species: String = "",
        val form: String = "",
        @JsonAdapter(FlexibleListAdaptorFactory::class)
        @SerializedName("aspects", alternate = ["aspect"])
        val aspects: List<String> = emptyList(),
        val level: IntOption? = null,
        val shiny: BooleanOption? = null,
    ) {
        fun isRandom(): Boolean {
            return species.equals("random", ignoreCase = true)
        }

        fun createDisplayItem(): ItemStack? {
            val species = createSpecies() ?: return null
            val properties = PokemonProperties.parse(buildList {
                add(species.resourceIdentifier.toString())
                if (form.isNotEmpty()) add("form=$form")
                addAll(aspects)
            }.joinToString(" "))

            if (shiny is BooleanValue) properties.shiny = shiny.bool
            properties.updateAspects()

            return PokemonItem.from(species, properties.aspects)
        }

        fun createPokemon(): Pokemon? {
            val species = createSpecies() ?: return null
            val pokemon = species.create()
            pokemon.form = species.getFormByName(form)

            aspects.forEach {
                PokemonProperties.parse(it).apply(pokemon)
            }

            level?.let { option -> pokemon.level = option.getValue() }
            shiny?.let { option ->
                if (option is BooleanValue) {
                    pokemon.shiny = option.bool
                } else if (option is BooleanChance) {
                    pokemon.shiny = option.getValue()
                }
            }

            pokemon.initialize()

            return pokemon
        }

        private fun createSpecies(): Species? {
            if (species.isEmpty()) {
                Utils.printError("Species was empty when creating Pokemon reward.")
                return null
            }
            return if (isRandom()) {
                PokemonSpecies.random()
            } else {
                if (species.contains(":")) {
                    ResourceLocation.tryParse(species)?.let {
                        PokemonSpecies.getByIdentifier(it)
                    }
                } else {
                    PokemonSpecies.getByName(species)
                }
            } ?: run {
                Utils.printError("Could not find Pokemon species '$species' when creating Pokemon reward.")
                return null
            }
        }
    }

    override fun toString(): String {
        return "PokemonReward(name='$name', display=$display, weight=$weight, limits=$limits, broadcast=$broadcast, pokemon=$pokemon)"
    }
}
