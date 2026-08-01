package dev.civitas.listener;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.protection.ProtectionAction;
import dev.civitas.core.protection.ProtectionGuard;
import dev.civitas.core.shop.PlayerShop;
import dev.civitas.core.shop.PlayerShopService;
import dev.civitas.core.shop.ShopSign;
import dev.civitas.core.shop.ShopTerms;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Creating and destroying chest shops, SPEC 4.5.
 *
 * <p>A shop is a sign attached to a container. The material traded is whatever the container
 * already holds, because SPEC 4.5's four lines carry a quantity, prices and the owner's
 * name, with nowhere left to write an item name; stocking the chest first is also what every
 * player expects to do anyway.
 */
public final class ShopSignListener implements Listener {

    private final PlayerShopService shops;
    private final ShopSign signs;
    private final ProtectionGuard guard;
    private final ConfigManager configs;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public ShopSignListener(PlayerShopService shops, ShopSign signs, ProtectionGuard guard,
                            ConfigManager configs, LangManager lang, Scheduler scheduler,
                            Logger logger) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.signs = Objects.requireNonNull(signs, "signs");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Creation
    // ==================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (!enabled()) {
            return;
        }
        List<Component> lines = event.lines();
        if (lines.size() < 4 || !signs.isHeader(plain(lines.get(0)))) {
            return;
        }

        Player player = event.getPlayer();
        Block sign = event.getBlock();

        // SPEC 4.5: shops may be created where the player may build. In wilderness that is
        // everyone, which is exactly what M4's protection service already answers.
        if (!guard.allows(player, sign.getLocation(), ProtectionAction.BUILD)) {
            event.setCancelled(true);
            return;
        }

        Optional<Block> chest = attachedContainer(sign);
        if (chest.isEmpty()) {
            lang.send(player, "shop.no-chest");
            event.setCancelled(true);
            return;
        }

        Result<ShopTerms> terms = signs.parse(plain(lines.get(1)), plain(lines.get(2)));
        if (terms instanceof Result.Failure<ShopTerms> failure) {
            Replies.sendFailure(player, lang, failure);
            event.setCancelled(true);
            return;
        }

        Optional<Material> material = firstPlainMaterial(chest.get());
        if (material.isEmpty()) {
            lang.send(player, "shop.empty-chest");
            event.setCancelled(true);
            return;
        }

        // Line 4 is the owner, filled in by the plugin (SPEC 4.5).
        event.line(3, Component.text(player.getName()));

        Block chestBlock = chest.get();
        shops.create(player.getUniqueId(), sign.getWorld().getName(),
                        sign.getX(), sign.getY(), sign.getZ(),
                        chestBlock.getX(), chestBlock.getY(), chestBlock.getZ(),
                        material.get().name(), terms.orElseThrow(),
                        shopLimit(player), System.currentTimeMillis())
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(java.util.logging.Level.SEVERE, "Shop creation failed", error);
                        lang.send(player, "command.error");
                        breakSign(sign);
                        return;
                    }
                    if (result instanceof Result.Failure<PlayerShop> failure) {
                        Replies.sendFailure(player, lang, failure);
                        breakSign(sign);
                        return;
                    }
                    PlayerShop shop = result.orElseThrow();
                    lang.send(player, "shop.created",
                            Replies.p("item", shop.material()),
                            Replies.p("quantity", String.valueOf(shop.terms().quantity())),
                            Replies.p("buy", price(shop.terms().customerPays())),
                            Replies.p("sell", price(shop.terms().customerGets())));
                }));
    }

    // ==================================================================================
    // Destruction
    // ==================================================================================

    /**
     * A shop dies with its sign or its chest.
     *
     * <p>{@link EventPriority#MONITOR} with {@code ignoreCancelled}, so a break that land
     * protection refused does not delete anything.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();

        Optional<PlayerShop> atSign =
                shops.atSign(world, block.getX(), block.getY(), block.getZ());
        if (atSign.isPresent()) {
            removeAndTell(event.getPlayer(), atSign.get());
            return;
        }

        List<PlayerShop> atChest =
                shops.atChest(world, block.getX(), block.getY(), block.getZ());
        if (atChest.isEmpty()) {
            return;
        }
        shops.removeAtChest(world, block.getX(), block.getY(), block.getZ())
                .whenComplete((count, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(java.util.logging.Level.WARNING,
                                "Could not delete shops for a broken chest", error);
                        return;
                    }
                    lang.send(event.getPlayer(), "shop.removed-with-chest",
                            Replies.p("count", String.valueOf(atChest.size())));
                }));
    }

    private void removeAndTell(Player player, PlayerShop shop) {
        shops.remove(shop).whenComplete((count, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(java.util.logging.Level.WARNING, "Could not delete a shop", error);
                return;
            }
            lang.send(player, "shop.removed");
        }));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** The container a sign is attached to: behind a wall sign, or under a standing one. */
    static Optional<Block> attachedContainer(Block sign) {
        Block candidate = sign.getBlockData() instanceof WallSign wallSign
                ? sign.getRelative(wallSign.getFacing().getOppositeFace())
                : sign.getRelative(BlockFace.DOWN);
        return candidate.getState() instanceof Container ? Optional.of(candidate) : Optional.empty();
    }

    /** The first plain stack in a container, which is the material the shop trades. */
    private static Optional<Material> firstPlainMaterial(Block chest) {
        if (!(chest.getState() instanceof Container container)) {
            return Optional.empty();
        }
        for (ItemStack stack : container.getInventory().getContents()) {
            if (PlayerShopService.isPlain(stack)) {
                return Optional.of(stack.getType());
            }
        }
        return Optional.empty();
    }

    /**
     * The SPEC 10 per-player shop limit.
     *
     * <p>Read from {@code civitas.limit.shops.<n>} so a donor rank can be given more without
     * a code change, falling back to the config default.
     */
    private int shopLimit(Player player) {
        int configured = configs.get(ConfigFile.ECONOMY)
                .getInt("player-shops.default-limit-per-player", 5);
        int best = configured;
        for (var permission : player.getEffectivePermissions()) {
            String node = permission.getPermission();
            if (!permission.getValue() || !node.startsWith("civitas.limit.shops.")) {
                continue;
            }
            String suffix = node.substring("civitas.limit.shops.".length());
            if (suffix.equals("*")) {
                return -1;
            }
            try {
                best = Math.max(best, Integer.parseInt(suffix));
            } catch (NumberFormatException ignored) {
                // A malformed node grants nothing rather than breaking the check.
            }
        }
        return best;
    }

    private void breakSign(Block sign) {
        if (sign.getState() instanceof org.bukkit.block.Sign) {
            sign.breakNaturally();
        }
    }

    private String price(java.math.BigDecimal amount) {
        return amount == null ? "-" : Money.format(amount, configs);
    }

    private boolean enabled() {
        return configs.get(ConfigFile.ECONOMY).getBoolean("player-shops.enabled", true);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
