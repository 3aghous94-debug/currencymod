package net.currencymod.auction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.currencymod.CurrencyMod;
import net.currencymod.economy.EconomyManager;
import net.currencymod.util.FileUtil;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.text.HoverEvent;
import org.jetbrains.annotations.Nullable;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.text.MutableText;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * Manages auctions in the currency mod
 */
public class AuctionManager {
    private static final int MAX_AUCTION_DURATION_MINUTES = 5;
    private static final int MIN_AUCTION_DURATION_MINUTES = 1;
    private static final double MIN_AUCTION_PRICE = 1.0;
    private static final double BID_INCREMENT_PERCENT = 0.05; // 5% minimum increase
    private static final double MIN_BID_INCREMENT = 1.0; // Minimum $1 increase
    private static final String PENDING_ITEMS_FILE = "currency_mod/auction_pending_items.json";
    
    // Add a check interval (in seconds) to manually verify auction end times
    private static final int AUCTION_CHECK_INTERVAL = 5;

    // Reminder intervals in seconds
    private static final int[] REMINDER_INTERVALS = {60, 30, 15, 10, 5};
    
    // Countdown intervals for final seconds
    private static final int[] COUNTDOWN_SECONDS = {5, 4, 3, 2, 1};
    
    private static AuctionManager instance;
    
    private Auction currentAuction;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> endAuctionTask;
    private ScheduledFuture<?> auctionCheckerTask;
    private final Map<Integer, ScheduledFuture<?>> reminderTasks = new HashMap<>();
    private final Map<Integer, ScheduledFuture<?>> countdownTasks = new HashMap<>();
    private MinecraftServer server;
    
    // Map to store items that need to be returned to offline players
    private final Map<UUID, ItemStack> pendingItemReturns = new HashMap<>();
    
    // Track if an auction has been processed to avoid duplicate endings
    private boolean auctionProcessed = false;
    
    // C-03 fix: lock guarding currentAuction, auctionProcessed, and all
    // state-mutating auction operations (createAuction, placeBid, cancelAuction,
    // endAuction, extendAuctionTime). The scheduler thread (endAuctionTask,
    // auctionCheckerTask) and the server thread (placeBid, cancelAuction,
    // processBuyItNow) previously mutated this state without synchronization,
    // causing races where endAuction and placeBid could both run concurrently
    // -- refunding a bidder twice, charging a bidder whose bid was never
    // registered, or delivering the item to the old winner while a new bid's
    // money was also taken.
    //
    // The scheduler-side callbacks are also marshaled to the server thread
    // via server.execute(...) so the lock is only ever acquired from one
    // thread in practice; the synchronized block is belt-and-suspenders for
    // any code path we missed.
    private final Object auctionLock = new Object();
    
    private EconomyManager economyManager;
    private long lastBidTime = 0;
    
