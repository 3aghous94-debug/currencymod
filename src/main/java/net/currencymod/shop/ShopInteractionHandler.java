package net.currencymod.shop;

import net.currencymod.CurrencyMod;
import net.currencymod.economy.EconomyManager;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.currencymod.services.ServiceManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles shop transactions when players interact with shop signs
 */
public class ShopInteractionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("CurrencyMod/ShopInteractionHandler");
    
    // Cache for item name to item mappings
    private static final Map<String, Item> itemNameCache = new HashMap<>();
    
    // Pattern to match quantity: just a number
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d+)");
    
    // Pattern to match "quantity : price" format
    private static final Pattern QUANTITY_PRICE_PATTERN = Pattern.compile("(\\d+)\\s*:\\s*\\$?(\\d+(?:\\.\\d+)?)");
    
    /**
     * Register the shop interaction handler
     */
    public static void register() {
        UseBlockCallback.EVENT.register(ShopInteractionHandler::onUseBlock);
        LOGGER.info("Registered shop interaction handler");
    }
    
    /**
     * Handle block interaction events for shop transactions
     */
    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        // Only process on server side
        if (world.isClient() || !(world instanceof ServerWorld serverWorld)) {
            return ActionResult.PASS;
        }
        
        BlockPos pos = hitResult.getBlockPos();
        BlockState state = world.getBlockState(pos);
        
        // Check if we're interacting with a sign (but not editing it)
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity signBlockEntity)) {
            return ActionResult.PASS;
        }
        
        // Get shop data from the shop manager
        ShopManager shopManager = ((ShopAccess) world).getShopManager();
        if (shopManager == null) {
            return ActionResult.PASS;
        }
        
        String worldId = ShopManager.getWorldId(world);
        ShopData shopData = shopManager.getShop(worldId, pos);
        
        // If this is not a shop sign, allow default behavior
        if (shopData == null) {
            return ActionResult.PASS;
        }
        
        LOGGER.info("Shop sign interaction at {} by {}", pos, player.getName().getString());
        
        // Check if this is an admin shop
        boolean isAdminShop = shopData.isAdminShop();
        
        // This is a shop sign! Handle shop logic
        BlockPos chestPos = findAttachedChest(world, pos, state);
        
        // Regular shops require a chest, but admin shops don't
        if (chestPos == null && !isAdminShop) {
            Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                .append(Text.literal("Chest Missing").formatted(Formatting.RED, Formatting.BOLD));
                
            Text detailsText = Text.literal("This shop is no longer connected to a chest and has been removed.");
                
            player.sendMessage(errorText, false);
            player.sendMessage(detailsText, false);
            
            boolean removed = removeShop(shopManager, worldId, pos);
            if (removed) {
                // Also remove from shop detector tracking
                ShopDetector.untrackShopSign(worldId, pos);
                LOGGER.info("Removed shop at {} because chest is missing", pos);
            } else {
                LOGGER.warn("Failed to remove shop at {} despite missing chest", pos);
            }
            return ActionResult.SUCCESS;
        }
        
        // For regular shops, get the chest entity; admin shops don't need one
        ChestBlockEntity chestEntity = null;
        if (!isAdminShop) {
            chestEntity = (ChestBlockEntity) world.getBlockEntity(chestPos);
            if (chestEntity == null) {
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Invalid Chest").formatted(Formatting.RED, Formatting.BOLD));
                    
                player.sendMessage(errorText, false);
                return ActionResult.SUCCESS;
            }
        }
        
        // Parse shop sign to get item details
        SignBlockEntity sign = (SignBlockEntity) blockEntity;

        // Check for service subscription restriction via first line, e.g. [Buy TAG] or [Sell TAG]
        String firstLineRaw = sign.getFrontText().getMessage(0, false).getString().trim();
        String requiredServiceTag = extractServiceTag(firstLineRaw);
        
        // If shop is missing web sync data, try to migrate it now (chunk is loaded since player is interacting)
        if (!shopData.hasWebSyncData()) {
            if (shopManager != null) {
                shopManager.migrateShopDataIfNeeded(serverWorld, pos);
                // Refresh shop data after migration
                shopData = shopManager.getShop(worldId, pos);
            }
        }

        if (requiredServiceTag != null && player instanceof ServerPlayerEntity serverPlayer) {
            // Allow shop owner to bypass the restriction
            if (!shopData.getOwner().equals(serverPlayer.getUuid())) {
                boolean subscribed = ServiceManager.getInstance()
                    .hasSubscription(serverPlayer.getUuid(), requiredServiceTag);
                if (!subscribed) {
                    serverPlayer.sendMessage(
                        Text.literal("You must subscribe to service ")
                            .formatted(Formatting.WHITE)
                            .append(Text.literal(requiredServiceTag).formatted(Formatting.YELLOW, Formatting.BOLD))
                            .append(Text.literal(" to use this shop.").formatted(Formatting.WHITE)),
                        false
                    );
                    serverPlayer.sendMessage(
                        Text.literal("Use ").formatted(Formatting.WHITE)
                            .append(Text.literal("/service subscribe " + requiredServiceTag)
                                .formatted(Formatting.GREEN))
                            .append(Text.literal(" to subscribe.").formatted(Formatting.WHITE)),
                        false
                    );
                    return ActionResult.SUCCESS;
                }
            }
        }
        ItemDetails itemDetails = parseItemDetails(sign);
        
        if (itemDetails == null) {
            player.sendMessage(Text.literal("Invalid shop configuration!").formatted(Formatting.RED), false);
            return ActionResult.SUCCESS;
        }
        
        // Handle shop transaction with item transfer
        handleTransaction(player, shopData, chestEntity, itemDetails);
        
        // Prevent the sign edit interface from appearing
        return ActionResult.SUCCESS;
    }
    
    /**
     * Helper class to hold item details from sign
     */
    private static class ItemDetails {
        String itemName;
        Item item;
        int quantity;
        double price;
        
        ItemDetails(String itemName, Item item, int quantity, double price) {
            this.itemName = itemName;
            this.item = item;
            this.quantity = quantity;
            this.price = price;
        }
    }
    
    /**
     * Parse item details from a shop sign
     */
    private static ItemDetails parseItemDetails(SignBlockEntity sign) {
        try {
            String firstLine = sign.getFrontText().getMessage(0, false).getString().trim();
            String secondLine = sign.getFrontText().getMessage(1, false).getString().trim();
            String thirdLine = sign.getFrontText().getMessage(2, false).getString().trim();
            String fourthLine = sign.getFrontText().getMessage(3, false).getString().trim();
            
            // Normalize first line to base type (strip optional service tag)
            String baseType = normalizeShopType(firstLine);

            // Check for valid shop type
            boolean isValid = baseType.equalsIgnoreCase("[BUY]") || 
                              baseType.equalsIgnoreCase("[SELL]") ||
                              baseType.equalsIgnoreCase("[ADMINBUY]") || 
                              baseType.equalsIgnoreCase("[ADMINSELL]");
            
            if (!isValid) {
                LOGGER.warn("Invalid shop type: {}", firstLine);
                return null;
            }
            
            // Check if this is using the new format (quantity : price in fourth line)
            Matcher quantityPriceMatcher = QUANTITY_PRICE_PATTERN.matcher(fourthLine);
            if (quantityPriceMatcher.find()) {
                // This is the new format with two-line item name and quantity:price in fourth line
                // Combine second and third lines for item name
                String itemName = secondLine;
                if (!thirdLine.isEmpty()) {
                    itemName += " " + thirdLine;
                }
                
                // Parse quantity and price from fourth line
                int quantity = Integer.parseInt(quantityPriceMatcher.group(1));
                double price = Double.parseDouble(quantityPriceMatcher.group(2));
                
                // Find the item
                Item item = getItemFromName(itemName);
                if (item == null) {
                    LOGGER.warn("Item not found: {}", itemName);
                    return null;
                }
                
                LOGGER.info("Successfully parsed shop sign (new format): {} item={}, quantity={}, price=${}", 
                    baseType, itemName, quantity, price);
                return new ItemDetails(itemName, item, quantity, price);
            } else {
                // Use the original format: item on second line, quantity on third, price on fourth
                String itemName = secondLine;
                String quantityLine = thirdLine;
                String priceLine = fourthLine;
                
                // Parse the quantity
                int quantity = 1;
                Matcher quantityMatcher = QUANTITY_PATTERN.matcher(quantityLine);
                if (quantityMatcher.find()) {
                    quantity = Integer.parseInt(quantityMatcher.group(1));
                } else {
                    try {
                        // Try direct parse if pattern doesn't match
                        quantity = Integer.parseInt(quantityLine);
                    } catch (NumberFormatException e) {
                        LOGGER.warn("No quantity found in: {}", quantityLine);
                        return null;
                    }
                }
                
                // Parse the price
                double price = 0.0;
                if (priceLine.startsWith("$")) {
                    try {
                        price = Double.parseDouble(priceLine.substring(1));
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid price format: {}", priceLine);
                        return null;
                    }
                } else {
                    try {
                        price = Double.parseDouble(priceLine);
                    } catch (NumberFormatException e) {
                        LOGGER.warn("No price found in: {}", priceLine);
                        return null;
                    }
                }
                
                // Find the item
                Item item = getItemFromName(itemName);
                if (item == null) {
                    LOGGER.warn("Item not found: {}", itemName);
                    return null;
                }
                
                LOGGER.info("Successfully parsed shop sign (standard format): {} item={}, quantity={}, price=${}", 
                    baseType, itemName, quantity, price);
                return new ItemDetails(itemName, item, quantity, price);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing shop sign: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract an optional service tag from the first line of a shop sign.
     * Supports formats like [Buy TAG] or [Sell TAG] (case-insensitive, TAG = 1-3 letters).
     * Returns the uppercased tag or null if none/invalid.
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
        if (!(base.equalsIgnoreCase("buy") || base.equalsIgnoreCase("sell"))) {
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
     * Normalize a shop sign's first line to its base type token.
     * E.g. "[Buy TAG]" -> "[BUY]", "[Sell]" -> "[SELL]".
     */
    private static String normalizeShopType(String firstLine) {
        if (firstLine == null) return "";
        String trimmed = firstLine.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return trimmed;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        String[] parts = inner.split("\\s+");
        String base = parts[0].toUpperCase();
        return "[" + base + "]";
    }
    
    /**
     * Find a matching item from the item name on the sign
     */
    private static Item getItemFromName(String name) {
        // Check cache first
        if (itemNameCache.containsKey(name)) {
            return itemNameCache.get(name);
        }
        
        // Try exact match with registry ID
        for (Item item : Registries.ITEM) {
            // Try to match by translated name (more user-friendly)
            String translatedName = item.getName().getString();
            if (translatedName.equalsIgnoreCase(name)) {
                itemNameCache.put(name, item);
                return item;
            }
            
            // Try to match by identifier (technical name)
            Identifier id = Registries.ITEM.getId(item);
            String idPath = id.getPath();
            if (idPath.equalsIgnoreCase(name)) {
                itemNameCache.put(name, item);
                return item;
            }
            
            // Try to match by identifier without namespace
            String[] parts = name.split(":");
            String nameWithoutNamespace = parts.length > 1 ? parts[1] : name;
            if (idPath.equalsIgnoreCase(nameWithoutNamespace)) {
                itemNameCache.put(name, item);
                return item;
            }
        }
        
        // Not found
        return null;
    }
    
    /**
     * Helper method to remove a shop and verify the removal
     * Note: After calling this, you should also call ShopDetector.untrackShopSign 
     * to ensure the position can be reused for a new shop
     */
    private static boolean removeShop(ShopManager shopManager, String worldId, BlockPos pos) {
        // Remove the shop
        shopManager.removeShop(worldId, pos);
        
        // Verify the shop was actually removed
        return shopManager.getShop(worldId, pos) == null;
    }
    
    /**
     * Handle a shop transaction with item transfer
     */
    private static void handleTransaction(PlayerEntity player, ShopData shopData, ChestBlockEntity chest, ItemDetails itemDetails) {
        EconomyManager economyManager = CurrencyMod.getEconomyManager();
        
        boolean isBuyShop = shopData.isBuyShop();
        boolean isAdminShop = shopData.isAdminShop();
        double price = shopData.getPrice();
        String formattedPrice = formatPrice(price);
        
        // Get the prototype item with NBT data (if available)
        ItemStack prototypeItem = null;
        
        // For buying from shop, get item from chest
        if (isBuyShop && !isAdminShop && chest != null) {
            // Look for the first item of the correct type in the chest
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.getStack(i);
                if (!stack.isEmpty() && itemStacksMatch(stack, itemToItemStack(itemDetails.item))) {
                    prototypeItem = stack.copy();
                    prototypeItem.setCount(1); // Just need 1 for reference
                    break;
                }
            }
        }
        
        // For selling to shop, get item from player
        if (!isBuyShop) {
            // Look for the first item of the correct type in the player's inventory
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty() && itemStacksMatch(stack, itemToItemStack(itemDetails.item))) {
                    prototypeItem = stack.copy();
                    prototypeItem.setCount(1); // Just need 1 for reference
                    break;
                }
            }
        }
        
        // Default to a basic item if no prototype found
        if (prototypeItem == null) {
            prototypeItem = new ItemStack(itemDetails.item);
        }
        
        // Verify that we have a chest for regular shops
        if (!isAdminShop && chest == null) {
            Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                .append(Text.literal("Shop Error").formatted(Formatting.RED, Formatting.BOLD));
                
            Text detailsText = Text.literal("This shop has no attached chest storage.");
                
            player.sendMessage(errorText, false);
            player.sendMessage(detailsText, false);
            return;
        }
        
        if (isBuyShop) {
            // Player is buying from the shop (taking items from chest, paying money)
            
            // Check if player has enough money
            if (economyManager.getBalance(player.getUuid()) < price) {
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Insufficient Funds").formatted(Formatting.RED, Formatting.BOLD));
                    
                Text detailsText = Text.literal("You need ")
                    .append(Text.literal("$" + formattedPrice).formatted(Formatting.YELLOW))
                    .append(Text.literal(" to make this purchase."));
                    
                player.sendMessage(errorText, false);
                player.sendMessage(detailsText, false);
                return;
            }
            
            // For regular shops, check if chest has the items
            if (!isAdminShop) {
                int availableItems = countItems(chest, itemDetails.item, prototypeItem);
                if (availableItems < itemDetails.quantity) {
                    Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                        .append(Text.literal("Shop Out of Stock").formatted(Formatting.RED, Formatting.BOLD));
                        
                    Text detailsText = Text.literal("Only ")
                        .append(Text.literal(availableItems + "").formatted(Formatting.YELLOW))
                        .append(Text.literal(" items available."));
                        
                    player.sendMessage(errorText, false);
                    player.sendMessage(detailsText, false);
                    return;
                }
            }
            
            // C-09 fix (buy path): make the transaction atomic across
            // economy + chest + player inventory.
            //
            // The pre-checks above (line 476 balance, line 491 stock) are
            // TOCTOU: a concurrent tax/service-charge deduction can drop the
            // player's balance below 'price' between the check and the actual
            // removeBalance call. The old code discarded removeBalance's
            // return value, so a failed deduction silently let the player take
            // the items for free. We now check the return value and abort
            // cleanly if the deduction fails.
            //
            // We also pre-check hasInventorySpace BEFORE deducting money, so
            // the player is never charged for items we cannot deliver. The
            // addItemsToPlayer helper has also been hardened to drop any
            // leftover at the player's feet if the inventory fills up mid-
            // insertion (defensive: even with the pre-check, a concurrent
            // inventory change could still leave a partial leftover).
            if (!hasInventorySpace(player, itemDetails.item, itemDetails.quantity)) {
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Inventory Full").formatted(Formatting.RED, Formatting.BOLD));
                Text detailsText = Text.literal("Make space in your inventory before buying.");
                player.sendMessage(errorText, false);
                player.sendMessage(detailsText, false);
                return;
            }

            // 1. Remove money from player -- check return value (C-09 fix).
            double deductResult = economyManager.removeBalance(player.getUuid(), price);
            if (deductResult < 0) {
                LOGGER.warn("Buy transaction aborted for player {}: balance dropped below price {} " +
                    "between pre-check and removeBalance (likely concurrent tax/service charge)",
                    player.getName().getString(), price);
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Insufficient Funds").formatted(Formatting.RED, Formatting.BOLD));
                Text detailsText = Text.literal("Your balance changed during the transaction. Please try again.");
                player.sendMessage(errorText, false);
                player.sendMessage(detailsText, false);
                return; // no money moved, no items moved, no chest mutation
            }
            
            if (!isAdminShop) {
                // 2. Add money to shop owner (skip for admin shops)
                economyManager.addBalance(shopData.getOwner(), price);
                // 3. Remove items from chest (skip for admin shops)
                removeItemsFromChest(chest, itemDetails.item, itemDetails.quantity, prototypeItem);
            }
            
            // 4. Add items to player (using prototype for enchantments).
            //    Helper now drops any leftover at the player's feet if the
            //    inventory fills up mid-insertion (C-09 fix).
            addItemsToPlayer(player, prototypeItem, itemDetails.quantity);
            
            // Send a single-line transaction message
            Text message = Text.literal("✓ ")
                .formatted(Formatting.GREEN)
                .append(Text.literal("You bought ").formatted(Formatting.WHITE))
                .append(Text.literal(itemDetails.quantity + "").formatted(Formatting.YELLOW))
                .append(Text.literal(" " + itemDetails.itemName + " for ").formatted(Formatting.WHITE))
                .append(Text.literal("$" + formattedPrice).formatted(Formatting.YELLOW, Formatting.BOLD));
                
            if (isAdminShop) {
                // Use a complete new text instance since we can't append to an immutable Text
                message = Text.empty()
                    .append(message)
                    .append(Text.literal(" (Admin Shop)").formatted(Formatting.GOLD));
            }
            
            player.sendMessage(message, false);
            
            // Extra message if buying from your own shop (for testing)
            if (!isAdminShop && shopData.getOwner().equals(player.getUuid())) {
                player.sendMessage(Text.literal("⚠ This is your own shop")
                        .formatted(Formatting.GOLD), false);
            }
            
            // Record the transaction for notification/summary
            ShopTransactionManager.getInstance().recordTransaction(
                new ShopTransactionManager.TransactionRecord(
                    player.getUuid(),
                    player.getName().getString(),
                    shopData.getOwner(),
                    itemDetails.itemName,
                    itemDetails.quantity,
                    price,
                    true, // isBuyShop
                    System.currentTimeMillis()
                )
            );
            
            LOGGER.info("Transaction completed at {} shop: Player {} bought {}x {} for ${}", 
                isAdminShop ? "admin" : "regular", player.getName().getString(), 
                itemDetails.quantity, itemDetails.itemName, formattedPrice);
            
        } else {
            // Player is selling to the shop (giving items to chest, receiving money)
            
            // Check if player has enough items
            int playerItemCount;
            if (prototypeItem != null && hasSpecialProperties(prototypeItem)) {
                playerItemCount = countPlayerItemsWithEnchants(player, prototypeItem);
            } else {
                playerItemCount = countPlayerItems(player, itemDetails.item);
            }
            
            if (playerItemCount < itemDetails.quantity) {
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Insufficient Items").formatted(Formatting.RED, Formatting.BOLD));
                    
                Text detailsText = Text.literal("You only have ")
                    .append(Text.literal(playerItemCount + "").formatted(Formatting.YELLOW))
                    .append(Text.literal(" of these items."));
                    
                player.sendMessage(errorText, false);
                player.sendMessage(detailsText, false);
                return;
            }
            
            // For regular shops, check if shop owner has enough money
            if (!isAdminShop) {
                double ownerBalance = economyManager.getBalance(shopData.getOwner());
                if (ownerBalance < price) {
                    Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                        .append(Text.literal("Shop Can't Afford Purchase").formatted(Formatting.RED, Formatting.BOLD));
                        
                    Text detailsText = Text.literal("The shop owner only has ")
                        .append(Text.literal("$" + formatPrice(ownerBalance)).formatted(Formatting.YELLOW));
                        
                    player.sendMessage(errorText, false);
                    player.sendMessage(detailsText, false);
                    return;
                }
            }
            
            // For regular shops, check if there's space in the chest
            if (!isAdminShop && !hasChestSpace(chest)) {
                Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                    .append(Text.literal("Shop Chest Full").formatted(Formatting.RED, Formatting.BOLD));
                    
                Text detailsText = Text.literal("The shop's chest is full and cannot accept more items.");
                    
                player.sendMessage(errorText, false);
                player.sendMessage(detailsText, false);
                return;
            }
            
            // C-09 fix (sell path): symmetric guard against the buy path.
            //
            // We credit the player first via addBalance (cannot fail -- it
            // just sets a map value), then attempt to deduct from the shop
            // owner. If the owner's balance dropped below 'price' between the
            // pre-check (line 587) and now (e.g. owner is online and spending),
            // removeBalance returns -1. We must then REFUND the player (undo
            // the addBalance) and abort, otherwise the player gets paid for
            // free and the shop owner keeps the items.
            //
            // We do NOT remove items from the player or add items to the chest
            // until the owner-side deduction succeeds. This keeps the sell
            // path atomic: either all three legs succeed, or none of them do.
            //
            // 1. Add money to player (cannot fail)
            economyManager.addBalance(player.getUuid(), price);
            
            if (!isAdminShop) {
                // 2. Remove money from shop owner -- check return value (C-09 fix).
                double ownerDeduct = economyManager.removeBalance(shopData.getOwner(), price);
                if (ownerDeduct < 0) {
                    // Owner can no longer afford the purchase. Refund the player,
                    // do NOT touch items or chest, abort the transaction.
                    economyManager.addBalance(player.getUuid(), -price); // undo the credit
                    LOGGER.warn("Sell transaction aborted for player {}: shop owner {} balance dropped " +
                        "below price {} between pre-check and removeBalance; player refunded",
                        player.getName().getString(), shopData.getOwner(), price);
                    Text errorText = Text.literal("❌ ").formatted(Formatting.RED)
                        .append(Text.literal("Shop Can't Afford Purchase").formatted(Formatting.RED, Formatting.BOLD));
                    Text detailsText = Text.literal("The shop owner's balance changed during the transaction. " +
                        "Your items were not taken; please try again.");
                    player.sendMessage(errorText, false);
                    player.sendMessage(detailsText, false);
                    return;
                }
                // 3. Add items to chest (skip for admin shops)
                addItemsToChest(chest, itemDetails.item, itemDetails.quantity, prototypeItem);
            }
            
            // 4. Remove items from player
            removeItemsFromPlayer(player, itemDetails.item, itemDetails.quantity, prototypeItem);
            
            // Send a single-line transaction message
            Text message = Text.literal("✓ ")
                .formatted(Formatting.BLUE)
                .append(Text.literal("You sold ").formatted(Formatting.WHITE))
                .append(Text.literal(itemDetails.quantity + "").formatted(Formatting.YELLOW))
                .append(Text.literal(" " + itemDetails.itemName + " for ").formatted(Formatting.WHITE))
                .append(Text.literal("$" + formattedPrice).formatted(Formatting.YELLOW, Formatting.BOLD));
            
            if (isAdminShop) {
                // Use a complete new text instance since we can't append to an immutable Text
                message = Text.empty()
                    .append(message)
                    .append(Text.literal(" (Admin Shop)").formatted(Formatting.GOLD));
            }
            
            player.sendMessage(message, false);
            
            // Extra message if selling to your own shop (for testing)
            if (!isAdminShop && shopData.getOwner().equals(player.getUuid())) {
                player.sendMessage(Text.literal("⚠ This is your own shop")
                        .formatted(Formatting.GOLD), false);
            }
            
            // Record the transaction for notification/summary
            ShopTransactionManager.getInstance().recordTransaction(
                new ShopTransactionManager.TransactionRecord(
                    player.getUuid(),
                    player.getName().getString(),
                    shopData.getOwner(),
                    itemDetails.itemName,
                    itemDetails.quantity,
                    price,
                    false, // isBuyShop 
                    System.currentTimeMillis()
                )
            );
            
            LOGGER.info("Transaction completed at {} shop: Player {} sold {}x {} for ${}", 
                isAdminShop ? "admin" : "regular", player.getName().getString(), 
                itemDetails.quantity, itemDetails.itemName, formattedPrice);
        }
    }
    
    /**
     * Format a price nicely
     */
    private static String formatPrice(double price) {
        if (price == Math.floor(price)) {
            return String.format("%.0f", price);
        } else {
            return String.format("%.2f", price);
        }
    }
    
    /**
     * Check if two item stacks match, considering enchantments
     */
    private static boolean itemStacksMatch(ItemStack stack1, ItemStack stack2) {
        // Check basic item type
        if (stack1.getItem() != stack2.getItem()) {
            return false;
        }
        
        // Check if item is enchanted
        boolean stack1Enchanted = stack1.hasEnchantments() || stack1.getItem() instanceof EnchantedBookItem;
        boolean stack2Enchanted = stack2.hasEnchantments() || stack2.getItem() instanceof EnchantedBookItem;
        
        // If one is enchanted and the other isn't, they don't match
        if (stack1Enchanted != stack2Enchanted) {
            return false;
        }
        
        // For enchanted items, in MC 1.21.1 we'll use a simplified approach
        // Just consider items of the same type with the same enchantment status as matching
        // This is a compromise due to API changes in 1.21.1
        
        // Basic items match if they're the same type
        return true;
    }
    
    /**
     * Compare enchantments between two enchanted books
     * This is no longer used in MC 1.21.1
     */
    private static boolean compareEnchantedBooks(ItemStack book1, ItemStack book2) {
        // In MC 1.21.1, we use a simpler approach in itemStacksMatch
        return true;
    }
    
    /**
     * Compare enchantments between two regular enchanted items
     * This is no longer used in MC 1.21.1
     */
    private static boolean compareEnchantments(ItemStack item1, ItemStack item2) {
        // In MC 1.21.1, we use a simpler approach in itemStacksMatch
        return true;
    }
    
    /**
     * Checks if an item has any special properties like enchantments
     */
    private static boolean hasSpecialProperties(ItemStack stack) {
        // Check for enchantments
        return stack.hasEnchantments() || stack.getItem() instanceof EnchantedBookItem;
    }
    
    // Create a helper method to convert Item to ItemStack for comparison
    private static ItemStack itemToItemStack(Item item) {
        return new ItemStack(item);
    }
    
    /**
     * Counts items in a chest, considering enchantments
     * @param chest The chest to check
     * @param prototype The prototype item to match
     * @return The number of matching items found
     */
    private static int countItemsWithEnchants(ChestBlockEntity chest, ItemStack prototype) {
        int count = 0;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() && itemStacksMatch(stack, prototype)) {
                count += stack.getCount();
            }
        }
        return count;
    }
    
    /**
     * Count items of a specific type in a player's inventory
     */
    private static int countPlayerItems(PlayerEntity player, Item item) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();
        
        // Check main inventory
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        
        return count;
    }
    
    /**
     * Counts items in a player's inventory, considering enchantments
     * @param player The player
     * @param prototype The prototype item to match
     * @return The number of matching items found
     */
    private static int countPlayerItemsWithEnchants(PlayerEntity player, ItemStack prototype) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();
        
        // Check main inventory
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && itemStacksMatch(stack, prototype)) {
                count += stack.getCount();
            }
        }
        
        return count;
    }
    
    /**
     * Count items in a chest, considering enchantments
     * @param chest The chest to check
     * @param item The item to match
     * @param prototype The prototype item to match
     * @return The number of matching items found
     */
    private static int countItems(ChestBlockEntity chest, Item item, ItemStack prototype) {
        // If we have a prototype with NBT data, use specialized counting
        if (prototype != null && hasSpecialProperties(prototype)) {
            return countItemsWithEnchants(chest, prototype);
        }
        
        // Otherwise fall back to simple item type counting
        int count = 0;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
    
    /**
     * Check if a player has enough inventory space for the items
     */
    private static boolean hasInventorySpace(PlayerEntity player, Item item, int quantity) {
        PlayerInventory inventory = player.getInventory();
        int remainingQuantity = quantity;
        
        // Check for existing stacks that can be filled
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                // Empty slot can hold a full stack
                remainingQuantity -= item.getMaxCount();
                if (remainingQuantity <= 0) return true;
            } else if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                // Partial stack can be filled
                remainingQuantity -= (stack.getMaxCount() - stack.getCount());
                if (remainingQuantity <= 0) return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if a chest has space for more items
     */
    private static boolean hasChestSpace(ChestBlockEntity chest) {
        for (int i = 0; i < chest.size(); i++) {
            if (chest.getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Remove items from a chest
     */
    private static void removeItemsFromChest(ChestBlockEntity chest, Item item, int quantity, ItemStack prototype) {
        int remaining = quantity;
        boolean checkSpecialProps = prototype != null && hasSpecialProperties(prototype);
        
        for (int i = 0; i < chest.size() && remaining > 0; i++) {
            ItemStack stack = chest.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                // If we need to check special properties and the items don't match, skip this stack
                if (checkSpecialProps && !itemStacksMatch(stack, prototype)) {
                    continue;
                }
                
                if (stack.getCount() <= remaining) {
                    // Remove entire stack
                    remaining -= stack.getCount();
                    chest.setStack(i, ItemStack.EMPTY);
                } else {
                    // Remove partial stack
                    stack.decrement(remaining);
                    remaining = 0;
                }
            }
        }
        
        chest.markDirty();
    }
    
    /**
     * Add items to a chest
     */
    private static void addItemsToChest(ChestBlockEntity chest, Item item, int quantity, ItemStack prototype) {
        int remaining = quantity;
        
        // If we have a prototype with NBT, use it
        boolean hasSpecialProps = prototype != null && hasSpecialProperties(prototype);
        
        // First try to fill existing matching stacks if we have special properties
        if (hasSpecialProps) {
            for (int i = 0; i < chest.size() && remaining > 0; i++) {
                ItemStack stack = chest.getStack(i);
                if (!stack.isEmpty() && itemStacksMatch(stack, prototype) && stack.getCount() < stack.getMaxCount()) {
                    int canAdd = Math.min(remaining, stack.getMaxCount() - stack.getCount());
                    stack.increment(canAdd);
                    remaining -= canAdd;
                }
            }
        } else {
            // Standard stacking for items without special properties
            for (int i = 0; i < chest.size() && remaining > 0; i++) {
                ItemStack stack = chest.getStack(i);
                if (!stack.isEmpty() && stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                    int canAdd = Math.min(remaining, stack.getMaxCount() - stack.getCount());
                    stack.increment(canAdd);
                    remaining -= canAdd;
                }
            }
        }
        
        // Then fill empty slots
        for (int i = 0; i < chest.size() && remaining > 0; i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty()) {
                int stackSize = Math.min(remaining, item.getMaxCount());
                
                // Use a copy of the prototype if available
                if (hasSpecialProps) {
                    ItemStack newStack = prototype.copy();
                    newStack.setCount(stackSize);
                    chest.setStack(i, newStack);
                } else {
                    chest.setStack(i, new ItemStack(item, stackSize));
                }
                
                remaining -= stackSize;
            }
        }
        
        chest.markDirty();
    }
    
    /**
     * Remove items from a player
     */
    private static void removeItemsFromPlayer(PlayerEntity player, Item item, int quantity, ItemStack prototype) {
        if (prototype != null && hasSpecialProperties(prototype)) {
            // Use a predicate that checks both item type and special properties
            player.getInventory().remove(
                stack -> stack.getItem() == item && itemStacksMatch(stack, prototype), 
                quantity, 
                player.getInventory()
            );
        } else {
            // Fall back to simple item type checking
            player.getInventory().remove(
                stack -> stack.getItem() == item, 
                quantity, 
                player.getInventory()
            );
        }
    }
    
    /**
     * Add items to a player
     */
    private static void addItemsToPlayer(PlayerEntity player, ItemStack prototype, int quantity) {
        // Create a copy of the prototype item with the requested quantity
        ItemStack itemToAdd = prototype.copy();
        itemToAdd.setCount(quantity);
        
        // Insert the item into the player's inventory. insertStack mutates
        // itemToAdd in place: any leftover that did not fit remains in the
        // stack and the method returns false. The old code discarded the
        // leftover, silently destroying items the player had paid for (C-09).
        // We now drop any leftover at the player's feet, matching the pattern
        // in AuctionManager.giveItemToPlayer (line ~1208) and TradeManager
        // (line ~297).
        boolean fullyInserted = player.getInventory().insertStack(itemToAdd);
        if (!fullyInserted || !itemToAdd.isEmpty()) {
            LOGGER.warn("Player {} inventory was full during shop purchase; dropping {}x {} at their feet",
                player.getName().getString(), itemToAdd.getCount(), prototype.getItem().getName().getString());
            player.dropItem(itemToAdd, false);
            player.sendMessage(Text.literal("Your inventory was full, so " +
                itemToAdd.getCount() + "x " +
                prototype.getName().getString() +
                " was dropped at your feet.").formatted(Formatting.YELLOW), false);
        }
    }
    
    /**
     * Find the chest that a sign is attached to.
     * Returns null for admin shops, which don't need a chest.
     */
    private static BlockPos findAttachedChest(World world, BlockPos signPos, BlockState signState) {
        try {
            // Get shop data to check if this is an admin shop
            ShopManager shopManager = ((ShopAccess) world).getShopManager();
            if (shopManager != null) {
                String worldId = ShopManager.getWorldId(world);
                ShopData shopData = shopManager.getShop(worldId, signPos);
                
                // Admin shops don't need a chest
                if (shopData != null && shopData.isAdminShop()) {
                    return signPos; // Return any valid position to avoid chest checks
                }
            }
            
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
        } catch (Exception e) {
            LOGGER.error("Error finding attached chest: {}", e.getMessage());
        }
        
        return null; // No chest found
    }
} 