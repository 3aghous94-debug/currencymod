package net.currencymod.shop;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that detects shop signs and processes them
 */
public class ShopDetector {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("CurrencyMod/ShopDetector");
    
    // Store processed signs to avoid re-processing them
    private static final Map<String, Set<BlockPos>> processedSigns = new HashMap<>();
    
    // Store signs with their creators, used for assigning ownership correctly
    private static final Map<BlockPos, UUID> signCreators = new HashMap<>();
    
    // Flag to indicate we're in the initial scan after server/world load
    private static boolean isInitialScan = true;
    
    // Last process tick to avoid hammering the server
    private static long lastProcessTick = 0;
    
    // Pattern to match buy shop signs: [Buy] on the first line
    private static final Pattern BUY_SHOP_PATTERN = Pattern.compile("\\[Buy\\]");
    
    // Pattern to match sell shop signs: [Sell] on the first line
    private static final Pattern SELL_SHOP_PATTERN = Pattern.compile("\\[Sell\\]");
    
    // Pattern to match admin buy shop signs: [AdminBuy] on the first line
    private static final Pattern ADMIN_BUY_SHOP_PATTERN = Pattern.compile("\\[AdminBuy\\]");
    
    // Pattern to match admin sell shop signs: [AdminSell] on the first line
    private static final Pattern ADMIN_SELL_SHOP_PATTERN = Pattern.compile("\\[AdminSell\\]");
    
    // Pattern to match a price: $<price>
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$(\\d+(\\.\\d+)?)");
    
