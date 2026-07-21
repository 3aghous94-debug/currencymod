package net.currencymod.plots;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.currencymod.CurrencyMod;
import net.currencymod.util.FileUtil;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.LinkedHashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages player plot ownership, taxation, and the world-chunk plot registry.
 */
public class PlotManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CurrencyMod/PlotManager");
    private static final String PLOTS_FILE = "currency_mod/plots.json";
    private static PlotManager instance;

    // --- Ownership records (unchanged) ---
    private final Map<UUID, List<Plot>> playerPlots = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> taxTask;

    // --- Tax tracking ---
    private long lastTaxDay = -1;
    private long lastCheckedTime = 0;
    private final Set<UUID> taxedPlayersToday = new HashSet<>();

    // --- Bulk-purchase confirmations (kept for op-level escape hatch) ---
    private static class PendingPurchase {
        final PlotType plotType;
        final int quantity;
        final int totalCost;
        final long timestamp;

        PendingPurchase(PlotType plotType, int quantity, int totalCost) {
            this.plotType = plotType;
            this.quantity = quantity;
            this.totalCost = totalCost;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Map<UUID, PendingPurchase> pendingPurchases = new HashMap<>();
    private static final long PURCHASE_TIMEOUT = 60000;

    // --- World chunk registry (new) ---
    private final Map<ChunkKey, WorldPlot> registeredPlots = new HashMap<>();

    // --- Plot Vision ---
    private final Set<UUID> plotVisionEnabled = new HashSet<>();
    private int visionTickCounter = 0;

    // --- Multi-buy ---
    private final Set<UUID> multiBuyMode = new HashSet<>();
    private final Map<UUID, LinkedHashSet<ChunkKey>> selectedPlots = new HashMap<>();

    // =========================================================================
    // Singleton
    // =========================================================================

    public static PlotManager getInstance() {
        if (instance == null) instance = new PlotManager();
        return instance;
    }

    private PlotManager() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void initialize(MinecraftServer server) {
        loadData(server);
        startTaxCollection(server);
        registerSleepWatcher();
        registerPlotVisionTick();
        LOGGER.info("PlotManager initialized. Last tax day: {}. Registered plots: {}",
                lastTaxDay, registeredPlots.size());
    }

    private void registerSleepWatcher() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long currentTime = server.getOverworld().getTimeOfDay();
            if (lastCheckedTime == 0) {
                lastCheckedTime = currentTime;
                return;
            }
            if (currentTime - lastCheckedTime > 100) {
                long currentDay = currentTime / 24000L;
                long previousDay = lastCheckedTime / 24000L;
                if (currentDay > previousDay && currentDay != lastTaxDay) {
                    LOGGER.info("Day change detected during time skip (sleep). Day {} → {}.",
                            previousDay, currentDay);
                    taxedPlayersToday.clear();
                    lastTaxDay = currentDay;
                    collectTaxesForOnlinePlayers(server, true);
                    saveData(server);
                }
            }
            lastCheckedTime = currentTime;
        });
        LOGGER.info("Registered sleep watcher for tax collection");
    }

    // =========================================================================
    // Plot Vision
    // =========================================================================

    /**
     * Toggles Plot Vision for a player. Returns the new state (true = enabled).
     */
    public boolean togglePlotVision(UUID playerUuid) {
        if (plotVisionEnabled.contains(playerUuid)) {
            plotVisionEnabled.remove(playerUuid);
            return false;
        } else {
            plotVisionEnabled.add(playerUuid);
            return true;
        }
    }

    /** Returns true if the player has Plot Vision active. */
    public boolean hasPlotVision(UUID playerUuid) {
        return plotVisionEnabled.contains(playerUuid);
    }

    /**
     * Registers a server-tick listener that spawns particles every second (20 ticks)
     * for every online player who has Plot Vision enabled.
     */
    private void registerPlotVisionTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            visionTickCounter++;
            if (visionTickCounter % 20 != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (plotVisionEnabled.contains(player.getUuid())) {
                    spawnPlotVisionParticles(player);
                }
            }
        });
        LOGGER.info("Registered Plot Vision particle tick");
    }

    /**
     * Spawns END_ROD particles for every available (unowned, registered) chunk within
     * a 5×5 chunk grid centred on the player:
     *   - A floating cluster 3 blocks above the chunk centre.
     *   - A 3-particle vertical line at each of the 4 chunk corners, just above the surface.
     *
     * Only considers chunks in the same dimension as the player.
     */
    private void spawnPlotVisionParticles(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        String dim = world.getRegistryKey().getValue().toString();
        int playerCX = player.getBlockPos().getX() >> 4;
        int playerCZ = player.getBlockPos().getZ() >> 4;

        // Corner offsets within a chunk (0.5 = centre of the corner block)
        double[][] cornerOffsets = {
            {  0.5,  0.5 },   // NW
            { 15.5,  0.5 },   // NE
            {  0.5, 15.5 },   // SW
            { 15.5, 15.5 }    // SE
        };

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int cx = playerCX + dx;
                int cz = playerCZ + dz;
                ChunkKey key = new ChunkKey(cx, cz, dim);
                WorldPlot wp = registeredPlots.get(key);
                if (wp == null || !wp.isAvailable()) continue;

                boolean loaded = world.isChunkLoaded(cx, cz);
                double fallbackY = player.getY();

                // --- Centre glow (floating 3 blocks above surface) ---
                double centreX = cx * 16 + 8.5;
                double centreZ = cz * 16 + 8.5;
                double centreY = loaded
                        ? world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) centreX, (int) centreZ) + 3.0
                        : fallbackY + 3.0;

                world.spawnParticles(player, ParticleTypes.END_ROD, false,
                        centreX, centreY, centreZ, 4, 0.4, 0.15, 0.4, 0.02);

                // --- Corner lines (3 stacked particles per corner, just above surface) ---
                for (double[] offset : cornerOffsets) {
                    double cornerX = cx * 16 + offset[0];
                    double cornerZ = cz * 16 + offset[1];
                    double baseY = loaded
                            ? world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) cornerX, (int) cornerZ)
                            : fallbackY;

                    // Three evenly-spaced particles form a short vertical line
                    world.spawnParticles(player, ParticleTypes.END_ROD, false,
                            cornerX, baseY + 0.5, cornerZ, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    // =========================================================================
    // Multi-buy
    // =========================================================================

    /**
     * Toggles multi-buy mode for a player. Clears any existing selections when turning off.
     * Returns the new state (true = enabled).
     */
    public boolean toggleMultiBuy(UUID playerUuid) {
        if (multiBuyMode.contains(playerUuid)) {
            multiBuyMode.remove(playerUuid);
            selectedPlots.remove(playerUuid);
            return false;
        } else {
            multiBuyMode.add(playerUuid);
            selectedPlots.put(playerUuid, new LinkedHashSet<>());
            return true;
        }
    }

    public boolean isMultiBuyMode(UUID playerUuid) {
        return multiBuyMode.contains(playerUuid);
    }

    public Set<ChunkKey> getSelectedPlots(UUID playerUuid) {
        Set<ChunkKey> sel = selectedPlots.get(playerUuid);
        return sel != null ? Collections.unmodifiableSet(sel) : Collections.emptySet();
    }

    public int getSelectionCount(UUID playerUuid) {
        Set<ChunkKey> sel = selectedPlots.get(playerUuid);
        return sel != null ? sel.size() : 0;
    }

    /**
     * Toggles a chunk in/out of the player's selection.
     * Returns the new selection count, or -1 if the cap of 16 has been reached
     * and the chunk was not already selected.
     */
    public int togglePlotSelection(UUID playerUuid, ChunkKey key) {
        LinkedHashSet<ChunkKey> sel =
                selectedPlots.computeIfAbsent(playerUuid, k -> new LinkedHashSet<>());
        if (sel.contains(key)) {
            sel.remove(key);
        } else {
            if (sel.size() >= 16) return -1;
            sel.add(key);
        }
        return sel.size();
    }

    /**
     * Purchases all plots in plotKeys as the given type, applying the bulk discount.
     * Validates availability and shared type support before deducting any currency.
     * Clears the player's multi-buy state on success.
     */
    public boolean buyMultiWorldPlots(ServerPlayerEntity player, Set<ChunkKey> plotKeys, PlotType type) {
        List<WorldPlot> plots = new ArrayList<>();
        for (ChunkKey key : plotKeys) {
            WorldPlot wp = registeredPlots.get(key);
            if (wp == null || !wp.isAvailable() || !wp.getEnabledTypes().contains(type)) {
                player.sendMessage(Text.literal(
                        "One or more selected plots are no longer available or don't support that type.")
                        .formatted(Formatting.RED));
                return false;
            }
            plots.add(wp);
        }

        int quantity = plots.size();
        if (quantity == 0) return false;

        int basePrice = type.getPurchasePrice();
        int totalCost = calculateBulkPurchaseCost(basePrice, quantity);
        UUID playerUuid = player.getUuid();

        if (CurrencyMod.getEconomyManager().getBalance(playerUuid) < totalCost) {
            player.sendMessage(Text.literal("Insufficient funds. Total cost for " + quantity + " plots: ")
                    .append(Text.literal("$" + totalCost).formatted(Formatting.RED)));
            return false;
        }

        CurrencyMod.getEconomyManager().removeBalance(playerUuid, totalCost);
        for (WorldPlot wp : plots) {
            Plot plot = new Plot(type);
            playerPlots.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(plot);
            wp.setOwnerUuid(playerUuid);
            wp.setPlotId(plot.getId());
        }

        // Clear multi-buy state after a successful bulk purchase
        selectedPlots.remove(playerUuid);
        multiBuyMode.remove(playerUuid);

        saveData(CurrencyMod.getServer());

        int totalTax = quantity * type.getDailyTax();
        player.sendMessage(Text.literal("✅ Purchased ")
                .append(Text.literal(quantity + "x ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(type.getDisplayName()).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" plots for "))
                .append(Text.literal("$" + totalCost).formatted(Formatting.GOLD))
                .append(Text.literal(" (bulk discount applied). Daily tax: "))
                .append(Text.literal("$" + totalTax + "/day").formatted(Formatting.YELLOW)));
        LOGGER.info("Player {} purchased {}x {} plots via multi-buy for ${}",
                player.getName().getString(), quantity, type.getDisplayName(), totalCost);
        return true;
    }

    public void checkPlayerTaxStatus(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        long currentDay = player.getServer().getOverworld().getTimeOfDay() / 24000L;
        if (currentDay == lastTaxDay && !taxedPlayersToday.contains(playerUuid)) {
            if (playerHasPlots(playerUuid)) {
                collectTaxFromPlayer(player);
                taxedPlayersToday.add(playerUuid);
                saveData(player.getServer());
                LOGGER.info("Collected tax on join from {} (day {})",
                        player.getName().getString(), currentDay);
            }
        }
    }

    private boolean playerHasPlots(UUID playerUuid) {
        return playerPlots.containsKey(playerUuid) && !playerPlots.get(playerUuid).isEmpty();
    }

    public void shutdown(MinecraftServer server) {
        saveData(server);
        if (taxTask != null && !taxTask.isDone()) taxTask.cancel(false);
        if (scheduler != null) scheduler.shutdown();
        LOGGER.info("PlotManager shutdown complete");
    }

    private void startTaxCollection(MinecraftServer server) {
        taxTask = scheduler.scheduleAtFixedRate(() -> checkForNewDay(server), 1, 1, TimeUnit.MINUTES);
        LOGGER.info("Scheduled tax collection task started");
    }

    private void checkForNewDay(MinecraftServer server) {
        if (server == null) return;
        long currentDay = server.getOverworld().getTimeOfDay() / 24000L;
        if (currentDay != lastTaxDay) {
            LOGGER.info("New Minecraft day: {}. Previous tax day: {}", currentDay, lastTaxDay);
            taxedPlayersToday.clear();
            lastTaxDay = currentDay;
            collectTaxesForOnlinePlayers(server, false);
            saveData(server);
        }
    }

    private void collectTaxesForOnlinePlayers(MinecraftServer server, boolean wasSleeping) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerUuid = player.getUuid();
            if (taxedPlayersToday.contains(playerUuid)) continue;
            if (!playerHasPlots(playerUuid)) continue;
            collectTaxFromPlayer(player);
            taxedPlayersToday.add(playerUuid);
            if (wasSleeping) {
                player.sendMessage(Text.literal("Taxes are collected at dawn, even when you sleep through the night!")
                        .formatted(Formatting.GRAY, Formatting.ITALIC));
            }
        }
    }

    private boolean collectTaxFromPlayer(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        List<Plot> plots = playerPlots.get(playerUuid);
        int totalTax = calculateTotalTax(plots);

        // C-12 fix: clamp negative totalTax to 0.
        // The COMMUNITY plot type's default tax is -10 (PlotType.java:93) and
        // ModConfig.PlotConfig only seeds PERSONAL/FARM/BUSINESS/INDUSTRIAL,
        // never COMMUNITY -- so the -10 default leaks through to runtime.
        // Combined with the missing ownership cap (separate audit item), a
        // player who buys N COMMUNITY plots receives N*10 currency per day
        // unconditionally, even while offline. 100 plots == $1,000/day forever,
        // break-even ~26 in-game days, pure profit thereafter. This is an
        // unintended money-printing exploit, not a feature.
        //
        // Phase 1a fix per audit v3.0: treat any negative totalTax as 0.
        // This is a behavior change: existing COMMUNITY plot owners who were
        // receiving the unintended subsidy will stop receiving it. The audit
        // recommends communicating this in the changelog and adding an in-game
        // notice on first join after upgrade. Ownership cap + config-level
        // validation are tracked as separate follow-up work.
        if (totalTax < 0) {
            LOGGER.warn("Player {} had negative total tax of {} (likely from COMMUNITY plots); " +
                "clamping to 0 to prevent money duplication exploit (C-12).",
                player.getName().getString(), totalTax);
            totalTax = 0;
        }

        if (totalTax == 0) {
            return true;
        }

        double playerBalance = CurrencyMod.getEconomyManager().getBalance(playerUuid);
        if (playerBalance >= totalTax) {
            CurrencyMod.getEconomyManager().removeBalance(playerUuid, totalTax);
            player.sendMessage(Text.literal("💰 Daily plot tax of ")
                    .append(Text.literal("$" + totalTax).formatted(Formatting.GOLD))
                    .append(Text.literal(" has been collected for your plots.").formatted(Formatting.WHITE)));
            LOGGER.info("Collected ${} in taxes from {}", totalTax, player.getName().getString());
            return true;
        } else {
            player.sendMessage(Text.literal("⚠️ WARNING: You do not have enough money to pay your property tax of ")
                    .append(Text.literal("$" + totalTax).formatted(Formatting.RED))
                    .append(Text.literal(". Your plots may be repossessed soon!").formatted(Formatting.RED)));
            LOGGER.warn("Player {} has insufficient funds for tax payment of ${}",
                    player.getName().getString(), totalTax);
            return false;
        }
    }

    public int collectManualTax(MinecraftServer server) {
        if (server == null) { LOGGER.warn("Cannot collect tax: server is null"); return 0; }
        taxedPlayersToday.clear();
        int count = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!playerHasPlots(player.getUuid())) continue;
            if (collectTaxFromPlayer(player)) {
                count++;
                taxedPlayersToday.add(player.getUuid());
            }
        }
        saveData(server);
        return count;
    }

    // =========================================================================
    // World chunk registry — admin operations
    // =========================================================================

    /**
     * Returns the WorldPlot for the chunk the player is standing in, or null.
     */
    public WorldPlot getWorldPlotAt(ServerPlayerEntity player) {
        return registeredPlots.get(ChunkKey.fromPlayer(player));
    }

    /**
     * Returns the WorldPlot for an explicit key, or null.
     */
    public WorldPlot getWorldPlot(ChunkKey key) {
        return registeredPlots.get(key);
    }

    /**
     * Returns an unmodifiable view of all registered plots.
     */
    public Map<ChunkKey, WorldPlot> getRegisteredPlots() {
        return Collections.unmodifiableMap(registeredPlots);
    }

    /**
     * Registers a chunk as a buyable plot. Does nothing if already registered.
     */
    public void registerPlot(ChunkKey key, Set<PlotType> enabledTypes) {
        if (registeredPlots.containsKey(key)) return;
        registeredPlots.put(key, new WorldPlot(key, enabledTypes));
        saveData(CurrencyMod.getServer());
        LOGGER.info("Registered plot at {}", key);
    }

    /**
     * Removes a chunk from the registry. Returns false if it is currently owned.
     */
    public boolean unregisterPlot(ChunkKey key) {
        WorldPlot wp = registeredPlots.get(key);
        if (wp == null) return false;
        if (wp.isOwned()) return false;
        registeredPlots.remove(key);
        saveData(CurrencyMod.getServer());
        LOGGER.info("Unregistered plot at {}", key);
        return true;
    }

    /**
     * Updates the enabled types for an already-registered plot (admin edit).
     */
    public void updateEnabledTypes(ChunkKey key, Set<PlotType> enabledTypes) {
        WorldPlot wp = registeredPlots.get(key);
        if (wp != null) {
            wp.setEnabledTypes(enabledTypes);
            saveData(CurrencyMod.getServer());
        }
    }

    /**
     * Admin grant: assigns an unowned registered chunk to an online player as a specific type.
     * Does not require the type to be in the chunk's enabledTypes — admin grants bypass that.
     * Returns false if the chunk is already owned.
     */
    public boolean grantPlot(ServerPlayerEntity target, WorldPlot worldPlot, PlotType type) {
        if (worldPlot.isOwned()) return false;
        UUID targetUuid = target.getUuid();

        Plot plot = new Plot(type);
        playerPlots.computeIfAbsent(targetUuid, k -> new ArrayList<>()).add(plot);
        worldPlot.setOwnerUuid(targetUuid);
        worldPlot.setPlotId(plot.getId());
        saveData(CurrencyMod.getServer());

        LOGGER.info("Admin granted {} plot at {} to {}",
                type.getDisplayName(), worldPlot.getLocation(), target.getName().getString());
        return true;
    }

    /**
     * Admin delete: removes all data for a registered chunk — unlinks ownership records,
     * removes the player's Plot entry, and deletes the WorldPlot from the registry.
     * Works regardless of whether the chunk is owned.
     */
    public void deletePlot(ChunkKey key) {
        WorldPlot wp = registeredPlots.get(key);
        if (wp == null) return;

        if (wp.isOwned() && wp.getPlotId() != null) {
            List<Plot> plots = playerPlots.get(wp.getOwnerUuid());
            if (plots != null) {
                plots.removeIf(p -> p.getId().equals(wp.getPlotId()));
                if (plots.isEmpty()) playerPlots.remove(wp.getOwnerUuid());
            }
        }

        registeredPlots.remove(key);
        saveData(CurrencyMod.getServer());
        LOGGER.info("Admin deleted plot at {}", key);
    }

    // =========================================================================
    // Chunk-aware purchase
    // =========================================================================

    /**
     * Purchases the given WorldPlot as the specified type.
     * Validates availability, type enabled, and balance.
     * Creates the Plot record, links it to the WorldPlot, and saves.
     */
    public boolean buyWorldPlot(ServerPlayerEntity player, WorldPlot worldPlot, PlotType type) {
        if (!worldPlot.isAvailable()) {
            player.sendMessage(Text.literal("This plot is not available for purchase.").formatted(Formatting.RED));
            return false;
        }
        if (!worldPlot.getEnabledTypes().contains(type)) {
            player.sendMessage(Text.literal("That plot type is not available at this location.").formatted(Formatting.RED));
            return false;
        }
        int price = type.getPurchasePrice();
        UUID playerUuid = player.getUuid();
        if (CurrencyMod.getEconomyManager().getBalance(playerUuid) < price) {
            player.sendMessage(Text.literal("You cannot afford this plot.")
                    .append(Text.literal(" Cost: $" + price).formatted(Formatting.RED)));
            return false;
        }

        // Deduct currency, create plot record, link WorldPlot
        CurrencyMod.getEconomyManager().removeBalance(playerUuid, price);
        Plot plot = new Plot(type);
        playerPlots.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(plot);
        worldPlot.setOwnerUuid(playerUuid);
        worldPlot.setPlotId(plot.getId());
        saveData(CurrencyMod.getServer());

        player.sendMessage(Text.literal("You purchased a ")
                .append(Text.literal(type.getDisplayName()).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" plot for "))
                .append(Text.literal("$" + price).formatted(Formatting.GOLD))
                .append(Text.literal(". Daily tax: "))
                .append(Text.literal("$" + type.getDailyTax() + "/day").formatted(Formatting.YELLOW)));
        LOGGER.info("Player {} purchased {} plot at {}", player.getName().getString(),
                type.getDisplayName(), worldPlot.getLocation());
        return true;
    }

    /**
     * Sells the WorldPlot owned by the player, refunding 20% of the original price.
     * Unlinks the WorldPlot and removes the Plot record.
     */
    public boolean sellWorldPlot(ServerPlayerEntity player, WorldPlot worldPlot) {
        UUID playerUuid = player.getUuid();
        if (!worldPlot.isOwned() || !playerUuid.equals(worldPlot.getOwnerUuid())) {
            player.sendMessage(Text.literal("You don't own this plot.").formatted(Formatting.RED));
            return false;
        }
        UUID plotId = worldPlot.getPlotId();
        List<Plot> plots = playerPlots.get(playerUuid);
        if (plots == null) {
            player.sendMessage(Text.literal("Error: plot ownership data not found.").formatted(Formatting.RED));
            return false;
        }
        Optional<Plot> plotOpt = plots.stream().filter(p -> p.getId().equals(plotId)).findFirst();
        if (plotOpt.isEmpty()) {
            player.sendMessage(Text.literal("Error: plot record not found.").formatted(Formatting.RED));
            return false;
        }
        Plot plot = plotOpt.get();
        PlotType plotType = plot.getType();
        int refundAmount = (int)(plotType.getPurchasePrice() * 0.2);

        CurrencyMod.getEconomyManager().addBalance(playerUuid, refundAmount);
        plots.remove(plot);
        if (plots.isEmpty()) playerPlots.remove(playerUuid);
        worldPlot.setOwnerUuid(null);
        worldPlot.setPlotId(null);
        saveData(CurrencyMod.getServer());

        player.sendMessage(Text.literal("You sold your ")
                .append(Text.literal(plotType.getDisplayName()).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" plot for "))
                .append(Text.literal("$" + refundAmount).formatted(Formatting.GOLD))
                .append(Text.literal(" (20% of original price).").formatted(Formatting.GRAY)));
        LOGGER.info("Player {} sold {} plot at {}", player.getName().getString(),
                plotType.getDisplayName(), worldPlot.getLocation());
        return true;
    }

    // =========================================================================
    // Legacy command-based purchase (kept for op-level escape hatch)
    // =========================================================================

    public boolean buyPlot(ServerPlayerEntity player, PlotType plotType) {
        return buyPlots(player, plotType, 1);
    }

    public boolean buyPlots(ServerPlayerEntity player, PlotType plotType, int quantity) {
        if (quantity < 1) {
            player.sendMessage(Text.literal("Quantity must be at least 1").formatted(Formatting.RED));
            return false;
        }
        UUID playerUuid = player.getUuid();
        int basePrice = plotType.getPurchasePrice();
        int totalCost = calculateBulkPurchaseCost(basePrice, quantity);

        if (CurrencyMod.getEconomyManager().getBalance(playerUuid) < totalCost) {
            player.sendMessage(Text.literal("You don't have enough money. Total cost: ")
                    .append(Text.literal("$" + totalCost).formatted(Formatting.RED)));
            return false;
        }
        if (quantity == 1) return processPurchase(player, plotType, quantity, totalCost);
        showPurchaseConfirmation(player, plotType, quantity, totalCost, basePrice);
        return true;
    }

    private void showPurchaseConfirmation(ServerPlayerEntity player, PlotType plotType,
                                          int quantity, int totalCost, int basePrice) {
        UUID playerUuid = player.getUuid();
        pendingPurchases.put(playerUuid, new PendingPurchase(plotType, quantity, totalCost));

        MutableText header = Text.literal("🏞️ ")
                .append(Text.literal("Plot Purchase Confirmation").formatted(Formatting.GOLD, Formatting.BOLD));
        MutableText details = Text.literal("\nYou are about to purchase:").formatted(Formatting.WHITE)
                .append(Text.literal("\n  • ").formatted(Formatting.WHITE))
                .append(Text.literal(String.valueOf(quantity)).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal("x " + plotType.getDisplayName()).formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal(" plots").formatted(Formatting.WHITE))
                .append(Text.literal("\n  • Daily tax: ").formatted(Formatting.WHITE))
                .append(Text.literal("$" + (quantity * plotType.getDailyTax())).formatted(Formatting.YELLOW));

        MutableText breakdown = Text.literal("\n\nPrice breakdown:").formatted(Formatting.WHITE);
        for (int i = 0; i < quantity; i++) {
            int plotPrice = calculatePlotPriceWithDiscount(basePrice, i);
            int discountPercent = Math.min(50, i * 5);
            if (i == 0) {
                breakdown.append(Text.literal("\n  Plot 1: $").formatted(Formatting.WHITE)
                        .append(Text.literal(String.valueOf(plotPrice)).formatted(Formatting.GOLD))
                        .append(Text.literal(" (full price)").formatted(Formatting.GRAY)));
            } else {
                breakdown.append(Text.literal("\n  Plot " + (i + 1) + ": $").formatted(Formatting.WHITE)
                        .append(Text.literal(String.valueOf(plotPrice)).formatted(Formatting.GOLD))
                        .append(Text.literal(" (" + discountPercent + "% off)").formatted(Formatting.GREEN)));
            }
        }

        MutableText totalSummary = Text.literal("\n\nTotal cost: ")
                .append(Text.literal("$" + totalCost).formatted(Formatting.GREEN, Formatting.BOLD));
        MutableText confirmButton = Text.literal("[Confirm Purchase]")
                .setStyle(Style.EMPTY.withColor(Formatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plots confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Click to confirm"))));
        MutableText cancelButton = Text.literal("[Cancel]")
                .setStyle(Style.EMPTY.withColor(Formatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plots cancel"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Click to cancel"))));

        player.sendMessage(header.append(details).append(breakdown).append(totalSummary)
                .append(Text.literal("\n")).append(confirmButton)
                .append(Text.literal(" ")).append(cancelButton));
    }

    private boolean processPurchase(ServerPlayerEntity player, PlotType plotType, int quantity, int totalCost) {
        UUID playerUuid = player.getUuid();
        if (CurrencyMod.getEconomyManager().getBalance(playerUuid) < totalCost) {
            player.sendMessage(Text.literal("You don't have enough money to complete this purchase.")
                    .formatted(Formatting.RED));
            pendingPurchases.remove(playerUuid);
            return false;
        }
        int basePrice = plotType.getPurchasePrice();
        CurrencyMod.getEconomyManager().removeBalance(playerUuid, totalCost);
        for (int i = 0; i < quantity; i++) {
            playerPlots.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(new Plot(plotType));
        }
        saveData(CurrencyMod.getServer());

        if (quantity == 1) {
            player.sendMessage(Text.literal("You have purchased a ")
                    .append(Text.literal(plotType.getDisplayName()).formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(" plot for "))
                    .append(Text.literal("$" + totalCost).formatted(Formatting.GOLD))
                    .append(Text.literal("\nDaily tax: "))
                    .append(Text.literal("$" + plotType.getDailyTax()).formatted(Formatting.YELLOW)));
        } else {
            MutableText message = Text.literal("✅ Purchase confirmed! ")
                    .append(Text.literal(quantity + "x ").formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(plotType.getDisplayName()).formatted(Formatting.GREEN, Formatting.BOLD))
                    .append(Text.literal(" plots for "))
                    .append(Text.literal("$" + totalCost).formatted(Formatting.GOLD))
                    .append(Text.literal(" (bulk discount applied)"));
            message.append(Text.literal("\nBreakdown:").formatted(Formatting.WHITE));
            for (int i = 0; i < quantity; i++) {
                int plotPrice = calculatePlotPriceWithDiscount(basePrice, i);
                int discountPercent = Math.min(50, i * 5);
                if (i == 0) {
                    message.append(Text.literal("\n  Plot 1: $" + plotPrice + " (full price)").formatted(Formatting.WHITE));
                } else {
                    message.append(Text.literal("\n  Plot " + (i + 1) + ": $" + plotPrice
                            + " (" + discountPercent + "% off)").formatted(Formatting.WHITE));
                }
            }
            message.append(Text.literal("\nTotal daily tax: "))
                    .append(Text.literal("$" + (quantity * plotType.getDailyTax())).formatted(Formatting.YELLOW));
            player.sendMessage(message);
        }
        return true;
    }

    public boolean confirmPurchase(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        PendingPurchase pending = pendingPurchases.get(playerUuid);
        if (pending == null) {
            player.sendMessage(Text.literal("No pending plot purchase to confirm.").formatted(Formatting.RED));
            return false;
        }
        if (System.currentTimeMillis() - pending.timestamp > PURCHASE_TIMEOUT) {
            pendingPurchases.remove(playerUuid);
            player.sendMessage(Text.literal("Purchase confirmation expired. Please try again.").formatted(Formatting.RED));
            return false;
        }
        pendingPurchases.remove(playerUuid);
        return processPurchase(player, pending.plotType, pending.quantity, pending.totalCost);
    }

    public boolean cancelPurchase(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        PendingPurchase pending = pendingPurchases.remove(playerUuid);
        if (pending == null) {
            player.sendMessage(Text.literal("No pending plot purchase to cancel.").formatted(Formatting.RED));
            return false;
        }
        player.sendMessage(Text.literal("Purchase cancelled.").formatted(Formatting.YELLOW));
        return true;
    }

    /**
     * Legacy command-based sell. Also clears any WorldPlot binding for the removed plot.
     */
    public boolean sellPlot(ServerPlayerEntity player, PlotType plotType) {
        UUID playerUuid = player.getUuid();
        if (!playerPlots.containsKey(playerUuid) || playerPlots.get(playerUuid).isEmpty()) {
            player.sendMessage(Text.literal("You don't own any plots to sell.").formatted(Formatting.RED));
            return false;
        }
        List<Plot> plots = playerPlots.get(playerUuid);
        Optional<Plot> plotOpt = plots.stream().filter(p -> p.getType() == plotType).findFirst();
        if (plotOpt.isEmpty()) {
            player.sendMessage(Text.literal("You don't own any ")
                    .append(Text.literal(plotType.getDisplayName()).formatted(Formatting.GOLD))
                    .append(Text.literal(" plots to sell.").formatted(Formatting.RED)));
            return false;
        }
        Plot plotToSell = plotOpt.get();
        int refundAmount = (int)(plotType.getPurchasePrice() * 0.2);
        CurrencyMod.getEconomyManager().addBalance(playerUuid, refundAmount);
        plots.remove(plotToSell);
        if (plots.isEmpty()) playerPlots.remove(playerUuid);

        // Clear WorldPlot binding if one exists for this specific plot
        for (WorldPlot wp : registeredPlots.values()) {
            if (plotToSell.getId().equals(wp.getPlotId())) {
                wp.setOwnerUuid(null);
                wp.setPlotId(null);
                break;
            }
        }
        saveData(CurrencyMod.getServer());

        player.sendMessage(Text.literal("You have sold your ")
                .append(Text.literal(plotType.getDisplayName()).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" plot for "))
                .append(Text.literal("$" + refundAmount).formatted(Formatting.GOLD))
                .append(Text.literal(" (20% of original price).").formatted(Formatting.GRAY)));
        return true;
    }

    // =========================================================================
    // Read-only queries
    // =========================================================================

    public List<Plot> getPlayerPlots(UUID playerUuid) {
        return playerPlots.getOrDefault(playerUuid, Collections.emptyList());
    }

    public Map<PlotType, Integer> getPlayerPlotCounts(UUID playerUuid) {
        List<Plot> plots = getPlayerPlots(playerUuid);
        Map<PlotType, Integer> counts = new EnumMap<>(PlotType.class);
        for (PlotType type : PlotType.values()) counts.put(type, 0);
        for (Plot plot : plots) counts.merge(plot.getType(), 1, Integer::sum);
        return counts;
    }

    public int getPlayerDailyTax(UUID playerUuid) {
        return calculateTotalTax(getPlayerPlots(playerUuid));
    }

    // =========================================================================
    // Math helpers
    // =========================================================================

    private int calculateTotalTax(List<Plot> plots) {
        return plots.stream().mapToInt(Plot::getDailyTax).sum();
    }

    private int calculatePlotPriceWithDiscount(int basePrice, int plotIndex) {
        if (plotIndex == 0) return basePrice;
        int discountPercent = Math.min(50, plotIndex * 5);
        double multiplier = 1.0 - (discountPercent / 100.0);
        return (int) Math.round(basePrice * multiplier);
    }

    public int calculateBulkPurchaseCost(int basePrice, int quantity) {
        int total = 0;
        for (int i = 0; i < quantity; i++) total += calculatePlotPriceWithDiscount(basePrice, i);
        return total;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    public void loadData(MinecraftServer server) {
        if (server == null) { LOGGER.warn("Cannot load plot data: server is null"); return; }
        File file = server.getRunDirectory().resolve(PLOTS_FILE).toFile();
        if (!file.exists()) { LOGGER.info("Plots file not found; starting fresh."); return; }

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("lastTaxDay"))
                lastTaxDay = root.get("lastTaxDay").getAsLong();

            if (root.has("taxedPlayers")) {
                taxedPlayersToday.clear();
                for (JsonElement e : root.getAsJsonArray("taxedPlayers"))
                    taxedPlayersToday.add(UUID.fromString(e.getAsString()));
            }

            if (root.has("playerPlots")) {
                JsonObject plotsObj = root.getAsJsonObject("playerPlots");
                for (Map.Entry<String, JsonElement> entry : plotsObj.entrySet()) {
                    UUID playerUuid = UUID.fromString(entry.getKey());
                    List<Plot> plots = new ArrayList<>();
                    for (JsonElement elem : entry.getValue().getAsJsonArray()) {
                        JsonObject plotObj = elem.getAsJsonObject();
                        UUID plotId = UUID.fromString(plotObj.get("id").getAsString());
                        PlotType type = PlotType.valueOf(plotObj.get("type").getAsString());
                        long timestamp = plotObj.get("timestamp").getAsLong();
                        plots.add(new Plot(plotId, type, timestamp));
                    }
                    playerPlots.put(playerUuid, plots);
                }
            }

            if (root.has("registeredPlots")) {
                JsonObject regObj = root.getAsJsonObject("registeredPlots");
                for (Map.Entry<String, JsonElement> entry : regObj.entrySet()) {
                    ChunkKey key = ChunkKey.fromString(entry.getKey());
                    if (key == null) continue;
                    JsonObject wpObj = entry.getValue().getAsJsonObject();

                    Set<PlotType> enabledTypes = EnumSet.noneOf(PlotType.class);
                    if (wpObj.has("enabledTypes")) {
                        for (JsonElement e : wpObj.getAsJsonArray("enabledTypes")) {
                            try { enabledTypes.add(PlotType.valueOf(e.getAsString())); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }

                    UUID ownerUuid = (wpObj.has("ownerUuid") && !wpObj.get("ownerUuid").isJsonNull())
                            ? UUID.fromString(wpObj.get("ownerUuid").getAsString()) : null;
                    UUID plotId = (wpObj.has("plotId") && !wpObj.get("plotId").isJsonNull())
                            ? UUID.fromString(wpObj.get("plotId").getAsString()) : null;

                    registeredPlots.put(key, new WorldPlot(key, enabledTypes, ownerUuid, plotId));
                }
            }

            LOGGER.info("Loaded plot data: {} players, {} registered chunks, last tax day: {}",
                    playerPlots.size(), registeredPlots.size(), lastTaxDay);
        } catch (IOException e) {
            LOGGER.error("Failed to load plot data", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing plot data", e);
        }
    }

    public void saveData(MinecraftServer server) {
        if (server == null) { LOGGER.warn("Cannot save plot data: server is null"); return; }
        File file = FileUtil.getServerFile(server, PLOTS_FILE);
        if (file == null) { LOGGER.error("Failed to get plot file path"); return; }

        try {
            JsonObject root = new JsonObject();

            root.addProperty("lastTaxDay", lastTaxDay);

            JsonArray taxedArr = new JsonArray();
            for (UUID uuid : taxedPlayersToday) taxedArr.add(uuid.toString());
            root.add("taxedPlayers", taxedArr);

            JsonObject plotsObj = new JsonObject();
            for (Map.Entry<UUID, List<Plot>> entry : playerPlots.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Plot plot : entry.getValue()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", plot.getId().toString());
                    p.addProperty("type", plot.getType().name());
                    p.addProperty("timestamp", plot.getPurchaseTimestamp());
                    arr.add(p);
                }
                plotsObj.add(entry.getKey().toString(), arr);
            }
            root.add("playerPlots", plotsObj);

            JsonObject regObj = new JsonObject();
            for (Map.Entry<ChunkKey, WorldPlot> entry : registeredPlots.entrySet()) {
                WorldPlot wp = entry.getValue();
                JsonObject wpObj = new JsonObject();

                JsonArray enabledArr = new JsonArray();
                for (PlotType t : wp.getEnabledTypes()) enabledArr.add(t.name());
                wpObj.add("enabledTypes", enabledArr);

                wpObj.add("ownerUuid", wp.getOwnerUuid() != null
                        ? new com.google.gson.JsonPrimitive(wp.getOwnerUuid().toString()) : JsonNull.INSTANCE);
                wpObj.add("plotId", wp.getPlotId() != null
                        ? new com.google.gson.JsonPrimitive(wp.getPlotId().toString()) : JsonNull.INSTANCE);

                regObj.add(entry.getKey().toString(), wpObj);
            }
            root.add("registeredPlots", regObj);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            boolean ok = FileUtil.safeWriteToFile(server, file, gson.toJson(root));
            if (ok) {
                LOGGER.info("Saved plot data: {} players, {} registered chunks",
                        playerPlots.size(), registeredPlots.size());
            } else {
                LOGGER.error("FileUtil.safeWriteToFile returned failure");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save plot data", e);
        }
    }
}
