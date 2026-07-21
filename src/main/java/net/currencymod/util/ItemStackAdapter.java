package net.currencymod.util;

import com.google.gson.*;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Pair;
import net.currencymod.CurrencyMod;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.lang.reflect.Type;

/**
 * C-11 fix: serializes and deserializes ItemStack objects with GSON using the
 * vanilla ItemStack.CODEC, which round-trips the full component map
 * (enchantments, durability, custom data, custom names, lore, trim, written-
 * book contents, shulker-box contents, potion data, banner patterns, etc.).
 *
 * The previous implementation only stored {id, count, displayName} and never
 * read displayName back on deserialization. Any enchanted / max-durability /
 * customised item that transited through JSON-backed storage (marketplace
 * listings across server restart) was silently downgraded to a plain item.
 * On parse failure, the fallback returned minecraft:stone -- silent
 * substitution rather than an error.
 *
 * The new format produced by ItemStack.CODEC is a JSON object like:
 *   {
 *     "id": "minecraft:diamond_sword",
 *     "count": 1,
 *     "components": {
 *       "minecraft:enchantments": {...},
 *       "minecraft:damage": 50
 *     }
 *   }
 *
 * Backward compatibility: ItemStack.CODEC is lenient about extra fields, so
 * old-format files ({id, count, displayName}) will still deserialize via the
 * codec as plain ItemStacks with no components (since the components field is
 * absent). The displayName field is simply ignored by the codec. This means
 * existing marketplace.json files load without error and without regression
 * -- the items are still stripped of their (already-lost) components, but
 * newly-saved items will round-trip correctly going forward.
 *
 * On parse failure, returns ItemStack.EMPTY (NOT minecraft:stone) -- the
 * caller can check isEmpty() and decide how to handle the missing item.
 *
 * Scope: this adapter is registered ONLY in MarketplaceManager.java:54, so
 * only marketplace listings are affected. Auctions use savePendingItems
 * (fixed separately in C-07). Trade does not persist items.
 */
public class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {

    /**
     * Get the RegistryWrapper.WrapperLookup needed by ItemStack.CODEC.
     * Uses CurrencyMod.getServer() so the adapter can be instantiated as
     * `new ItemStackAdapter()` with no constructor changes (the audit's
     * architectural refactor is avoided for minimal regression risk).
     *
     * @return the WrapperLookup, or null if the server is not yet available
     *         (in which case serialize/deserialize fall back to the legacy
     *         basic format).
     */
    private static RegistryWrapper.WrapperLookup getWrapperLookup() {
        MinecraftServer server = CurrencyMod.getServer();
        if (server == null) {
            return null;
        }
        try {
            return server.getRegistryManager();
        } catch (Exception e) {
            CurrencyMod.LOGGER.warn("ItemStackAdapter: failed to get registry manager from server: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public JsonElement serialize(ItemStack itemStack, Type typeOfSrc, JsonSerializationContext context) {
        // C-11 fix: use ItemStack.CODEC with RegistryOps to serialize the
        // full component map. Falls back to the legacy basic format if the
        // server (and therefore the WrapperLookup) is not available.
        if (itemStack == null || itemStack.isEmpty()) {
            JsonObject empty = new JsonObject();
            empty.addProperty("id", "minecraft:air");
            empty.addProperty("count", 0);
            return empty;
        }

        RegistryWrapper.WrapperLookup lookup = getWrapperLookup();
        if (lookup == null) {
            CurrencyMod.LOGGER.warn("ItemStackAdapter: server not available; falling back to legacy basic format " +
                "(components will be lost)");
            return serializeLegacy(itemStack);
        }

        try {
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, lookup);
            DataResult<JsonElement> result = ItemStack.CODEC.encodeStart(ops, itemStack);
            JsonElement encoded = result.result().orElse(null);
            if (encoded != null && encoded.isJsonObject()) {
                // Also add displayName for human readability -- this is NOT
                // used by the codec on deserialize (it's a derived value), but
                // it makes the JSON file easier to inspect manually.
                JsonObject obj = encoded.getAsJsonObject();
                obj.addProperty("displayName", itemStack.getName().getString());
                return obj;
            }
            CurrencyMod.LOGGER.warn("ItemStackAdapter: ItemStack.CODEC.encodeStart returned non-object; falling back to legacy");
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("ItemStackAdapter: error during ItemStack.CODEC serialization; falling back to legacy: {}", e.getMessage());
        }
        return serializeLegacy(itemStack);
    }

    @Override
    public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json == null || !json.isJsonObject()) {
            CurrencyMod.LOGGER.warn("ItemStackAdapter: cannot deserialize non-object JSON; returning EMPTY");
            return ItemStack.EMPTY;
        }