    // Pattern to match a quantity: just a number
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d+)");
    
    /**
     * Register the shop detector
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ShopDetector::onServerTick);
        LOGGER.info("Registered shop detector");
    }
    
    /**
     * Record a player interacting with a sign, for correct ownership assignment
     * This is called from ShopSignHandler when a player interacts with a sign
     * @param pos The position of the sign
     * @param player The player interacting with the sign
     */
    public static void recordSignPlacement(BlockPos pos, PlayerEntity player) {
        if (player != null) {
            signCreators.put(pos, player.getUuid());
            LOGGER.info("Recorded player {} as owner of sign at {}", player.getName().getString(), pos);
        }
    }
    
    /**
     * Get the UUID of the player who created a sign, if known
     * @param pos The position of the sign
     * @return The UUID of the creator, or null if unknown
     */
    public static UUID getSignCreator(BlockPos pos) {
        return signCreators.get(pos);
    }
    
    /**
     * Check if we know who created a sign
     * @param pos The position of the sign
     * @return True if we know who created the sign
     */
    public static boolean hasSignCreator(BlockPos pos) {
        return signCreators.containsKey(pos);
    }
    
    /**
     * Remove a sign from the processed signs set when a shop is removed
     * This ensures a new shop can be created at the same position later
     * @param worldId The world dimension ID
     * @param pos The position of the sign
     */
    public static void untrackShopSign(String worldId, BlockPos pos) {
        // Remove from the processed signs set
        Set<BlockPos> worldProcessedSigns = processedSigns.get(worldId);
        if (worldProcessedSigns != null) {
            boolean removed = worldProcessedSigns.remove(pos);
            if (removed) {
                LOGGER.info("Untracked shop sign at {} in world {}", pos, worldId);
            }
        }
        
        // Also remove from the signCreators map
        signCreators.remove(pos);
    }
    
    /**
     * Method called on each server tick to check for shop signs
     */
    private static void onServerTick(MinecraftServer server) {
        long currentTick = server.getTicks();
        
        // Only process every 20 ticks (1 second) to avoid performance impact
        if (currentTick - lastProcessTick < 20) {
            return;
        }
        
        lastProcessTick = currentTick;
        
        // Process each world
        int signsChecked = 0;
        int shopsCreated = 0;
        
        for (ServerWorld world : server.getWorlds()) {
            String worldId = ShopManager.getWorldId(world);
            
            // Get or create the set of processed signs for this world
            Set<BlockPos> worldProcessedSigns = processedSigns.computeIfAbsent(worldId, k -> new HashSet<>());
            
            // Get the closest player for sign ownership (useful for single player)
            ServerPlayerEntity closestPlayer = null;
            if (!world.getPlayers().isEmpty()) {
                closestPlayer = world.getPlayers().get(0);
            }

            // Process chunks around spawn
            int radius = 8;
            BlockPos spawnPos = world.getSpawnPos();
            ChunkPos spawnChunkPos = new ChunkPos(spawnPos);
            
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    ChunkPos chunkPos = new ChunkPos(spawnChunkPos.x + x, spawnChunkPos.z + z);
                    
                    // Skip unloaded chunks
                    if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                        continue;
                    }
                    
                    WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
                    if (chunk == null) continue;
                    
                    // Process block entities in the chunk
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof SignBlockEntity signBlockEntity) {
                            BlockPos pos = signBlockEntity.getPos();
                            signsChecked++;
                            
                            // Skip signs we've already processed
                            if (worldProcessedSigns.contains(pos)) {
                                continue;
                            }
                            
                            // Check if this is a shop sign
                            SignBlockEntity sign = (SignBlockEntity) blockEntity;
                            String firstLine = sign.getFrontText().getMessage(0, false).getString();
                            
                            // Check if it's a regular or admin shop
                            boolean isBuyShop = BUY_SHOP_PATTERN.matcher(firstLine).matches();
                            boolean isSellShop = SELL_SHOP_PATTERN.matcher(firstLine).matches();
                            boolean isAdminBuyShop = ADMIN_BUY_SHOP_PATTERN.matcher(firstLine).matches();
                            boolean isAdminSellShop = ADMIN_SELL_SHOP_PATTERN.matcher(firstLine).matches();
                            
                            boolean isShopSign = isBuyShop || isSellShop || isAdminBuyShop || isAdminSellShop;
                            boolean isAdminShop = isAdminBuyShop || isAdminSellShop;
                            boolean isBuyType = isBuyShop || isAdminBuyShop;
                            
                            if (isShopSign) {
                                // For regular shops, check if sign is attached to a chest
                                // Admin shops don't need a chest
                                BlockPos chestPos = null;
                                
                                if (!isAdminShop) {
                                    chestPos = findAttachedChest(world, signBlockEntity);
                                    
                                    if (chestPos == null) {
                                        // Not attached to a chest, mark as processed but don't create shop
                                        worldProcessedSigns.add(pos);
                                        LOGGER.info("Found shop sign at {} but it's not attached to a chest", pos);
                                        continue;
                                    }
                                }
                                
                                // Parse shop data from sign
                                ShopInfo shopInfo = parseShopSign(sign);
                                if (shopInfo == null) {
                                    worldProcessedSigns.add(pos);
                                    LOGGER.warn("Could not parse shop sign at {}", pos);
                                    continue;
                                }
                                
                                // Check if the shop already exists in the database
                                ShopManager shopManager = ((ShopAccess) world).getShopManager();
                                ShopData existingShop = shopManager.getShop(worldId, pos);
                                
                                // If the shop already exists, just mark it as processed and continue
                                if (existingShop != null) {
                                    worldProcessedSigns.add(pos);
                                    continue;
                                }
                                
                                // For admin shops, only process if this is a server with command blocks enabled
                                // or if we're in creative/single-player mode (as OPs would be detected)
                                if (isAdminShop && 
                                    !(server.areCommandBlocksEnabled() ||
                                      server.getCurrentPlayerCount() <= 1)) {
                                    worldProcessedSigns.add(pos);
                                    LOGGER.info("Skipping admin shop creation as server may not be in admin/creative mode at {}", pos);
                                    continue;
                                }
                                
                                // Determine shop owner 
                                UUID ownerUuid;
                                
                                // First check if we've recorded this sign being interacted with by a specific player
                                if (hasSignCreator(pos)) {
                                    ownerUuid = getSignCreator(pos);
                                    LOGGER.info("Using recorded owner {} for shop at {}", ownerUuid, pos);
                                }
                                // In single player, use the only player
                                else if (server.getCurrentPlayerCount() == 1 && !server.getPlayerManager().getPlayerList().isEmpty()) {
                                    ownerUuid = server.getPlayerManager().getPlayerList().get(0).getUuid();
                                    LOGGER.info("Single player mode - assigning owner {} for shop at {}", ownerUuid, pos);
                                }
                                // For multiplayer during world load, defer creation until a player interacts with it
                                else if (isInitialScan && server.getCurrentPlayerCount() > 1) {
                                    // Skip shop creation during initial scan in multiplayer to avoid wrong owner
                                    worldProcessedSigns.add(pos);
                                    LOGGER.info("Skipping shop creation during initial scan in multiplayer for {}", pos);
                                    continue;
                                }
                                // Try to find the closest player to the sign as owner (only during active play)
                                else if (!isInitialScan) {
                                    // Find closest player to the sign
                                    double closestDistance = Double.MAX_VALUE;
                                    ServerPlayerEntity shopOwner = null;
                                    
                                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                                        if (p.getWorld().equals(world)) {
                                            double distance = p.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
                                            if (distance < closestDistance) {
                                                closestDistance = distance;
                                                shopOwner = p;
                                            }
                                        }
                                    }
                                    
                                    if (shopOwner != null && closestDistance < 100) { // Within ~10 blocks
                                        ownerUuid = shopOwner.getUuid();
                                        // Record this player as the sign creator for future reference
                                        signCreators.put(pos, ownerUuid);
                                        LOGGER.info("Found closest player {} at distance {} for shop at {}", 
                                            shopOwner.getName().getString(), Math.sqrt(closestDistance), pos);
                                    } else {
                                        // Skip shop creation if we can't determine owner
                                        worldProcessedSigns.add(pos);
                                        LOGGER.info("No nearby player found for shop at {}, skipping creation", pos);
                                        continue;
                                    }
                                }
                                else {
                                    // Fallback - skip shop creation
                                    worldProcessedSigns.add(pos);
                                    LOGGER.info("Cannot determine owner for shop at {}, skipping creation", pos);
                                    continue;
                                }
                                
                                // Create the shop with the determined owner
                                // C-08 fix: for admin shops, verify the
                                // determined owner has OP permission level 2+.
                                // The old code only checked the server-level
                                // gate (areCommandBlocksEnabled() ||
                                // getCurrentPlayerCount() <= 1), which is true
                                // on any singleplayer world AND on any server
                                // where command blocks are enabled (common for
                                // survival servers). A non-OP player could
                                // place an [AdminBuy]/[AdminSell] sign and the
                                // background scanner would auto-create an
                                // unlimited-stock, unlimited-funds shop owned
                                // by them -- an infinite-money sink.
                                //
                                // We check AFTER owner determination so we
                                // have the UUID to look up. If the owner is
                                // offline (signCreators returned a stale UUID,
                                // or single-player loaded a world whose host
                                // is offline), we cannot check permission --
                                // in that case, skip creation rather than
                                // trust the recorded owner. Single-player
                                // mode (getCurrentPlayerCount() == 1) is the
                                // exception: the host is always OP-equivalent.
                                if (isAdminShop && server.getCurrentPlayerCount() > 1) {
                                    ServerPlayerEntity ownerPlayer = server.getPlayerManager().getPlayer(ownerUuid);
                                    if (ownerPlayer == null || !ownerPlayer.hasPermissionLevel(2)) {
                                        worldProcessedSigns.add(pos);
                                        LOGGER.warn("C-08: refusing to auto-create admin shop at {} for player {} " +
                                            "(offline or insufficient permission level); skipping. If this is a " +
                                            "legitimate admin shop, an OP must right-click the sign to claim it.",
                                            pos, ownerUuid);
                                        continue;
                                    }
                                }
                                
                                ShopData shopData = createShop(world, pos, chestPos, ownerUuid, isBuyType, 
                                    shopInfo.itemName, shopInfo.quantity, shopInfo.price, isAdminShop);
                                
                                if (shopData != null) {
                                    // Mark as processed
                                    worldProcessedSigns.add(pos);
                                    shopsCreated++;
                                    
                                    // Only notify players for shops created during normal gameplay, not during initial scan
                                    if (!isInitialScan) {
                                        // Notify the shop owner
                                        notifyNearbyPlayers(world, pos, ownerUuid, isBuyType, isAdminShop);
                                        
                                        String shopTypeStr = isAdminShop ? 
                                            (isBuyType ? "AdminBuy" : "AdminSell") : 
                                            (isBuyType ? "Buy" : "Sell");
                                            
                                        LOGGER.info("Created a new {} shop at {} with owner {}", 
                                            shopTypeStr, pos, ownerUuid);
                                    } else {
                                        String shopTypeStr = isAdminShop ? 
                                            (isBuyType ? "AdminBuy" : "AdminSell") : 
                                            (isBuyType ? "Buy" : "Sell");
                                            
                                        LOGGER.info("Silently created a {} shop at {} with owner {} during initial scan", 
                                            shopTypeStr, pos, ownerUuid);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // After initial scan completes, clear the flag
        if (isInitialScan) {
            isInitialScan = false;
            LOGGER.info("Initial shop scan complete - detected shops will now trigger notifications");
        }
        
        if (signsChecked > 0 || shopsCreated > 0) {
            LOGGER.debug("Processed {} signs, created {} shops", signsChecked, shopsCreated);
        }
    }
    
    /**
     * Helper class to hold parsed shop information
     */
    private static class ShopInfo {
        String itemName;
        int quantity;
        double price;
        
        ShopInfo(String itemName, int quantity, double price) {
            this.itemName = itemName;
            this.quantity = quantity;
            this.price = price;
        }
    }
    
    /**
     * Parse a shop sign to extract item, quantity and price
     */
    private static ShopInfo parseShopSign(SignBlockEntity sign) {
        try {
            String itemLine = sign.getFrontText().getMessage(1, false).getString();
            String quantityLine = sign.getFrontText().getMessage(2, false).getString();
            String priceLine = sign.getFrontText().getMessage(3, false).getString();
            
            // Parse the quantity
            int quantity = 1;
            Matcher quantityMatcher = QUANTITY_PATTERN.matcher(quantityLine);
            if (quantityMatcher.find()) {
                quantity = Integer.parseInt(quantityMatcher.group(1));
            } else {
                LOGGER.warn("No quantity found in: {}", quantityLine);
                return null;
            }
            
            // Parse the price
            double price = 0.0;
            Matcher priceMatcher = PRICE_PATTERN.matcher(priceLine);
            if (priceMatcher.find()) {
                price = Double.parseDouble(priceMatcher.group(1));
            } else {
                LOGGER.warn("No price found in: {}", priceLine);
                return null;
            }
            
            return new ShopInfo(itemLine, quantity, price);
        } catch (Exception e) {
            LOGGER.error("Error parsing shop sign: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract service tag from shop sign first line
     */
    private static String extractServiceTag(String firstLine) {
        if (firstLine == null) return null;
        String trimmed = firstLine.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        String[] parts = inner.split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        String base = parts[0];
        String tag = parts[1];
        if (!(base.equalsIgnoreCase("buy") || base.equalsIgnoreCase("sell") || 
              base.equalsIgnoreCase("adminbuy") || base.equalsIgnoreCase("adminsell"))) {
            return null;
        }
        if (tag.length() < 1 || tag.length() > 3) {
            return null;
        }
        // Ensure tag is letters only
        if (!tag.matches("(?i)[A-Z]{1,3}")) {
            return null;
        }
        return tag.toUpperCase();
    }
    
    /**
     * Find the chest that a sign is attached to
     */
    private static BlockPos findAttachedChest(ServerWorld world, SignBlockEntity sign) {
        BlockPos signPos = sign.getPos();
        BlockState signState = world.getBlockState(signPos);
        
        // Check if this is a wall sign (attached to another block)
        if (signState.getBlock() instanceof WallSignBlock) {
            Direction facing = signState.get(WallSignBlock.FACING);
            BlockPos attachedPos = signPos.offset(facing.getOpposite());
            
            // Check if the block it's attached to is a chest
            BlockState attachedBlock = world.getBlockState(attachedPos);
            if (attachedBlock.getBlock() instanceof ChestBlock) {
                return attachedPos;
            }
        } else {
            // For standing signs, check the block below
            BlockPos belowPos = signPos.down();
            BlockState belowBlock = world.getBlockState(belowPos);
            if (belowBlock.getBlock() instanceof ChestBlock) {
                return belowPos;
            }
        }
        
        return null; // No chest found
    }
    
    /**
     * Create a shop and return the shop data
     */
    private static ShopData createShop(ServerWorld world, BlockPos signPos, BlockPos chestPos, 
                                UUID playerUuid, boolean isBuyShop, 
                                String itemName, int quantity, double price, boolean isAdminShop) {
        try {
            // Get the shop manager
            ShopManager shopManager = ((ShopAccess) world).getShopManager();
            if (shopManager == null) {
                LOGGER.error("Shop manager is null for world: {}", world.getRegistryKey().getValue());
                return null;
            }
            
            // Check if shop already exists at this location
            String worldId = ShopManager.getWorldId(world);
            ShopData existingShop = shopManager.getShop(worldId, signPos);
            if (existingShop != null) {
                // Shop already exists, return the existing shop
                return existingShop;
            }
            
            // Extract service tag from sign
            BlockEntity blockEntity = world.getBlockEntity(signPos);
            String serviceTag = null;
            if (blockEntity instanceof SignBlockEntity signEntity) {
                String firstLine = signEntity.getFrontText().getMessage(0, false).getString().trim();
                serviceTag = extractServiceTag(firstLine);
            }
            
            // Create the shop with all web sync data
            ShopData shopData = shopManager.createShop(worldId, signPos, playerUuid, price, null, 
                                                       isBuyShop, isAdminShop, itemName, quantity, serviceTag);
            
            if (shopData != null) {
                String shopTypeStr = isAdminShop ? 
                    (isBuyShop ? "AdminBuy" : "AdminSell") : 
                    (isBuyShop ? "Buy" : "Sell");
                    
                LOGGER.info("Created shop at {}: {} x{} @ ${}, owner={}, type={}, serviceTag={}", 
                    signPos, itemName, quantity, price, playerUuid, shopTypeStr, serviceTag);
                return shopData;
            } else {
                LOGGER.warn("Failed to create shop at {}", signPos);
                return null;
            }
        } catch (Exception e) {
            LOGGER.error("Error creating shop: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Notify the shop owner about a shop creation
     */
    private static void notifyNearbyPlayers(ServerWorld world, BlockPos pos, UUID ownerUuid, boolean isBuyShop, boolean isAdminShop) {
        // Find the shop owner if they're online
        PlayerEntity owner = null;
        for (PlayerEntity player : world.getPlayers()) {
            if (player.getUuid().equals(ownerUuid)) {
                owner = player;
                break;
            }
        }
        
        // If shop owner is not online, no notification is needed
        if (owner == null) {
            return;
        }
        
        // Only notify the shop owner
        String shopType = isAdminShop ? 
            (isBuyShop ? "Admin Buy" : "Admin Sell") : 
            (isBuyShop ? "Buy" : "Sell");
        
        // Create a message with a decorative header
        Text headerText = Text.literal("✓ ").formatted(Formatting.GREEN)
            .append(Text.literal("Shop Created").formatted(Formatting.GOLD, Formatting.BOLD));
            
        // Main message with shop type
        Text message = Text.literal("You created a new ")
            .append(Text.literal(shopType).formatted(isBuyShop ? Formatting.GREEN : Formatting.BLUE))
            .append(Text.literal(" shop!"));
            
        // Send formatted messages
        owner.sendMessage(headerText, false);
        owner.sendMessage(message, false);
        owner.sendMessage(Text.literal("Right-click to transact with the shop.")
            .formatted(Formatting.YELLOW), false);
        
        // Add extra message for admin shops
        if (isAdminShop) {
            owner.sendMessage(Text.literal("Admin shops have unlimited inventory and funds.")
                .formatted(Formatting.GOLD), false);
        }
    }
} 