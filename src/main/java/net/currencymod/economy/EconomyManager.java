package net.currencymod.economy;

import com.google.gson.*;
import net.currencymod.CurrencyMod;
import net.currencymod.util.FileUtil;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class EconomyManager {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(UUID.class, new UUIDAdapter())
            .create();
    
    private static final String ECONOMY_FILE = "currency_mod/economy.json";
    private static final double DEFAULT_BALANCE = 100.0;

    // C-01 fix: playerBalances is read and mutated from the main server thread
    // AND from at least three scheduler threads (WebSyncManager's Timer,
    // PlotManager's ScheduledExecutorService, TradeManager/AuctionManager
    // expiry schedulers). Plain HashMap is unsafe under concurrent mutation:
    // concurrent put/get can infinite-loop on table resize (defensive in
    // modern JVMs but corruption still occurs), silently lose writes, or
    // return stale nulls. ConcurrentHashMap's per-bin locking makes
    // individual mutations atomic and iteration weakly-consistent (no CME).
    //
    // volatile so that loadData() can safely replace the entire map
    // reference and all reader threads observe the new map promptly.
    private volatile Map<UUID, Double> playerBalances = new ConcurrentHashMap<>();
    
    /**
     * Get a player's balance
     * @param playerUuid The UUID of the player
     * @return The player's balance
     */
    public double getBalance(UUID playerUuid) {
        // computeIfAbsent is atomic per-key in ConcurrentHashMap: two threads
        // racing on a new UUID will only insert the default once.
        return playerBalances.computeIfAbsent(playerUuid, k -> {
            CurrencyMod.LOGGER.debug("Added player {} to economy with default balance", k);
            return DEFAULT_BALANCE;
        });
    }
    
    /**
     * Set a player's balance
     * @param playerUuid The UUID of the player
     * @param amount The new balance amount
     */
    public void setBalance(UUID playerUuid, double amount) {
        playerBalances.put(playerUuid, amount);
    }
    
    /**
     * Add money to a player's balance
     * @param playerUuid The UUID of the player
     * @param amount The amount to add (may be negative for a refund reversal)
     * @return The new balance
     */
    public double addBalance(UUID playerUuid, double amount) {
        // C-01 fix: atomic read-modify-write via compute. The old code did
        // getBalance + setBalance as two separate calls, which raced with
        // concurrent addBalance/removeBalance on the same UUID: both threads
        // could read the same old value and the second write would silently
        // overwrite the first. compute holds the per-bin lock for the entire
        // function, so concurrent mutations on the same key serialize.
        // Negative amounts (used by C-09 sell-path refund) are allowed --
        // they reduce the balance, which is the intent.
        AtomicReference<Double> resultRef = new AtomicReference<>();
        playerBalances.compute(playerUuid, (k, v) -> {
            double current = (v == null) ? DEFAULT_BALANCE : v;
            double newBalance = current + amount;
            resultRef.set(newBalance);
            return newBalance;
        });
        return resultRef.get();
    }
    
    /**
     * Remove money from a player's balance
     * @param playerUuid The UUID of the player
     * @param amount The amount to remove
     * @return The new balance, or -1 if the player doesn't have enough money
     */
    public double removeBalance(UUID playerUuid, double amount) {
        // C-01 fix: atomic check-and-decrement via compute. The old code did
        // getBalance (check) + setBalance (decrement) as two separate calls,
        // which raced with concurrent removeBalance on the same UUID: two
        // threads could both see sufficient funds and both succeed, driving
        // the balance negative. This compose directly with the C-05 and C-09
        // return-value checks -- those checks are only meaningful because
        // this method's check-and-decrement is now atomic per-key.
        AtomicReference<Double> resultRef = new AtomicReference<>();
        playerBalances.compute(playerUuid, (k, v) -> {
            double current = (v == null) ? DEFAULT_BALANCE : v;
            if (current < amount) {
                resultRef.set(-1.0);
                return current; // no change
            }
            double newBalance = current - amount;
            resultRef.set(newBalance);
            return newBalance;
        });
        return resultRef.get();
    }
    
    /**
     * Transfer money from one player to another
     * @param fromUuid The UUID of the sender
     * @param toUuid The UUID of the receiver
     * @param amount The amount to transfer
     * @return True if the transfer was successful, false otherwise
     */
    public boolean transferMoney(UUID fromUuid, UUID toUuid, double amount) {
        if (amount <= 0) {
            return false;
        }
        // Self-transfer is a no-op success (the check below would otherwise
        // deduct then re-add the same amount, which is wasteful but harmless;
        // short-circuiting is clearer).
        if (fromUuid.equals(toUuid)) {
            // Still ensure the player has enough funds for accounting parity.
            return getBalance(fromUuid) >= amount;
        }
        
        // C-01 fix: each leg is an atomic per-key compute. The sender leg
        // does the check-and-decrement atomically (only deducts if funds are
        // sufficient); the receiver leg is a pure atomic credit. The two
        // legs are NOT atomic with respect to each other -- a crash between
        // them leaves the sender debited but the receiver not credited --
        // but that is a pre-existing persistence concern (the economy.json
        // save is periodic, not per-transaction) and is out of scope for
        // C-01, which is specifically about the in-memory concurrency bug.
        AtomicReference<Boolean> successRef = new AtomicReference<>(Boolean.FALSE);
        playerBalances.compute(fromUuid, (k, v) -> {
            double current = (v == null) ? DEFAULT_BALANCE : v;
            if (current < amount) {
                return current; // no change, successRef stays FALSE
            }
            successRef.set(Boolean.TRUE);
            return current - amount;
        });
        if (!successRef.get()) {
            return false;
        }
        
        // Atomic credit to receiver. merge() handles the absent-key case
        // by inserting 'amount' directly; for an existing key it applies
        // the remapping function. We still need the DEFAULT_BALANCE base
        // for new receivers, so use compute instead of merge.
        playerBalances.compute(toUuid, (k, v) -> {
            double current = (v == null) ? DEFAULT_BALANCE : v;
            return current + amount;
        });
        
        return true;
    }
    
    /**
     * Load economy data from file
     * @param server The Minecraft server instance
     */
    public void loadData(MinecraftServer server) {
        if (server == null) {
            CurrencyMod.LOGGER.error("Cannot load economy data: server is null");
            return;
        }

        // Get the file using our utility class
        File economyFile = FileUtil.getServerFile(server, ECONOMY_FILE);
        if (economyFile == null) {
            CurrencyMod.LOGGER.error("Failed to get economy file path");
            return;
        }
        
        // Log the file path
        CurrencyMod.LOGGER.info("Loading economy data from: {}", economyFile.getAbsolutePath());
        
        // Check if the file is accessible
        if (!economyFile.exists()) {
            CurrencyMod.LOGGER.info("No economy data file found, starting with fresh data");
            playerBalances = new ConcurrentHashMap<>();
            return;
        }
        
        if (!FileUtil.isFileAccessible(economyFile, false)) {
            CurrencyMod.LOGGER.error("Economy file exists but is not accessible: {}", economyFile.getAbsolutePath());
            return;
        }
        
        try {
            // Read the file content
            String jsonContent = FileUtil.safeReadFromFile(economyFile);
            if (jsonContent == null || jsonContent.isEmpty()) {
                CurrencyMod.LOGGER.warn("Empty economy file, starting with fresh data");
                playerBalances = new ConcurrentHashMap<>();
                return;
            }
            
            // Parse the JSON
            JsonObject rootObject = JsonParser.parseString(jsonContent).getAsJsonObject();
            // C-01 fix: build the new map locally, then assign it atomically.
            // The old code did playerBalances.clear() followed by per-entry
            // put() calls, which left a window where reader threads on other
            // cores could see an empty or partially-loaded map. By building
            // a fresh ConcurrentHashMap and assigning the field reference at
            // the end (volatile write), readers either see the old complete
            // map or the new complete map -- never a half-loaded one.
            Map<UUID, Double> loaded = new ConcurrentHashMap<>();
            
            // Deserialize the player balances
            for (Map.Entry<String, JsonElement> entry : rootObject.entrySet()) {
                try {
                    UUID playerUuid = UUID.fromString(entry.getKey());
                    double balance = entry.getValue().getAsDouble();
                    loaded.put(playerUuid, balance);
                } catch (Exception e) {
                    CurrencyMod.LOGGER.error("Error parsing player entry: " + entry.getKey(), e);
                }
            }
            
            playerBalances = loaded;
            CurrencyMod.LOGGER.info("Loaded economy data for {} players", playerBalances.size());
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Failed to load economy data", e);
            // Reset to empty map in case of error
            playerBalances = new ConcurrentHashMap<>();
        }
    }
    
    /**
     * Save economy data to file
     * @param server The Minecraft server instance
     */
    public void saveData(MinecraftServer server) {
        if (server == null) {
            CurrencyMod.LOGGER.error("Cannot save economy data: server is null");
            return;
        }

        // Get the file using our utility class
        File economyFile = FileUtil.getServerFile(server, ECONOMY_FILE);
        if (economyFile == null) {
            CurrencyMod.LOGGER.error("Failed to get economy file path");
            return;
        }
        
        // Log the file path
        CurrencyMod.LOGGER.info("Saving economy data to: {}", economyFile.getAbsolutePath());
        
        try {
            // Create a JSON object with the player balances
            JsonObject rootObject = new JsonObject();
            
            // Serialize the player balances
            for (Map.Entry<UUID, Double> entry : playerBalances.entrySet()) {
                rootObject.addProperty(entry.getKey().toString(), entry.getValue());
            }
            
            // Convert to JSON string
            String jsonContent = GSON.toJson(rootObject);
            
            // Write to file using our utility class
            boolean success = FileUtil.safeWriteToFile(server, economyFile, jsonContent);
            if (success) {
                CurrencyMod.LOGGER.info("Saved economy data for {} players", playerBalances.size());
            } else {
                CurrencyMod.LOGGER.error("Failed to save economy data");
            }
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Error during economy data saving", e);
        }
    }
    
    /**
     * Get all player balances
     * @return An unmodifiable map of all player UUIDs to their balances
     */
    public Map<UUID, Double> getAllBalances() {
        return Collections.unmodifiableMap(playerBalances);
    }
    
    /**
     * Type adapter for UUID to handle JSON serialization/deserialization
     */
    private static class UUIDAdapter implements JsonSerializer<UUID>, JsonDeserializer<UUID> {
        @Override
        public JsonElement serialize(UUID src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return UUID.fromString(json.getAsString());
        }
    }
} 