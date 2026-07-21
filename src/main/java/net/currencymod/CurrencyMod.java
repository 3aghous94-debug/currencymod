package net.currencymod;

import net.currencymod.command.AuctionCommand;
import net.currencymod.command.BalanceCommand;
import net.currencymod.command.BaltopCommand;
import net.currencymod.command.BidCommand;
import net.currencymod.command.JobCommand;
import net.currencymod.command.PayCommand;
import net.currencymod.command.TradeCommand;
import net.currencymod.command.MarketCommand;
import net.currencymod.command.PlotsCommand;
import net.currencymod.command.AdminMoneyCommand;
import net.currencymod.command.TaxCommand;
import net.currencymod.economy.DailyLoginRewardManager;
import net.currencymod.economy.EconomyManager;
import net.currencymod.economy.OfflinePaymentManager;
import net.currencymod.jobs.JobManager;
import net.currencymod.jobs.MarketplaceHandler;
import net.currencymod.jobs.MarketplaceManager;
import net.currencymod.plots.PlotManager;
import net.currencymod.shop.ShopAccess;
import net.currencymod.shop.ShopDetector;
import net.currencymod.shop.ShopInteractionHandler;
import net.currencymod.shop.ShopManager;
import net.currencymod.shop.ShopSignHandler;
import net.currencymod.shop.ShopTransactionManager;
import net.currencymod.shop.ShopSignBreakHandler;
import net.currencymod.trade.TradeManager;
import net.currencymod.auction.AuctionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.currencymod.data.DataManager;
import net.currencymod.data.WebSyncManager;
import net.currencymod.command.DataCommand;
import net.currencymod.command.ModStatusCommand;
import net.currencymod.command.TestDataCommand;
import java.util.UUID;
import net.currencymod.config.ModConfig;
import net.currencymod.command.ConfigCommand;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.currencymod.command.AdminBoosterCommand;
import net.currencymod.command.ServiceCommand;
import net.currencymod.command.CurrencyGuiCommand;
import net.currencymod.config.GuiPreferenceManager;
import net.currencymod.services.ServiceManager;
import net.currencymod.services.ServiceNotificationManager;

public class CurrencyMod implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {
    public static final String MOD_ID = "currencymod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static EconomyManager economyManager;
    private static MinecraftServer server;
    
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Currency Mod");
        
        // Register commands
        CommandRegistrationCallback.EVENT.register(BalanceCommand::register);
        CommandRegistrationCallback.EVENT.register(PayCommand::register);
        CommandRegistrationCallback.EVENT.register(BaltopCommand::register);
        CommandRegistrationCallback.EVENT.register(TradeCommand::register);
        CommandRegistrationCallback.EVENT.register(AuctionCommand::register);
        CommandRegistrationCallback.EVENT.register(BidCommand::register);
        CommandRegistrationCallback.EVENT.register(JobCommand::register);
        CommandRegistrationCallback.EVENT.register(AdminMoneyCommand::register);
        CommandRegistrationCallback.EVENT.register(PlotsCommand::register);
        CommandRegistrationCallback.EVENT.register(TaxCommand::register);
        CommandRegistrationCallback.EVENT.register(MarketCommand::register);
        CommandRegistrationCallback.EVENT.register(DataCommand::register);
        CommandRegistrationCallback.EVENT.register(TestDataCommand::register);
        CommandRegistrationCallback.EVENT.register(ModStatusCommand::register);
        CommandRegistrationCallback.EVENT.register(ConfigCommand::register);
        CommandRegistrationCallback.EVENT.register(AdminBoosterCommand::register);
        CommandRegistrationCallback.EVENT.register(ServiceCommand::register);
        CommandRegistrationCallback.EVENT.register(CurrencyGuiCommand::register);
        
        // Create the economy manager instance
        economyManager = new EconomyManager();
        
        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Server starting, storing reference");
            CurrencyMod.server = server;
            
            // Load configuration from file
            LOGGER.info("Loading mod configuration - calling ModConfig.getInstance().load()");
            try {
                ModConfig.getInstance().load(server);
                LOGGER.info("ModConfig.load() completed");
            } catch (Exception e) {
                LOGGER.error("Error loading configuration: {}", e.getMessage(), e);
            }
            
            // Reset tracking variables for a clean state
            ShopManager.resetTracking();
            
            // Initialize various managers with server instance
            AuctionManager.getInstance().setServer(server);
            DataManager.getInstance().initialize(server);
            
            // Initialize web sync manager
            WebSyncManager.getInstance().initialize(server);
            
            economyManager.loadData(server);
            
            // Load shop data for each world
            for (ServerWorld world : server.getWorlds()) {
                ShopManager shopManager = ((ShopAccess) world).getShopManager();
                if (shopManager != null) {
                    shopManager.loadData(server);
                }
            }
            
            // Load transaction data
            ShopTransactionManager.getInstance().loadData(server);
            
            // Load pending auction items
            AuctionManager.getInstance().loadPendingItems(server);
            
            // N2-C-02 fix: recover any orphaned auction from a mid-auction crash.
            // MUST run AFTER economyManager.loadData (line above, ~119) so the
            // bidder-escrow refund via addBalance writes to the loaded economy
            // map, AND AFTER loadPendingItems so the seller's item-return is
            // appended to the already-loaded pendingItemReturns map. The
            // recovery refunds the bidder (if any) and queues the item for
            // return to the seller; see AuctionManager.recoverOrphanedAuction.
            AuctionManager.getInstance().recoverOrphanedAuction();
            
            // Load offline payments data
            OfflinePaymentManager.getInstance().loadOfflinePayments(server);
            