    private AuctionManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Start the auction checker task
        startAuctionChecker();
    }
    
    /**
     * Start a background task that periodically checks if auctions should have ended
     */
    private void startAuctionChecker() {
        // Cancel any existing checker task
        if (auctionCheckerTask != null && !auctionCheckerTask.isDone()) {
            auctionCheckerTask.cancel(false);
        }
        
        // Schedule a new checker task that runs every few seconds
        auctionCheckerTask = scheduler.scheduleAtFixedRate(
            this::checkAuctionStatus,
            AUCTION_CHECK_INTERVAL,
            AUCTION_CHECK_INTERVAL,
            TimeUnit.SECONDS
        );
        
        CurrencyMod.LOGGER.info("Started auction checker task with interval: {} seconds", AUCTION_CHECK_INTERVAL);
    }
    
    /**
     * Check if the current auction should have ended
     */
    private void checkAuctionStatus() {
        // Skip if there's no server reference yet
        if (server == null) {
            return;
        }
        
        // C-03 fix: marshal the entire check to the server thread so that
        // currentAuction + auctionProcessed reads/writes never race with
        // placeBid/cancelAuction/endAuction on the server thread. The old
        // code read currentAuction + auctionProcessed on the scheduler thread
        // and then called endAuction() directly on the scheduler thread --
        // racing with any concurrent placeBid on the server thread.
        server.execute(() -> {
            // Check if there's an active auction
            if (currentAuction != null && !auctionProcessed) {
                // Check if the auction should have ended
                if (currentAuction.isEnded()) {
                    CurrencyMod.LOGGER.info("Auction checker detected that auction should have ended (auctionProcessed={})", auctionProcessed);
                    
                    // Double-check that the auction isn't already being processed
                    if (!auctionProcessed) {
                        // End the auction manually
                        try {
                            CurrencyMod.LOGGER.info("Auction checker calling endAuction for item: {}", 
                                currentAuction.getItem().getName().getString());
                            endAuction();
                        } catch (Exception e) {
                            // Log any errors that might occur during auction ending
                            CurrencyMod.LOGGER.error("Error during manual auction end check: ", e);
                            
                            // Force mark the auction as processed to avoid retrying
                            auctionProcessed = true;
                            CurrencyMod.LOGGER.info("Force marked auction as processed due to error");
                        }
                    } else {
                        CurrencyMod.LOGGER.info("Auction checker skipped ending auction because it's already processed");
                    }
                } else {
                    // Log the current status for debugging
                    long timeLeft = currentAuction.getTimeLeft();
                    CurrencyMod.LOGGER.debug("Auction checker: auction active with {} seconds left", timeLeft);
                }
            }
        });
    }
    
    /**
     * Get the singleton instance of the auction manager
     */
    public static AuctionManager getInstance() {
        if (instance == null) {
            instance = new AuctionManager();
        }
        return instance;
    }
    
    /**
     * Set the Minecraft server reference
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
        // Initialize the economy manager
        this.economyManager = CurrencyMod.getEconomyManager();
        // Load any pending items when the server starts
        loadPendingItems(server);
    }
    
    /**
     * Get the current auction
     */
    @Nullable
    public Auction getCurrentAuction() {
        return currentAuction;
    }
    
    /**
     * Create a new auction
     * 
     * @param sellerUuid The UUID of the player selling the item
     * @param item The item being sold
     * @param startingPrice The starting price of the auction
     * @param durationMinutes The duration of the auction in minutes
     * @return true if the auction was created, false otherwise
     */
    public boolean createAuction(UUID sellerUuid, ItemStack item, double startingPrice, int durationMinutes) {
        // C-03 fix: serialize against placeBid/cancelAuction/endAuction.
        synchronized (auctionLock) {
        // Check if there's already an active auction
        if (currentAuction != null && !currentAuction.isEnded()) {
            return false;
        }
        
        // Validate inputs
        if (startingPrice < MIN_AUCTION_PRICE) {
            return false;
        }
        
        // Reset the auction processed flag
        auctionProcessed = false;
        
        // Limit auction duration
        int limitedDuration = Math.min(Math.max(durationMinutes, MIN_AUCTION_DURATION_MINUTES), MAX_AUCTION_DURATION_MINUTES);
        
        // Create the auction
        currentAuction = new Auction(sellerUuid, item, startingPrice, limitedDuration);
        
        // Log auction creation
        CurrencyMod.LOGGER.info("Created auction for player {} with duration {} minutes, item: {} x{}, price: ${}", 
            sellerUuid, limitedDuration, item.getName().getString(), item.getCount(), startingPrice);
        
        // Schedule the end of the auction
        scheduleAuctionEnd(currentAuction, limitedDuration * 60);
        
        // Schedule reminder messages
        scheduleReminders(limitedDuration);
        
        // Get seller's name
        String sellerName = getPlayerNameString(sellerUuid);
        
        // Create the message with hoverable item tooltip
        Text message = Text.literal("🔨 New Auction! ")
            .formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(sellerName)
                .formatted(Formatting.YELLOW))
            .append(Text.literal(" is auctioning ")
                .formatted(Formatting.WHITE))
            .append(createItemComponent(item))
            .append(Text.literal(" starting at ")
                .formatted(Formatting.WHITE))
            .append(Text.literal("$" + String.format("%.2f", startingPrice))
                .formatted(Formatting.GREEN))
            .append(Text.literal(" for ")
                .formatted(Formatting.WHITE))
            .append(Text.literal(durationMinutes + " minute" + (durationMinutes > 1 ? "s" : ""))
                .formatted(Formatting.YELLOW));
        
        // Broadcast the message
        broadcastAuctionMessage(message);
        
        // Display enchantment information in chat if the item is enchanted
        Text enchantInfo = getEnchantmentInfoText(item);
        if (enchantInfo != null) {
            Text enchantMessage = Text.literal("📜 Item Details: ")
                .formatted(Formatting.GOLD)
                .append(enchantInfo);
            broadcastAuctionMessage(enchantMessage);
        }
        
        return true;
        } // synchronized (auctionLock)
    }
    
    /**
     * Schedule the auction to end after the specified duration
     */
    private void scheduleAuctionEnd(Auction auction, int durationSeconds) {
        // Cancel any existing end task first
        if (endAuctionTask != null && !endAuctionTask.isDone() && !endAuctionTask.isCancelled()) {
            CurrencyMod.LOGGER.info("Cancelling existing auction end task");
            endAuctionTask.cancel(false);
        }
        
        // Get time left in seconds based on our updated time format
        long timeLeftSeconds = auction.getTimeLeft();
        
        // Format end time for logging
        long endTimeMillis = System.currentTimeMillis() + (timeLeftSeconds * 1000);
        String endTimeFormatted = new Date(endTimeMillis).toString();
        
        CurrencyMod.LOGGER.info("Scheduling auction end for item '{}' in {} seconds (at {})",
            auction.getItem().getName().getString(),
            timeLeftSeconds,
            endTimeFormatted);
        
        // Schedule the auction to end after the duration
        endAuctionTask = scheduler.schedule(
            () -> {
                try {
                    CurrencyMod.LOGGER.info("Auction end task triggered for: {}", 
                        auction.getItem().getName().getString());
                    
                    // C-03 fix: marshal endAuction to the server thread so it
                    // never races with placeBid/cancelAuction. The old code
                    // called endAuction() directly on the scheduler thread.
                    // server.execute is a no-op (returns immediately) if we
                    // are already on the server thread, so this is safe even
                    // if the scheduler somehow runs on the server thread.
                    server.execute(() -> {
                        try {
                            // Check if the auction is the current one and not already ended
                            if (currentAuction == auction && !auction.isEnded()) {
                                CurrencyMod.LOGGER.info("Ending auction normally via scheduled task");
                                endAuction();
                            } else {
                                CurrencyMod.LOGGER.warn("Auction end task triggered but auction is not current or already ended");
                            }
                        } catch (Exception e) {
                            CurrencyMod.LOGGER.error("Error in auction end task (server thread): ", e);
                            
                            // Make sure to reset the auction state even on error
                            try {
                                resetAuctionState();
                            } catch (Exception ex) {
                                CurrencyMod.LOGGER.error("Failed to reset auction state after error", ex);
                            }
                        }
                    });
                } catch (Exception e) {
                    CurrencyMod.LOGGER.error("Error scheduling auction end task: ", e);
                }
            },
            timeLeftSeconds,
            TimeUnit.SECONDS
        );
        
        CurrencyMod.LOGGER.info("Successfully scheduled auction end task");
    }
    
    /**
     * Schedule reminder messages at specific intervals
     */
    private void scheduleReminders(int durationMinutes) {
        // Cancel any existing reminder tasks
        for (ScheduledFuture<?> task : reminderTasks.values()) {
            if (!task.isDone()) {
                task.cancel(false);
            }
        }
        reminderTasks.clear();
        
        // Cancel any existing countdown tasks
        for (ScheduledFuture<?> task : countdownTasks.values()) {
            if (!task.isDone()) {
                task.cancel(false);
            }
        }
        countdownTasks.clear();
        
        // Convert minutes to seconds
        int durationSeconds = durationMinutes * 60;
        
        // Schedule reminders
        for (int interval : REMINDER_INTERVALS) {
            int secondsBeforeEnd = interval;
            int delaySeconds = durationSeconds - secondsBeforeEnd;
            
            if (delaySeconds > 0) {
                reminderTasks.put(interval, scheduler.schedule(
                    () -> sendReminderMessage(interval),
                    delaySeconds,
                    TimeUnit.SECONDS
                ));
            }
        }
        
        // Schedule countdown for final seconds
        for (int seconds : COUNTDOWN_SECONDS) {
            int delaySeconds = durationSeconds - seconds;
            
            if (delaySeconds > 0) {
                countdownTasks.put(seconds, scheduler.schedule(
                    () -> sendCountdownMessage(seconds),
                    delaySeconds,
                    TimeUnit.SECONDS
                ));
            }
        }
    }
    
    /**
     * Create a Text component for an item with hover tooltip showing details
     * Public method for use by other classes
     * 
     * @param item The item to show
     * @return A Text component with hover tooltip
     */
    public Text createItemHoverTooltip(ItemStack item) {
        return createItemComponent(item);
    }
    
    /**
     * Create a Text component for an item with hover tooltip showing details
     * Private implementation
     * 
     * @param item The item to show
     * @return A Text component with hover tooltip
     */
    private Text createItemComponent(ItemStack item) {
        // Create the base text with the item name
        Text itemText = Text.literal(item.getCount() + "x " + item.getName().getString())
            .formatted(Formatting.AQUA);
        
        // Create tooltip text with item details
        List<Text> tooltip = new ArrayList<>();
        
        // Add item name with proper color
        tooltip.add(item.getName().copy());
        
        // Check if the item is enchanted
        if (item.hasGlint()) {
            tooltip.add(Text.literal("")); // Empty line
            
            // In Minecraft 1.21.1, we can't easily get detailed enchantment info
            // Just indicate that the item is enchanted
            tooltip.add(getEnchantmentInfoText(item));
        }
        
        // Add durability information for damageable items
        if (item.isDamageable()) {
            int maxDurability = item.getMaxDamage();
            int currentDurability = maxDurability - item.getDamage();
            float durabilityPercent = (float) currentDurability / maxDurability * 100;
            
            tooltip.add(Text.literal("")); // Empty line
            tooltip.add(Text.literal(String.format("Durability: %d/%d (%.1f%%)", 
                    currentDurability, maxDurability, durabilityPercent))
                .formatted(durabilityPercent > 50 ? Formatting.GREEN : 
                         durabilityPercent > 25 ? Formatting.YELLOW : Formatting.RED));
        }
        
        // Add custom name note if the item has a custom name
        // Check by comparing the item's name with its default name
        String displayName = item.getName().getString();
        String defaultName = item.getItem().getDefaultStack().getName().getString();
        if (!displayName.equals(defaultName)) {
            tooltip.add(Text.literal("")); // Empty line
            tooltip.add(Text.literal("✏ Custom Name")
                .formatted(Formatting.ITALIC, Formatting.GRAY));
        }
        
        // Create the hover event with all tooltip text
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
            Text.empty().copy().append(joinText(tooltip, Text.literal("\n"))));
        
        // Return the item text with the hover event
        return itemText.copy().styled(style -> style.withHoverEvent(hoverEvent));
    }

    public static ItemEnchantmentsComponent getEnchants(ItemStack stack) {
        // Under the hood this reads the "Enchantments" NBT list and
        // turns it into a Map<Enchantment, Integer>
        return EnchantmentHelper.getEnchantments(stack);
    }
    
    /**
     * Extract enchantment information from an item using simplified approach for 1.21.1
     * @param item The item to extract enchantment info from
     * @return Text component with enchantment information or null if none
     */
    private Text getEnchantmentInfoText(ItemStack item) {
        if (!item.hasGlint()) {
            return null;
        }
        
        // In Minecraft 1.21.1, just provide a simple enchantment indicator without details
        // This avoids API compatibility issues

        ItemEnchantmentsComponent enchantments = getEnchants(item);
        // ask it to write its data into a fresh NbtCompound
        Set<RegistryEntry<Enchantment>> enchSet = enchantments.getEnchantments();
        Map<Enchantment, Integer> enchMap = new HashMap<>();
        for (RegistryEntry<Enchantment> entry : enchSet) {
            enchMap.put(entry.value(), enchantments.getLevel(entry));
        }

        var enchantmentText = Text.literal("✨ Enchanted with ")
                .formatted(Formatting.LIGHT_PURPLE);

        int idx   = 0;
        int total = enchMap.size();

        List<Text> enchantmentTexts = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : enchMap.entrySet()) {
            enchantmentTexts.add(Text.literal(entry.getKey().toString().replace("minecraft:", "").replace("Enchantment ", ""))
                .formatted(Formatting.LIGHT_PURPLE).append(" " + entry.getValue()));
            idx++;
            if (idx < total) {
                enchantmentTexts.add(Text.literal(", "));
            }
            else if (idx == total) {
                enchantmentTexts.add(Text.literal("."));
            }
        }

        for (Text text : enchantmentTexts) {
            enchantmentText.append(text);
        }

        return enchantmentText;
    }
    
    /**
     * Join a list of Text components with a separator
     */
    private Text joinText(List<Text> texts, Text separator) {
        if (texts.isEmpty()) {
            return Text.empty();
        }
        
        Text result = texts.get(0).copy();
        for (int i = 1; i < texts.size(); i++) {
            result = result.copy().append(separator.copy()).append(texts.get(i).copy());
        }
        
        return result;
    }
    
    /**
     * Send a reminder message about the auction ending soon
     */
    private void sendReminderMessage(int secondsLeft) {
        if (server == null || currentAuction == null || currentAuction.isEnded()) {
            return;
        }
        
        // Create the message with hoverable item tooltip
        Text message = Text.literal("⏰ Auction Ending Soon! ")
            .formatted(Formatting.GOLD)
            .append(createItemComponent(currentAuction.getItem()))
            .append(Text.literal(" - Current price: ")
                .formatted(Formatting.WHITE))
            .append(Text.literal("$" + String.format("%.2f", currentAuction.getCurrentPrice()))
                .formatted(Formatting.GREEN, Formatting.BOLD))
            .append(Text.literal(" - ")
                .formatted(Formatting.WHITE))
            .append(Text.literal(secondsLeft + " seconds")
                .formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal(" left!")
                .formatted(Formatting.WHITE));
        
        // Broadcast the message
        server.getPlayerManager().broadcast(message, false);
    }
    
    /**
     * Send a countdown message for the last few seconds
     */
    private void sendCountdownMessage(int secondsLeft) {
        if (server == null || currentAuction == null || currentAuction.isEnded()) {
            return;
        }
        
        // Create the message with hoverable item tooltip
        Text message = Text.literal("⏱ Auction Ending: ")
            .formatted(Formatting.RED)
            .append(Text.literal(String.valueOf(secondsLeft))
                .formatted(Formatting.GOLD, Formatting.BOLD))
            .append(Text.literal(" - Item: ")
                .formatted(Formatting.WHITE))
            .append(createItemComponent(currentAuction.getItem()));
        
        // Broadcast the message
        server.getPlayerManager().broadcast(message, false);
    }
    
    /**
     * End the current auction
     */
    public void endAuction() {
        // C-03 fix: serialize against placeBid/cancelAuction/createAuction.
        synchronized (auctionLock) {
        // Skip if there's no auction or it has already been processed
        if (currentAuction == null || auctionProcessed) {
            CurrencyMod.LOGGER.info("Skipping auction end: no auction or already processed (auctionProcessed={})", 
                auctionProcessed);
            return;
        }
        
        // Mark the auction as processed to prevent duplicate processing
        auctionProcessed = true;
        
        String itemName = currentAuction.getItem().getName().getString();
        int itemCount = currentAuction.getItem().getCount();
        UUID sellerUuid = currentAuction.getSellerUuid();
        String sellerName = getPlayerNameString(sellerUuid);
        
        CurrencyMod.LOGGER.info("Ending auction for {}x {} sold by {} (auctionProcessed now true)", 
            itemCount, itemName, sellerName);
        
        // Cancel all reminder tasks
        for (Map.Entry<Integer, ScheduledFuture<?>> entry : reminderTasks.entrySet()) {
            ScheduledFuture<?> task = entry.getValue();
            if (task != null && !task.isDone() && !task.isCancelled()) {
                CurrencyMod.LOGGER.debug("Cancelling reminder task for {} seconds", entry.getKey());
                task.cancel(false);
            }
        }
        reminderTasks.clear();
        
        // Cancel all countdown tasks
        for (Map.Entry<Integer, ScheduledFuture<?>> entry : countdownTasks.entrySet()) {
            ScheduledFuture<?> task = entry.getValue();
            if (task != null && !task.isDone() && !task.isCancelled()) {
                CurrencyMod.LOGGER.debug("Cancelling countdown task for {} seconds", entry.getKey());
                task.cancel(false);
            }
        }
        countdownTasks.clear();
        
        // Get the highest bid
        Bid highestBid = currentAuction.getCurrentBid();
        
        try {
            if (highestBid != null) {
                // Process the auction with a winner
                UUID buyerUuid = highestBid.getBidderUuid();
                double price = highestBid.getBidAmount();
                String buyerName = getPlayerNameString(buyerUuid);
                
                CurrencyMod.LOGGER.info("Auction ended with winner: {} ({}), price: ${}", 
                    buyerName, buyerUuid, price);
                
                // C-04 fix: credit the seller from the already-escrowed funds.
                // The buyer's balance was deducted (escrowed) at placeBid time
                // (see C-05 fix at line ~911). The escrowed amount is "in the
                // system" but not credited to anyone. endAuction must therefore
                // CREDIT the seller via addBalance(sellerUuid, price) -- it must
                // NOT call transferMoney(buyerUuid, sellerUuid, price), which
                // would deduct 'price' from the buyer a SECOND time. The old
                // code did exactly that: either the buyer had enough left and
                // the seller got paid twice (once from escrow, once from the
                // buyer's remaining balance), or transferMoney returned false
                // and the seller got nothing while the buyer's escrow was
                // permanently destroyed.
                //
                // Composition with C-05: the C-05 fix guarantees that if
                // currentAuction.getCurrentBid() != null (i.e. we are in this
                // winner branch), the bidder's removeBalance succeeded at
                // placeBid time. So addBalance(sellerUuid, price) is always
                // correct here -- the funds are already escrowed.
                //
                // Defensive guard: addBalance itself cannot meaningfully fail
                // (it sets a map value), but if the underlying economy layer
                // ever throws (e.g. persistence error during a future refactor),
                // we still want to return the item to the seller rather than
                // leave the auction in an inconsistent state.
                boolean transferSuccess;
                try {
                    economyManager.addBalance(sellerUuid, price);
                    CurrencyMod.LOGGER.info("Credited seller {} with ${} from escrowed bid funds (C-04 fix)",
                        sellerName, price);
                    transferSuccess = true;
                } catch (Exception creditEx) {
                    CurrencyMod.LOGGER.error("Failed to credit seller {} with ${} from escrow: ",
                        sellerName, price, creditEx);
                    transferSuccess = false;
                }
                
                if (transferSuccess) {
                    CurrencyMod.LOGGER.info("Successfully credited ${} to seller {} from buyer {} escrow", 
                        price, sellerName, buyerName);
                    
                    // Give the item to the buyer
                    ServerPlayerEntity buyer = server.getPlayerManager().getPlayer(buyerUuid);
                    if (buyer != null) {
                        // Buyer is online, give them the item directly
                        boolean gaveItem = giveItemToPlayer(buyer, currentAuction.getItem().copy());
                        
                        if (gaveItem) {
                            CurrencyMod.LOGGER.info("Gave auction item to buyer: {}", buyer.getName().getString());
                            
                            // Send a success message to the buyer
                            buyer.sendMessage(
                                Text.literal("You won the auction for ")
                                    .formatted(Formatting.GREEN)
                                    .append(createItemComponent(currentAuction.getItem()))
                                    .append(Text.literal(" for ")
                                    .formatted(Formatting.GREEN))
                                    .append(Text.literal("$" + String.format("%.2f", price))
                                    .formatted(Formatting.YELLOW, Formatting.BOLD)),
                                false
                            );
                        } else {
                            CurrencyMod.LOGGER.error("Failed to give auction item to buyer: {}", buyer.getName().getString());
                            // Store the item to give to the player later
                            pendingItemReturns.put(buyerUuid, currentAuction.getItem().copy());
                            savePendingItems(server);
                            
                            // Notify the buyer that their item will be delivered later
                            buyer.sendMessage(
                                Text.literal("You won the auction, but your inventory is full! ")
                                    .formatted(Formatting.YELLOW)
                                    .append(Text.literal("The item ")
                                    .formatted(Formatting.GOLD))
                                    .append(createItemComponent(currentAuction.getItem()))
                                    .append(Text.literal(" will be given to you later.")
                                    .formatted(Formatting.GOLD)),
                                false
                            );
                        }
                    } else {
                        // Buyer is offline, store the item to give later
                        CurrencyMod.LOGGER.info("Buyer is offline, storing item for later delivery");
                        pendingItemReturns.put(buyerUuid, currentAuction.getItem().copy());
                        savePendingItems(server);
                    }
                    
                    // Send a message to the seller if they're online
                    ServerPlayerEntity seller = server.getPlayerManager().getPlayer(sellerUuid);
                    if (seller != null) {
                        seller.sendMessage(
                            Text.literal("Your auction for ")
                                .formatted(Formatting.GREEN)
                                .append(createItemComponent(currentAuction.getItem()))
                                .append(Text.literal(" was sold to ")
                                .formatted(Formatting.GREEN))
                                .append(Text.literal(buyerName)
                                .formatted(Formatting.YELLOW))
                                .append(Text.literal(" for ")
                                .formatted(Formatting.GREEN))
                                .append(Text.literal("$" + String.format("%.2f", price))
                                .formatted(Formatting.GOLD, Formatting.BOLD)),
                            false
                        );
                    }
                    
                    // Broadcast a success message
                    broadcastAuctionMessage(Text.literal("Auction ended! ")
                        .formatted(Formatting.GOLD)
                        .append(Text.literal(buyerName)
                        .formatted(Formatting.YELLOW))
                        .append(Text.literal(" won the auction for "))
                        .append(createItemComponent(currentAuction.getItem()))
                        .append(Text.literal(" with a bid of "))
                        .append(Text.literal(String.format("$%.2f", price))
                        .formatted(Formatting.GREEN, Formatting.BOLD)));
                } else {
                    CurrencyMod.LOGGER.error("Failed to transfer funds for auction between {} and {}", 
                        buyerName, sellerName);
                    
                    // Return the item to the seller
                    returnAuctionItemToSeller("The payment could not be processed.");
                    
                    // Broadcast a failure message
                    broadcastAuctionMessage(Text.literal("Auction failed! ")
                        .formatted(Formatting.RED)
                        .append(Text.literal("The payment could not be processed.")));
                }
            } else {
                // No bids were placed
                CurrencyMod.LOGGER.info("Auction ended with no bids");
                
                // Return the item to the seller
                returnAuctionItemToSeller("No bids were placed.");
                
                // Send message to seller if online
                ServerPlayerEntity seller = server.getPlayerManager().getPlayer(sellerUuid);
                if (seller != null) {
                    seller.sendMessage(
                        Text.literal("Your auction for ")
                            .formatted(Formatting.YELLOW)
                            .append(createItemComponent(currentAuction.getItem()))
                            .append(Text.literal(" ended with no bids.")
                            .formatted(Formatting.RED)),
                        false
                    );
                }
                
                // Broadcast an end message
                broadcastAuctionMessage(Text.literal("Auction ended with no bids! ")
                    .formatted(Formatting.GOLD)
                    .append(Text.literal("The item "))
                    .append(createItemComponent(currentAuction.getItem()))
                    .append(Text.literal(" has been returned to the seller.")));
            }
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Critical error during auction end processing: ", e);
            
            // Try to return the item to the seller in case of error
            try {
                returnAuctionItemToSeller("An error occurred during auction processing.");
            } catch (Exception ex) {
                CurrencyMod.LOGGER.error("Failed to return item to seller after auction error", ex);
            }
            
            // Broadcast an error message
            broadcastAuctionMessage(Text.literal("Auction failed due to an error! ")
                .formatted(Formatting.RED)
                .append(Text.literal("The item has been returned to the seller.")));
        } finally {
            // Always reset the auction state, even if there was an error
            resetAuctionState();
        }
        } // synchronized (auctionLock)
    }
    
    /**
     * Get player name from UUID, with fallback to UUID string if not found
     */
    private String getPlayerNameString(UUID uuid) {
        if (server == null) return uuid.toString().substring(0, 8) + "...";
        
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player != null) {
            return player.getName().getString();
        }
        
        // Try to get from user cache
        var userCache = server.getUserCache();
        if (userCache != null) {
            Optional<GameProfile> profileOpt = userCache.getByUuid(uuid);
            if (profileOpt.isPresent()) {
                return profileOpt.get().getName();
            }
        }
        
        // Fallback to shortened UUID
        return uuid.toString().substring(0, 8) + "...";
    }
    
    /**
     * Reset the auction state
     */
    private void resetAuctionState() {
        currentAuction = null;
        auctionProcessed = false;
        
        // Cancel end task if it exists
        if (endAuctionTask != null) {
            if (!endAuctionTask.isDone() && !endAuctionTask.isCancelled()) {
                CurrencyMod.LOGGER.debug("Cancelling auction end task during reset");
                endAuctionTask.cancel(false);
            }
            endAuctionTask = null;
        }
        
        CurrencyMod.LOGGER.info("Auction state has been reset and is ready for the next auction");
    }

    /**
     * Broadcast a message to all players about the auction
     */
    private void broadcastAuctionMessage(Text message) {
        if (server == null) {
            CurrencyMod.LOGGER.warn("Cannot broadcast auction message: server is null");
            return;
        }
        
        try {
            server.getPlayerManager().broadcast(message, false);
            CurrencyMod.LOGGER.debug("Broadcasted auction message: {}", message.getString());
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Failed to broadcast auction message", e);
        }
    }
    
    /**
     * Calculate the minimum bid for the current auction
     * @return The minimum bid required
     */
    private int calculateMinimumBid() {
        if (currentAuction == null) {
            return 0;
        }
        return (int) currentAuction.getMinimumBid();
    }

    /**
     * Place a bid on the current auction
     *
     * @param bidderUuid The UUID of the player placing the bid
     * @param bidAmount The amount of the bid
     * @return true if the bid was placed successfully, false otherwise
     */
    public boolean placeBid(UUID bidderUuid, double bidAmount) {
        // C-03 fix: serialize against endAuction/cancelAuction/createAuction.
        synchronized (auctionLock) {
        // Check if there is an active auction
        if (currentAuction == null) {
            CurrencyMod.LOGGER.info("Bid rejected: No active auction");
            return false;
        }

        // Check if the auction has ended
        if (currentAuction.isEnded()) {
            CurrencyMod.LOGGER.info("Bid rejected: Auction has ended");
            return false;
        }

        // Check if the bid meets the minimum requirement
        int minBid = calculateMinimumBid();
        if (bidAmount < minBid) {
            CurrencyMod.LOGGER.info("Bid rejected: UUID {} bid {} which is less than the minimum bid of {}", 
                bidderUuid, bidAmount, minBid);
            return false;
        }

        // Check if the bidder is also the seller (prevent self-bidding)
        if (bidderUuid.equals(currentAuction.getSellerUuid())) {
            CurrencyMod.LOGGER.info("Bid rejected: Seller cannot bid on their own auction");
            return false;
        }

        // Check if the bidder has enough money
        double playerBalance = economyManager.getBalance(bidderUuid);
        if (playerBalance < bidAmount) {
            CurrencyMod.LOGGER.info("Bid rejected: UUID {} has insufficient funds ({}) to place bid of {}", 
                bidderUuid, playerBalance, bidAmount);
            return false;
        }

        // Implement the anti-sniping feature - if a bid is placed in the last 10 seconds, extend by 10 seconds
        long timeLeft = currentAuction.getTimeLeft(); // Time left in seconds
        if (timeLeft <= 10 && !currentAuction.isBuyItNow()) {
            CurrencyMod.LOGGER.info("Anti-snipe triggered: UUID {} placed bid with {} seconds left", 
                bidderUuid, timeLeft);
            // Call the extend auction time method instead of directly extending the end time
            extendAuctionTime(10);
            
            // Broadcast the time extension to all players
            if (server != null) {
                // Send a global message to all players
                Text extensionMessage = Text.literal("⏰ Auction Extended! ")
                    .formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("10 seconds")
                        .formatted(Formatting.RED))
                    .append(Text.literal(" added due to last-minute bid!")
                        .formatted(Formatting.YELLOW));
                
                broadcastAuctionMessage(extensionMessage);
            }
        }

        // If it's a buy-it-now auction and the bid amount equals or exceeds the buy-it-now price
        if (currentAuction.isBuyItNow() && bidAmount >= currentAuction.getBuyItNowPrice()) {
            // Process as a buy-it-now purchase
            processBuyItNow(bidderUuid, bidAmount);
            return true;
        }

        // Store previous top bidder info BEFORE any state mutation (C-05 fix).
        // We will attempt to deduct from the bidder FIRST and abort cleanly if
        // the deduction fails, so no rollback path is needed.
        UUID previousTopBidderUUID = null;
        double previousBidAmount = 0;
        
        if (currentAuction.getCurrentBid() != null) {
            previousTopBidderUUID = currentAuction.getCurrentBid().getBidderUuid();
            previousBidAmount = currentAuction.getCurrentBid().getBidAmount();
        }

        String bidderName = getPlayerNameString(bidderUuid);

        // Deduct the bid amount from the bidder FIRST (C-05 fix).
        // The pre-check at line 854 is a fast UX rejection path, but it is TOCTOU:
        // a concurrent tax deduction (PlotManager scheduler, see C-01) or service
        // charge can reduce the balance between the pre-check and the deduction.
        // removeBalance returns -1 on insufficient funds; we must check it and
        // abort cleanly WITHOUT mutating currentAuction or refunding the previous
        // bidder. Otherwise the bid would be recorded as the winning bid without
        // the bidder paying anything (free bid), and at auction end the seller
        // would be paid from money that was never deposited.
        double deductResult = economyManager.removeBalance(bidderUuid, bidAmount);
        if (deductResult < 0) {
            CurrencyMod.LOGGER.info(
                "Bid rejected: UUID {} failed escrow deduction of {} (balance dropped below bid " +
                "between pre-check and removeBalance, likely due to concurrent tax/service charge)",
                bidderUuid, bidAmount);
            ServerPlayerEntity bidder = server.getPlayerManager().getPlayer(bidderUuid);
            if (bidder != null) {
                bidder.sendMessage(Text.literal("§6[Auction] §cYour bid of §a" + bidAmount +
                    " §ccould not be placed: insufficient funds at escrow time."));
            }
            return false;
        }
        CurrencyMod.LOGGER.info("Escrowed {} from UUID {} (new balance: {})",
            bidAmount, bidderUuid, deductResult);

        // Deduction succeeded — now it is safe to mutate auction state.
        // Set the new winning bid.
        currentAuction.placeBid(bidderUuid, bidAmount);

        CurrencyMod.LOGGER.info("{} placed a bid of {} on auction of {}", 
            bidderName, bidAmount, currentAuction.getItem().getName().getString());

        // Refund the previous top bidder if there was one (their escrow was taken
        // at their own placeBid time). This refund happens AFTER the new bidder's
        // deduction succeeded, so we never end up in a state where we refunded
        // the previous bidder but failed to take the new bidder's money.
        if (previousTopBidderUUID != null && !previousTopBidderUUID.equals(bidderUuid)) {
            economyManager.addBalance(previousTopBidderUUID, previousBidAmount);
            CurrencyMod.LOGGER.info("Refunded {} currency to previous top bidder", previousBidAmount);
            
            // Notify the previous top bidder if they're online
            ServerPlayerEntity previousBidder = server.getPlayerManager().getPlayer(previousTopBidderUUID);
            if (previousBidder != null) {
                previousBidder.sendMessage(Text.literal("§6[Auction] §cYou have been outbid! §eYour bid of §a" + 
                    previousBidAmount + " §ehas been refunded."));
            }
        }

        // Broadcast the new bid to all players
        if (server != null) {
            String displayName = bidderName;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                player.sendMessage(Text.literal("§6[Auction] §e" + displayName + 
                    " §abid §a" + bidAmount + " §eon §b" + currentAuction.getItem().getName().getString()));
            }
        }

        // Update the last bid time
        lastBidTime = System.currentTimeMillis();
        
        // Create and broadcast the bid message
        Text bidMessage = Text.literal("💰 Bid Placed! ")
            .formatted(Formatting.GREEN)
            .append(Text.literal(bidderName)
                .formatted(Formatting.YELLOW))
            .append(Text.literal(" bid ")
                .formatted(Formatting.WHITE))
            .append(Text.literal("$" + String.format("%.2f", bidAmount))
                .formatted(Formatting.GREEN, Formatting.BOLD))
            .append(Text.literal(" on ")
                .formatted(Formatting.WHITE))
            .append(createItemComponent(currentAuction.getItem()))
            .append(Text.literal("! ")
                .formatted(Formatting.WHITE))
            .append(Text.literal(formatTimeLeft(currentAuction.getTimeLeft()))
                .formatted(Formatting.YELLOW))
            .append(Text.literal(" remaining.")
                .formatted(Formatting.WHITE));
        
        broadcastAuctionMessage(bidMessage);
        
        // If this is the first bid, show detailed enchantment information
        if (currentAuction.getCurrentBid() == null || currentAuction.getCurrentBid().getBidderUuid().equals(bidderUuid)) {
            Text enchantInfo = getEnchantmentInfoText(currentAuction.getItem());
            if (enchantInfo != null) {
                Text enchantMessage = Text.literal("📜 Item Details: ")
                    .formatted(Formatting.GOLD)
                    .append(enchantInfo);
                broadcastAuctionMessage(enchantMessage);
            }
        }
        
        return true;
        } // synchronized (auctionLock)
    }
    
    /**
     * Process a buy-it-now purchase
     * 
     * @param bidderUuid The UUID of the player making the purchase
     * @param amount The amount paid
     */
    private void processBuyItNow(UUID bidderUuid, double amount) {
        CurrencyMod.LOGGER.info("Processing buy-it-now purchase from UUID {}", bidderUuid);
        // Just end the auction immediately - the endAuction method will handle the rest
        endAuction();
    }
    
    /**
     * Extend the auction time by the specified number of seconds
     * 
     * @param seconds The number of seconds to extend the auction by
     */
    private void extendAuctionTime(int seconds) {
        if (currentAuction == null || currentAuction.isEnded()) {
            CurrencyMod.LOGGER.info("Cannot extend auction time: no auction or already ended");
            return;
        }
        
        // Extend the auction's end time
        currentAuction.extendEndTime(seconds);
        CurrencyMod.LOGGER.info("Extended auction by {} seconds", seconds);
        
        // Cancel the current end task
        if (endAuctionTask != null && !endAuctionTask.isDone()) {
            CurrencyMod.LOGGER.info("Cancelling existing auction end task for time extension");
            endAuctionTask.cancel(false);
        }
        
        // Get the new time left
        long newTimeLeft = currentAuction.getTimeLeft();
        
        // Schedule the new end task
        endAuctionTask = scheduler.schedule(
            this::endAuction,
            newTimeLeft,
            TimeUnit.SECONDS
        );
        
        CurrencyMod.LOGGER.info("Auction extended to prevent sniping - new end time is {} seconds from now", newTimeLeft);
    }
    
    /**
     * Cancel the current auction
     * 
     * @param playerUuid The UUID of the player canceling the auction
     * @return true if the auction was canceled, false otherwise
     */
    public boolean cancelAuction(UUID playerUuid) {
        // C-03 fix: serialize against endAuction/placeBid/createAuction.
        synchronized (auctionLock) {
        // Check if there's an active auction
        if (currentAuction == null || currentAuction.isEnded()) {
            CurrencyMod.LOGGER.info("Cannot cancel auction: no auction or already ended");
            return false;
        }
        
        // Check if the player is the seller
        if (!currentAuction.getSellerUuid().equals(playerUuid)) {
            CurrencyMod.LOGGER.info("Cannot cancel auction: player {} is not the seller", playerUuid);
            return false;
        }
        
        CurrencyMod.LOGGER.info("Cancelling auction for item {} by player {}", 
            currentAuction.getItem().getName().getString(), playerUuid);
        
        // Cancel the auction end task
        if (endAuctionTask != null && !endAuctionTask.isDone()) {
            CurrencyMod.LOGGER.info("Cancelling auction end task during auction cancellation");
            endAuctionTask.cancel(false);
        }
        
        // Cancel all reminder tasks
        for (ScheduledFuture<?> task : reminderTasks.values()) {
            if (!task.isDone()) {
                task.cancel(false);
            }
        }
        reminderTasks.clear();
        
        // Cancel all countdown tasks
        for (ScheduledFuture<?> task : countdownTasks.values()) {
            if (!task.isDone()) {
                task.cancel(false);
            }
        }
        countdownTasks.clear();
        
        // Mark the auction as ended and processed to prevent duplicate processing
        currentAuction.markAsEnded();
        auctionProcessed = true;
        
        CurrencyMod.LOGGER.info("Auction cancelled by player: {} (auctionProcessed flag set to true)", playerUuid);

        // Refund the current highest bidder (C-06 fix).
        // Bidder's balance was escrowed (deducted) at placeBid time (line 923).
        // Without this refund, a malicious seller could list a junk item, wait
        // for a victim to bid, then /auction cancel to permanently destroy the
        // bidder's escrowed money while keeping the item.
        // v3.0 regression notes:
        //  - Guard against null currentBid (no-bidder case).
        //  - Use UUID-keyed addBalance so offline bidders still get their refund.
        //  - Self-bid is already rejected at placeBid (line 847), but the guard
        //    here is still safe if that invariant ever changes.
        Bid currentHighestBid = currentAuction.getCurrentBid();
        if (currentHighestBid != null) {
            UUID bidderUuid = currentHighestBid.getBidderUuid();
            double refundAmount = currentHighestBid.getBidAmount();
            economyManager.addBalance(bidderUuid, refundAmount);
            CurrencyMod.LOGGER.info("Refunded ${} to bidder {} after auction cancellation by seller {}",
                refundAmount, bidderUuid, playerUuid);

            // Notify the bidder if they're online (same pattern as placeBid refund at line 915-919).
            ServerPlayerEntity bidder = server.getPlayerManager().getPlayer(bidderUuid);
            if (bidder != null) {
                bidder.sendMessage(Text.literal("§6[Auction] §cThe auction you bid on was canceled by the seller. " +
                    "§eYour bid of §a" + refundAmount + " §ehas been refunded."));
            }
        }

        // Return the item to the seller if they're online
        ServerPlayerEntity seller = server.getPlayerManager().getPlayer(playerUuid);
        if (seller != null) {
            CurrencyMod.LOGGER.info("Returning auction item to online seller: {}", seller.getName().getString());
            giveItemToPlayer(seller, currentAuction.getItem().copy());
            
            // Notify the player
            seller.sendMessage(Text.literal("You canceled your auction. The item has been returned to you.")
                .formatted(Formatting.GOLD), false);
        } else {
            // Store the item for when the seller logs back in
            CurrencyMod.LOGGER.info("Seller is offline, storing item for later delivery");
            pendingItemReturns.put(playerUuid, currentAuction.getItem().copy());
            savePendingItems(server); // Save pending items immediately
        }
        
        // Notify all players about the cancellation with hover tooltip
        String sellerName = seller != null ? seller.getName().getString() : "Offline Player";
        
        Text message = Text.literal("🔨 Auction Canceled! ")
            .formatted(Formatting.RED, Formatting.BOLD)
            .append(Text.literal(sellerName)
                .formatted(Formatting.YELLOW))
            .append(Text.literal(" canceled their auction for ")
                .formatted(Formatting.WHITE))
            .append(createItemComponent(currentAuction.getItem()));
        
        server.getPlayerManager().broadcast(message, false);
        
        CurrencyMod.LOGGER.info("About to reset auction state after cancellation");
        resetAuctionState();
        CurrencyMod.LOGGER.info("Auction successfully cancelled and reset");
        return true;
        } // synchronized (auctionLock)
    }
    
    /**
     * Give an item to a player, dropping it on the ground if inventory is full
     * @return true if the item was successfully given, false if there was an error
     */
    private boolean giveItemToPlayer(ServerPlayerEntity player, ItemStack itemStack) {
        if (player == null || itemStack == null || itemStack.isEmpty()) {
            CurrencyMod.LOGGER.error("Cannot give item: player is null or item is empty");
            return false;
        }
        
        // First make sure we're using a copy of the item to avoid reference issues
        ItemStack itemToGive = itemStack.copy();
        
        // Log detailed information for debugging
        CurrencyMod.LOGGER.info("Giving item to player {}: {} x{}", 
            player.getName().getString(), 
            itemToGive.getName().getString(), 
            itemToGive.getCount());
        
        try {    
            // Try to insert the item into the player's inventory
            boolean success = player.getInventory().insertStack(itemToGive);
            
            // Fallback: If inventory insertion fails, try to drop it in their inventory directly
            if (!success || !itemToGive.isEmpty()) {
                CurrencyMod.LOGGER.info("First inventory insertion attempt failed, trying direct drop");
                
                // Drop the remaining item at the player's feet
                player.dropItem(itemToGive, false);
                player.sendMessage(Text.literal("Your inventory was full, so the item was dropped at your feet.")
                    .formatted(Formatting.YELLOW), false);
                
                CurrencyMod.LOGGER.info("Player {} inventory was full, dropped item at their feet", 
                    player.getName().getString());
            } else {
                CurrencyMod.LOGGER.info("Successfully added item to player {}'s inventory", 
                    player.getName().getString());
                
                // Send confirmation message to player
                player.sendMessage(Text.literal("You received: ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(itemStack.getCount() + "x " + itemStack.getName().getString())
                    .formatted(Formatting.AQUA)), false);
            }
            return true;
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Error giving item to player: ", e);
            
            // Last resort - try to drop the item at their feet with a different method
            try {
                player.getInventory().offerOrDrop(itemToGive);
                CurrencyMod.LOGGER.info("Used fallback offerOrDrop method to give item to player");
                return true;
            } catch (Exception ex) {
                CurrencyMod.LOGGER.error("Critical error: Could not give item to player by any means", ex);
                return false;
            }
        }
    }
    
    /**
     * Format time left in a readable format
     */
    public static String formatTimeLeft(long secondsLeft) {
        if (secondsLeft <= 0) {
            return "ending now";
        }
        
        long minutes = secondsLeft / 60;
        long seconds = secondsLeft % 60;
        
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    /**
     * Auction class representing an active auction
     */
    public class Auction {
        private final UUID sellerUuid;
        private final ItemStack item;
        private final double startingPrice;
        private Bid currentBid;
        private final long startTime;
        private long endTime; // Changed from final to allow extension
        private boolean ended;
        
        /**
         * Create a new auction
         * 
         * @param sellerUuid The UUID of the player selling the item
         * @param item The item being sold
         * @param startingPrice The starting price of the auction
         * @param durationMinutes The duration of the auction in minutes
         */
        public Auction(UUID sellerUuid, ItemStack item, double startingPrice, int durationMinutes) {
            this.sellerUuid = sellerUuid;
            this.item = item.copy();
            this.startingPrice = startingPrice;
            this.currentBid = null;
            
            // Store times in milliseconds format for consistency
            this.startTime = System.currentTimeMillis();
            this.endTime = this.startTime + (durationMinutes * 60 * 1000); // Convert minutes to milliseconds
            this.ended = false;
            
            CurrencyMod.LOGGER.info("Created new auction: seller={}, item={}, price={}, duration={}min, endTime={}", 
                sellerUuid, item.getName().getString(), startingPrice, durationMinutes, 
                new Date(endTime));
        }
        
        /**
         * Get the UUID of the player selling the item
         */
        public UUID getSellerUuid() {
            return sellerUuid;
        }
        
        /**
         * Get the item being sold
         */
        public ItemStack getItem() {
            return item;
        }
        
        /**
         * Get the starting price of the auction
         */
        public double getStartingPrice() {
            return startingPrice;
        }
        
        /**
         * Get the current price of the auction
         */
        public double getCurrentPrice() {
            return currentBid != null ? currentBid.getBidAmount() : startingPrice;
        }
        
        /**
         * Get the minimum bid required
         */
        public double getMinimumBid() {
            if (currentBid == null) {
                return startingPrice;
            }
            
            // Calculate minimum increase
            double minIncrease = Math.max(
                MIN_BID_INCREMENT,
                currentBid.getBidAmount() * BID_INCREMENT_PERCENT
            );
            
            // Calculate the minimum bid
            return Math.ceil((currentBid.getBidAmount() + minIncrease) * 100) / 100; // Round up to nearest cent
        }
        
        /**
         * Get the current highest bid
         */
        @Nullable
        public Bid getCurrentBid() {
            return currentBid;
        }
        
        /**
         * Place a bid on the auction
         * 
         * @param bidderUuid The UUID of the player placing the bid
         * @param bidAmount The amount of the bid
         */
        public void placeBid(UUID bidderUuid, double bidAmount) {
            this.currentBid = new Bid(bidderUuid, bidAmount);
        }
        
        /**
         * Get the end time of the auction
         */
        public long getEndTime() {
            return endTime;
        }
        
        /**
         * Check if the auction has ended
         * @return true if the auction has ended, false otherwise
         */
        public boolean isEnded() {
            // If already marked as ended, return true
            if (ended) {
                return true;
            }
            
            // Check current time against end time - ensure proper time unit comparison
            long currentTimeMs = System.currentTimeMillis();
            
            // Convert endTime to milliseconds if needed (if it's stored as seconds)
            long endTimeMs = endTime;
            // Check if endTime is in seconds (if it's much smaller than a typical millisecond timestamp)
            if (endTime < 100000000000L) {
                endTimeMs = endTime * 1000; // Convert seconds to milliseconds
            }
            
            boolean timeEnded = currentTimeMs >= endTimeMs;
            
            // Debug logging
            if (timeEnded && !ended) {
                CurrencyMod.LOGGER.info("Auction time check: currentTimeMs={}, endTimeMs={}, diff={}ms", 
                    currentTimeMs, endTimeMs, (currentTimeMs - endTimeMs));
            }
            
            // For standard auctions, only end when time is up
            // Do not end the auction just because there's a bid
            return timeEnded;
        }
        
        /**
         * Mark the auction as ended
         */
        public void markAsEnded() {
            this.ended = true;
            CurrencyMod.LOGGER.info("Auction manually marked as ended");
        }
        
        /**
         * Get the time left in the auction in seconds
         * @return the time left in seconds, or 0 if the auction has ended
         */
        public long getTimeLeft() {
            if (ended) {
                return 0;
            }
            
            long currentTimeMs = System.currentTimeMillis();
            
            // Convert endTime to milliseconds if needed (consistent with isEnded)
            long endTimeMs = endTime;
            if (endTime < 100000000000L) {
                endTimeMs = endTime * 1000; // Convert seconds to milliseconds
            }
            
            long timeLeftMs = Math.max(0, endTimeMs - currentTimeMs);
            
            // If time is up but auction not marked as ended yet
            if (timeLeftMs == 0 && !ended) {
                CurrencyMod.LOGGER.info("Time is up for auction but not yet marked as ended");
            }
            
            return timeLeftMs / 1000; // Return time left in seconds
        }
        
        /**
         * Extend the end time of the auction by the specified number of seconds
         * 
         * @param seconds The number of seconds to extend the auction by
         */
        public void extendEndTime(int seconds) {
            // Determine if endTime is in seconds or milliseconds
            if (endTime < 100000000000L) {
                // endTime is in seconds, add seconds directly
                this.endTime += seconds;
                CurrencyMod.LOGGER.info("Auction end time extended by {} seconds to {} (seconds format)", 
                    seconds, new Date(endTime * 1000));
            } else {
                // endTime is already in milliseconds, convert seconds to milliseconds
                this.endTime += (seconds * 1000L);
                CurrencyMod.LOGGER.info("Auction end time extended by {} seconds to {} (milliseconds format)", 
                    seconds, new Date(endTime));
            }
        }
        
        /**
         * Check if this is a buy-it-now auction
         * @return true if this is a buy-it-now auction, false otherwise
         */
        public boolean isBuyItNow() {
            // Default implementation - you can modify based on your requirements
            return false;
        }
        
        /**
         * Get the buy-it-now price
         * @return The buy-it-now price
         */
        public double getBuyItNowPrice() {
            // Default implementation - you can modify based on your requirements
            return Double.MAX_VALUE; // Very high value to prevent triggering buy-it-now
        }
    }
    
    /**
     * Bid class representing a bid in an auction
     */
    public class Bid {
        private final UUID bidderUuid;
        private final double bidAmount;
        private final long timestamp;
        
        /**
         * Create a new bid
         * 
         * @param bidderUuid The UUID of the player placing the bid
         * @param bidAmount The amount of the bid
         */
        public Bid(UUID bidderUuid, double bidAmount) {
            this.bidderUuid = bidderUuid;
            this.bidAmount = bidAmount;
            this.timestamp = Instant.now().getEpochSecond();
        }
        
        /**
         * Get the UUID of the player placing the bid
         */
        public UUID getBidderUuid() {
            return bidderUuid;
        }
        
        /**
         * Get the amount of the bid
         */
        public double getBidAmount() {
            return bidAmount;
        }
        
        /**
         * Get the timestamp of the bid
         */
        public long getTimestamp() {
            return timestamp;
        }
    }
    
    /**
     * Check if a player has any pending items to receive and give them if they do
     * 
     * @param player The player that has joined the server
     */
    public void checkPendingItems(ServerPlayerEntity player) {
        UUID playerUuid = player.getUuid();
        
        // Check if the player has any pending items
        ItemStack pendingItem = pendingItemReturns.get(playerUuid);
        if (pendingItem != null) {
            // Log for debugging
            CurrencyMod.LOGGER.info("Found pending item for player {}: {} x{}", 
                player.getName().getString(), 
                pendingItem.getName().getString(), 
                pendingItem.getCount());
            
            // Wait a moment to ensure the player is fully loaded
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // Ignore interruption
            }
            
            // Give the item to the player
            giveItemToPlayer(player, pendingItem);
            
            // Remove from pending map
            pendingItemReturns.remove(playerUuid);
            
            // Save the updated pending items
            savePendingItems(server);
            
            // Send message to the player with hover tooltip
            player.sendMessage(
                Text.literal("You have received an item from an auction: ")
                    .formatted(Formatting.GOLD)
                    .append(createItemComponent(pendingItem)),
                false
            );
            
            CurrencyMod.LOGGER.info("Delivered pending auction item to player {} upon login", player.getName().getString());
        }
    }
    
    /**
     * Save pending items to a file
     */
    public void savePendingItems(MinecraftServer server) {
        if (server == null) {
            CurrencyMod.LOGGER.error("Cannot save pending items: server is null");
            return;
        }
        
        // Log current pending items for debugging
        CurrencyMod.LOGGER.info("Saving {} pending auction items", pendingItemReturns.size());
        for (Map.Entry<UUID, ItemStack> entry : pendingItemReturns.entrySet()) {
            CurrencyMod.LOGGER.info("Pending item for {}: {} x{}", 
                entry.getKey(), entry.getValue().getName().getString(), entry.getValue().getCount());
        }
        
        // Use FileUtil to get the file path
        File pendingItemsFile = FileUtil.getServerFile(server, PENDING_ITEMS_FILE);
        if (pendingItemsFile == null) {
            CurrencyMod.LOGGER.error("Failed to get auction pending items file path");
            return;
        }
        
        try {
            JsonObject rootObject = new JsonObject();
            
            for (Map.Entry<UUID, ItemStack> entry : pendingItemReturns.entrySet()) {
                UUID playerUuid = entry.getKey();
                ItemStack itemStack = entry.getValue();
                
                if (itemStack != null && !itemStack.isEmpty()) {
                    // Use a more detailed approach - store item type, count and display name
                    JsonObject itemObject = new JsonObject();
                    // C-07 fix: the old code used itemStack.getItem().toString(),
                    // which is NOT a valid Identifier (it returns either the
                    // Item class's toString override -- typically a translated
                    // display name like "Diamond" -- or the default
                    // Object.toString() like "net.minecraft.item.Item@hashcode").
                    // Either way, the load side could never parse it back into
                    // the correct Item, which is why the load-side fell back to
                    // diamonds for every entry. Use Registries.ITEM.getId(...)
                    // instead, which returns a proper Identifier like
                    // "minecraft:diamond".
                    itemObject.addProperty("itemId", Registries.ITEM.getId(itemStack.getItem()).toString());
                    itemObject.addProperty("count", itemStack.getCount());
                    itemObject.addProperty("displayName", itemStack.getName().getString());
                    
                    rootObject.add(playerUuid.toString(), itemObject);
                }
            }
            
            // Convert to JSON string
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonContent = gson.toJson(rootObject);
            
            // Write to file using FileUtil
            boolean success = FileUtil.safeWriteToFile(server, pendingItemsFile, jsonContent);
            
            if (success) {
                CurrencyMod.LOGGER.info("Successfully saved {} pending auction items", pendingItemReturns.size());
            } else {
                CurrencyMod.LOGGER.error("Failed to save pending auction items - FileUtil operation returned failure");
            }
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Failed to save pending auction items", e);
        }
    }
    
    /**
     * C-07 migration helper: recover an ItemStack from a displayName string
     * when the saved itemId field is missing or invalid (e.g. files saved
     * with the buggy getItem().toString() format). Per the audit's v3.0
     * verification notes:
     *   1. Iterate Registries.ITEM.stream() and match by item.getName()
     *      .getString() (works for vanilla items only).
     *   2. Try Registries.ITEM.get(Identifier.of("minecraft:" + displayName
     *      .toLowerCase())) as a heuristic.
     *   3. If still no match, return ItemStack.EMPTY -- do NOT substitute
     *      diamonds. The caller logs the displayName + playerUuid for
     *      manual recovery.
     *
     * @param displayName The display name to match against (e.g. "Diamond")
     * @param count The stack count to apply to the recovered item
     * @return The recovered ItemStack, or ItemStack.EMPTY if no match
     */
    private ItemStack recoverItemByDisplayName(String displayName, int count) {
        if (displayName == null || displayName.isEmpty() || "Unknown Item".equals(displayName)) {
            return ItemStack.EMPTY;
        }
        
        // Strategy 1: iterate the item registry and match by display name.
        // Works for vanilla items; custom modded items registered after this
        // build will also be found if their getName() matches.
        try {
            for (Item item : Registries.ITEM.stream().toList()) {
                try {
                    if (displayName.equals(item.getName().getString())) {
                        return new ItemStack(item, count);
                    }
                } catch (Exception ignored) {
                    // Some items may throw on getName() in edge cases; skip them.
                }
            }
        } catch (Exception e) {
            CurrencyMod.LOGGER.warn("C-07 migration: error iterating item registry for displayName recovery: {}", e.getMessage());
        }
        
        // Strategy 2: heuristic -- try "minecraft:" + displayName.toLowerCase()
        // as an Identifier. Handles common cases like "Diamond" -> minecraft:diamond.
        try {
            Identifier heuristic = Identifier.of("minecraft:" + displayName.toLowerCase().replace(' ', '_'));
            Item resolved = Registries.ITEM.get(heuristic);
            if (resolved != null && resolved != Items.AIR) {
                CurrencyMod.LOGGER.info("C-07 migration: heuristic recovery succeeded for displayName='{}' -> {}",
                    displayName, heuristic);
                return new ItemStack(resolved, count);
            }
        } catch (Exception ignored) {
            // Identifier.of may throw on invalid characters; fall through to EMPTY.
        }
        
        // Strategy 3: no match. Return EMPTY (caller will log + skip).
        CurrencyMod.LOGGER.warn("C-07 migration: could not recover item for displayName='{}'; returning EMPTY", displayName);
        return ItemStack.EMPTY;
    }
    
    /**
     * Load pending items from a file
     */
    public void loadPendingItems(MinecraftServer server) {
        if (server == null) {
            CurrencyMod.LOGGER.error("Cannot load pending items: server is null");
            return;
        }
        
        File worldDir = server.getRunDirectory().toFile();
        File pendingItemsFile = new File(worldDir, PENDING_ITEMS_FILE);
        
        // Ensure parent directory exists
        pendingItemsFile.getParentFile().mkdirs();
        
        if (!pendingItemsFile.exists()) {
            CurrencyMod.LOGGER.info("No pending auction items file found");
            return;
        }
        
        try (FileReader reader = new FileReader(pendingItemsFile)) {
            Gson gson = new GsonBuilder().create();
            JsonObject rootObject = gson.fromJson(reader, JsonObject.class);
            pendingItemReturns.clear();
            
            for (Map.Entry<String, JsonElement> entry : rootObject.entrySet()) {
                try {
                    UUID playerUuid = UUID.fromString(entry.getKey());
                    
                    // C-07 fix: the old code defaulted to
                    // `new ItemStack(Items.DIAMOND, 64)` for EVERY entry and
                    // never read the itemId field. A player with a pending
                    // return of 1 dirt block would instead receive 64 diamonds.
                    // We now resolve the item via Registries.ITEM.get(
                    // Identifier.of(itemId)) and fall back to ItemStack.EMPTY
                    // (NOT diamond, NOT air) if the item cannot be resolved.
                    // ItemStack.EMPTY is skipped on delivery (giveItemToPlayer
                    // checks isEmpty()), so the player simply doesn't receive
                    // an item rather than receiving the wrong one.
                    ItemStack itemStack = ItemStack.EMPTY;
                    String displayName = "Unknown Item";
                    int count = 1;
                    
                    if (entry.getValue().isJsonObject()) {
                        JsonObject itemObject = entry.getValue().getAsJsonObject();
                        
                        if (itemObject.has("count")) {
                            count = itemObject.get("count").getAsInt();
                        }
                        
                        if (itemObject.has("displayName")) {
                            displayName = itemObject.get("displayName").getAsString();
                        }
                        
                        if (itemObject.has("itemId")) {
                            String itemIdStr = itemObject.get("itemId").getAsString();
                            // Try direct Identifier parse (handles new format
                            // "minecraft:diamond" and bare names that
                            // Identifier.of will normalize).
                            try {
                                Identifier itemId = Identifier.of(itemIdStr);
                                Item resolved = Registries.ITEM.get(itemId);
                                if (resolved != null && resolved != Items.AIR) {
                                    itemStack = new ItemStack(resolved, count);
                                } else {
                                    CurrencyMod.LOGGER.warn(
                                        "C-07 migration: itemId '{}' resolved to null/air for player {} (displayName={}); skipping entry",
                                        itemIdStr, playerUuid, displayName);
                                }
                            } catch (Exception idEx) {
                                // itemId is not a valid Identifier -- likely
                                // an old-format file saved with the buggy
                                // getItem().toString(). Try migration heuristics.
                                CurrencyMod.LOGGER.warn(
                                    "C-07 migration: invalid itemId '{}' for player {} (displayName={}); attempting displayName-based recovery",
                                    itemIdStr, playerUuid, displayName);
                                itemStack = recoverItemByDisplayName(displayName, count);
                            }
                        } else {
                            // No itemId field at all (very old format). Try
                            // displayName-based recovery.
                            CurrencyMod.LOGGER.warn(
                                "C-07 migration: no itemId field for player {} (displayName={}); attempting displayName-based recovery",
                                playerUuid, displayName);
                            itemStack = recoverItemByDisplayName(displayName, count);
                        }
                        
                        CurrencyMod.LOGGER.info("Loaded item for player {}: {} x{} (resolved={})",
                            playerUuid, displayName, count,
                            itemStack.isEmpty() ? "EMPTY" : Registries.ITEM.getId(itemStack.getItem()));
                    }
                    
                    // Only insert if we successfully resolved an item. Empty
                    // stacks are skipped -- the player gets nothing rather
                    // than the wrong item.
                    if (!itemStack.isEmpty()) {
                        pendingItemReturns.put(playerUuid, itemStack);
                    } else {
                        CurrencyMod.LOGGER.warn(
                            "C-07: skipping pending item return for player {} -- item could not be resolved (displayName={})",
                            playerUuid, displayName);
                    }
                    
                } catch (Exception e) {
                    CurrencyMod.LOGGER.error("Failed to load pending item for player " + entry.getKey(), e);
                }
            }
            
            CurrencyMod.LOGGER.info("Loaded {} pending auction items", pendingItemReturns.size());
        } catch (Exception e) {
            CurrencyMod.LOGGER.error("Failed to load pending auction items", e);
        }
    }
    
    /**
     * Returns the auction item to the seller with a reason message
     * 
     * @param reason The reason why the item is being returned
     */
    private void returnAuctionItemToSeller(String reason) {
        if (currentAuction == null) {
            CurrencyMod.LOGGER.warn("Attempted to return auction item but no auction exists");
            return;
        }
        
        UUID sellerUuid = currentAuction.getSellerUuid();
        ItemStack itemStack = currentAuction.getItem().copy();
        String itemName = itemStack.getName().getString();
        
        CurrencyMod.LOGGER.info("Returning auction item {} to seller {}: {}", 
            itemName, sellerUuid, reason);
        
        // Try to give the item to the seller if they're online
        ServerPlayerEntity seller = server.getPlayerManager().getPlayer(sellerUuid);
        if (seller != null) {
            // Seller is online, give them the item directly
            boolean gaveItem = giveItemToPlayer(seller, itemStack);
            
            if (gaveItem) {
                CurrencyMod.LOGGER.info("Returned auction item to seller: {}", seller.getName().getString());
                
                // Notify the seller with hover tooltip
                seller.sendMessage(Text.literal("Your auction for ")
                    .append(createItemComponent(itemStack))
                    .append(Text.literal(" was returned: "))
                    .append(Text.literal(reason).formatted(Formatting.RED)));
            } else {
                CurrencyMod.LOGGER.error("Failed to return auction item to seller: {}", seller.getName().getString());
                
                // Store the item to give to the player later
                pendingItemReturns.put(sellerUuid, itemStack);
                savePendingItems(server);
                
                // Notify the seller about the pending item with hover tooltip
                seller.sendMessage(Text.literal("Your inventory is full! Your auction item ")
                    .append(createItemComponent(itemStack))
                    .append(Text.literal(" will be returned when you have space.")));
            }
        } else {
            // Seller is offline, store the item to give later
            CurrencyMod.LOGGER.info("Seller is offline, storing item for later delivery");
            pendingItemReturns.put(sellerUuid, itemStack);
            savePendingItems(server);
        }
    }
} 