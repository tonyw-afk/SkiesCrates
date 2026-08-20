package com.pokeskies.skiescrates.data.key

import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.pokeskies.skiescrates.config.ConfigManager
import java.lang.reflect.Type

@JsonAdapter(KeyRequirements.Adapter::class)
class KeyRequirements(
    // every group is a map of key ID to amount. groups (map sets) are combined with OR, while the entries within a single group (entries in the map) are combined with AND.
    val groups: List<Map<String, Int>> = emptyList()
) {
    companion object {
        // Joins key names based on the value count using conjunctions "and" and "or", like "key1 and key2" or "key1, key2 or key3"
        private fun joinKeyNames(values: List<String>, conjunction: String): String {
            return when (values.size) {
                0 -> ""
                1 -> values.first()
                2 -> "${values[0]} $conjunction ${values[1]}"
                else -> "${values.dropLast(1).joinToString(", ")}, $conjunction ${values.last()}"
            }
        }
    }

    // Trys to create a natural display for key requirements using "either", "or", and "and"
    fun getDisplay(): String {
        if (groups.isEmpty()) return ""

        val displays = groups.map { group ->
            val keys = group.map { (keyId, amount) ->
                "${ConfigManager.KEYS[keyId]?.name ?: keyId} x$amount"
            }
            val display = joinKeyNames(keys, "and")
            if (keys.size > 1 && groups.size > 1) "($display)" else display
        }

        return if (displays.size > 1) {
            "Either ${joinKeyNames(displays, "or")}"
        } else {
            displays.first()
        }
    }

    fun isNotEmpty(): Boolean {
        return groups.isNotEmpty()
    }

    override fun toString(): String {
        return "KeyRequirements(groups=$groups)"
    }

    class Adapter : JsonSerializer<KeyRequirements>, JsonDeserializer<KeyRequirements> {
        override fun serialize(
            src: KeyRequirements,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement {
            if (src.groups.isEmpty()) return JsonObject()

            if (src.groups.size == 1) {
                return JsonObject().also {
                    it.add("all_of", context.serialize(src.groups.first()))
                }
            }

            return JsonObject().also { obj ->
                obj.add("any_of", JsonArray().also { groups ->
                    src.groups.forEach { group ->
                        groups.add(JsonObject().also {
                            it.add("all_of", context.serialize(group))
                        })
                    }
                })
            }
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): KeyRequirements {
            if (!json.isJsonObject) {
                throw JsonParseException("Crate keys must be a JSON object")
            }

            val obj = json.asJsonObject
            if (obj.size() == 0) return KeyRequirements()

            val hasAllOf = obj.has("all_of") && !isNumeric(obj.get("all_of"))
            val hasAnyOf = obj.has("any_of") && !isNumeric(obj.get("any_of"))
            if (hasAllOf || hasAnyOf) {
                if (hasAllOf && hasAnyOf) {
                    throw JsonParseException("Crate keys cannot define both 'all_of' and 'any_of'")
                }
                if (obj.size() != 1) {
                    throw JsonParseException("Crate key expressions cannot contain fields alongside 'all_of' or 'any_of'")
                }

                return if (hasAllOf) {
                    KeyRequirements(listOf(readGroup(obj.get("all_of"), "all_of")))
                } else {
                    readAnyOf(obj.get("any_of"))
                }
            }

            return KeyRequirements(listOf(readGroup(obj, "keys")))
        }

        private fun readAnyOf(json: JsonElement): KeyRequirements {
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                if (obj.size() == 0) {
                    throw JsonParseException("Crate key 'any_of' must be a non-empty JSON object or array")
                }

                return KeyRequirements(obj.entrySet().map { (keyId, amount) ->
                    readGroup(JsonObject().also { it.add(keyId, amount) }, "any_of.$keyId")
                })
            }

            if (!json.isJsonArray || json.asJsonArray.size() == 0) {
                throw JsonParseException("Crate key 'any_of' must be a non-empty JSON object or array")
            }

            val groups = json.asJsonArray.mapIndexed { index, element ->
                if (!element.isJsonObject) {
                    throw JsonParseException("Crate key 'any_of' entry $index must be a JSON object")
                }
                val obj = element.asJsonObject
                if (obj.size() == 1 && obj.has("all_of") && !isNumeric(obj.get("all_of"))) {
                    readGroup(obj.get("all_of"), "any_of[$index].all_of")
                } else {
                    readGroup(obj, "any_of[$index]")
                }
            }
            return KeyRequirements(groups)
        }

        private fun readGroup(json: JsonElement, path: String): Map<String, Int> {
            if (!json.isJsonObject || json.asJsonObject.size() == 0) {
                throw JsonParseException("Crate key '$path' must be a non-empty JSON object")
            }

            return linkedMapOf<String, Int>().also { group ->
                json.asJsonObject.entrySet().forEach { (keyId, value) ->
                    val amount = if (isNumeric(value)) value.asString.toIntOrNull() else null
                    if (amount == null || amount <= 0) {
                        throw JsonParseException("Crate key '$keyId' in '$path' must have a positive integer amount")
                    }
                    group[keyId] = amount
                }
            }
        }

        private fun isNumeric(json: JsonElement): Boolean {
            return json.isJsonPrimitive && json.asJsonPrimitive.isNumber
        }
    }
}