            // Initialize plot manager
            PlotManager.getInstance().initialize(server);
            
            // Load service data
            ServiceManager.getInstance().loadData(server);
            ServiceNotificationManager.getInstance().loadNotifications(server);

            // Load GUI preferences
            GuiPreferenceManager.getInstance().load(server);

            // Set up daily service charges
            setupDailyServiceCharges();
            
            // Set up periodic cleanup of shop transactions (every hour)
            setupTransactionCleanup();
        });
        
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, saving data");
            
            // Shutdown web sync manager
            WebSyncManager.getInstance().shutdown();
            
            // Let the data manager handle the shutdown
            DataManager dataManager = DataManager.getInstance();
            dataManager.onShutdown();
            
            // Shutdown the trade manager
            TradeManager.getInstance().shutdown();
            
            // Clear the server reference
            CurrencyMod.server = null;
        });
        
        // Register world load/unload events for shop data
        ServerWorldEvents.LOAD.register((server, world) -> {
            ShopManager shopManager = ((ShopAccess) world).getShopManager();
            if (shopManager != null) {
                shopManager.loadData(server);
            }
        });
        
        // Add unload event handler to ensure data is saved when worlds unload
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            // Skip save if we're shutting down (handled centrally by DataManager)
            if (DataManager.getInstance().isShuttingDown()) {
                LOGGER.info("World {} unloaded during shutdown, skipping save", 
                    world.getRegistryKey().getValue());
                return;
            }
            
            ShopManager shopManager = ((ShopAccess) world).getShopManager();
            if (shopManager != null) {
                LOGGER.info("Saving shop data for world {} due to unload", 
                    world.getRegistryKey().getValue());
                shopManager.saveData(server);
            }
        });
        
        // Register player join callback for session tracking
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
            
            // Record player login in transaction manager for offline transactions
            ShopTransactionManager transactionManager = ShopTransactionManager.getInstance();
            transactionManager.recordPlayerLogin(playerUuid);
            
            // Send shop transaction summary to the player if they have any
            transactionManager.checkOfflineTransactions(player);
            
            // Check for pending auction items
            AuctionManager.getInstance().checkPendingItems(player);
            
            // Check for offline payments
            OfflinePaymentManager.getInstance().checkOfflinePayments(player);
            
            // Check tax status
            PlotManager.getInstance().checkPlayerTaxStatus(player);

            // Deliver any pending service provider notifications
            ServiceNotificationManager.getInstance().deliverNotifications(player);
            
            // Check for daily login reward
            DailyLoginRewardManager.getInstance().checkDailyLoginReward(player);
            
            // Check and award retrospective job skips
            JobManager.getInstance().checkAndAwardRetrospectiveSkips(player);
            
            LOGGER.info("Player joined: {}", player.getName().getString());
        });
        
        // Register shop detection systems
        ShopDetector.register();
        ShopSignHandler.register();
        ShopInteractionHandler.register();
        ShopSignBreakHandler.register();
        
        // Register marketplace handler for packet handling
        MarketplaceHandler.register();
        
        // Initialize marketplace manager
        MarketplaceManager.getInstance();
        
        LOGGER.info("Currency Mod initialized successfully!");
    }
    
    @Override
    @Environment(EnvType.CLIENT)
    public void onInitializeClient() {
        LOGGER.info("Currency Mod client initialized");
    }
    
    @Override
    @Environment(EnvType.SERVER)
    public void onInitializeServer() {
        LOGGER.info("CurrencyMod initializing on dedicated server");
        // Load config on server initialization
        ModConfig.getInstance();
    }
    
    public static EconomyManager getEconomyManager() {
        return economyManager;
    }
    
    public static MinecraftServer getServer() {
        return server;
    }
    
    /**
     * Set up periodic cleanup of shop transactions (once per hour)
     */
    private void setupTransactionCleanup() {
        // Initialize a counter for ticks
        final int[] tickCounter = {0};
        final int TICKS_PER_HOUR = 20 * 60 * 60; // 1 hour in ticks (20tps * 60s * 60m)
        
        // Register an end-of-tick event handler
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Increment the counter
            tickCounter[0]++;
            
            // Check if an hour has passed (72000 ticks at 20 ticks per second)
            if (tickCounter[0] >= TICKS_PER_HOUR) {
                // Reset the counter
                tickCounter[0] = 0;
                
                // Run the cleanup
                LOGGER.info("Running scheduled shop transaction cleanup");
                ShopTransactionManager.getInstance().cleanupAllTransactions();
                
                // Save the updated transactions to disk
                ShopTransactionManager.getInstance().saveData(server);
            }
        });
        
        LOGGER.info("Set up periodic shop transaction cleanup (every {} ticks)", TICKS_PER_HOUR);
    }
    
    /**
     * Set up daily service charges (once per Minecraft day)
     */
    private void setupDailyServiceCharges() {
        // Track the last day we processed charges
        final long[] lastProcessedDay = {-1};
        
        // Register an end-of-tick event handler
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server == null || server.getOverworld() == null) {
                return;
            }
            
            long currentDay = server.getOverworld().getTimeOfDay() / 24000L;
            
            // Check if a new day has started
            if (currentDay != lastProcessedDay[0]) {
                lastProcessedDay[0] = currentDay;
                
                // Process daily charges for all online subscribers
                ServiceManager.getInstance().processDailyCharges(server);
                
                LOGGER.debug("Processed daily service charges for day {}", currentDay);
            }
        });
        
        LOGGER.info("Set up daily service charge processing");
    }
} 