        JsonObject obj = json.getAsJsonObject();

        // Empty-stack marker (produced by serialize when itemStack.isEmpty()).
        if (obj.has("id") && "minecraft:air".equals(obj.get("id").getAsString())) {
            return ItemStack.EMPTY;
        }

        RegistryWrapper.WrapperLookup lookup = getWrapperLookup();
        if (lookup == null) {
            CurrencyMod.LOGGER.warn("ItemStackAdapter: server not available; falling back to legacy basic deserialize " +
                "(components will be lost)");
            return deserializeLegacy(obj);
        }

        try {
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, lookup);
            DataResult<Pair<ItemStack, JsonElement>> result = ItemStack.CODEC.decode(ops, json);
            ItemStack stack = result.result().map(Pair::getFirst).orElse(null);
            if (stack != null) {
                return stack;
            }
            // Codec returned empty -- likely an old-format file with an
            // unresolvable itemId. Fall back to legacy parse to preserve
            // existing marketplace data.
            CurrencyMod.LOGGER.warn("ItemStackAdapter: ItemStack.CODEC.decode returned empty; falling back to legacy parse");
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("ItemStackAdapter: error during ItemStack.CODEC deserialization; falling back to legacy: {}", e.getMessage());
        }
        return deserializeLegacy(obj);
    }

    /**
     * Legacy serialization: {id, count, displayName}. Used as a fallback when
     * the server (and therefore the WrapperLookup) is unavailable. Preserves
     * the pre-C-11 behavior so the adapter still works during early server
     * startup or in unit tests.
     */
    private JsonElement serializeLegacy(ItemStack itemStack) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", Registries.ITEM.getId(itemStack.getItem()).toString());
        jsonObject.addProperty("count", itemStack.getCount());
        jsonObject.addProperty("displayName", itemStack.getName().getString());
        return jsonObject;
    }

    /**
     * Legacy deserialization: parse {id, count} into a plain ItemStack with
     * no components. Used as a fallback when the codec cannot decode the JSON
     * (e.g., old-format files saved before the C-11 fix). On any parse
     * failure, returns ItemStack.EMPTY (NOT minecraft:stone -- the old code's
     * stone substitution was a silent data corruption).
     */
    private ItemStack deserializeLegacy(JsonObject obj) {
        try {
            if (!obj.has("id")) {
                CurrencyMod.LOGGER.warn("ItemStackAdapter: legacy deserialize -- missing 'id' field; returning EMPTY");
                return ItemStack.EMPTY;
            }
            String itemId = obj.get("id").getAsString();
            Item item = Registries.ITEM.get(Identifier.of(itemId));
            int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
            // C-11 fix: return ItemStack.EMPTY on parse failure instead of
            // minecraft:stone (the old behavior). ItemStack.EMPTY is a safe
            // no-op that the caller can detect via isEmpty().
            if (item == null || item == net.minecraft.item.Items.AIR) {
                CurrencyMod.LOGGER.warn("ItemStackAdapter: legacy deserialize -- itemId '{}' resolved to null/air; returning EMPTY", itemId);
                return ItemStack.EMPTY;
            }
            return new ItemStack(item, count);
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("ItemStackAdapter: legacy deserialize failed; returning EMPTY: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }
}
