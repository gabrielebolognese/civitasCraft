package dev.civitas.listener;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.command.Replies;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.Money;
import dev.civitas.core.shop.PlayerShop;
import dev.civitas.core.shop.PlayerShopService;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;

/**
 * Trading at a chest shop sign, SPEC 4.5.
 *
 * <p>Right-click buys, shift-right-click sells. Left-click is left alone entirely, because
 * on a sign it means "break this", and a mis-set shop should not cost a player their stock
 * the first time they try to close it.
 *
 * <p>Registered at {@link EventPriority#HIGH} so land protection, which runs at
 * {@code LOW}, has already had its say: a sign inside a claim a player may not touch never
 * reaches this listener.
 */
public final class ShopInteractListener implements Listener {

    private final PlayerShopService shops;
    private final ConfigManager configs;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public ShopInteractListener(PlayerShopService shops, ConfigManager configs, LangManager lang,
                                Scheduler scheduler, Logger logger) {
        this.shops = Objects.requireNonNull(shops, "shops");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Optional<PlayerShop> found = shops.atSign(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (found.isEmpty()) {
            return;
        }

        // From here the click belongs to the shop, whatever else it might have done.
        event.setUseInteractedBlock(Event.Result.DENY);

        PlayerShop shop = found.get();
        Player player = event.getPlayer();

        if (shop.isOwner(player.getUniqueId())) {
            describe(player, shop);
            return;
        }

        Optional<Inventory> chest = chestOf(shop);
        if (chest.isEmpty()) {
            lang.send(player, "shop.chest-missing");
            return;
        }

        boolean selling = player.isSneaking();
        var pending = selling
                ? shops.sell(player.getUniqueId(), shop, chest.get(), player.getInventory())
                : shops.buy(player.getUniqueId(), shop, chest.get(), player.getInventory());

        pending.whenComplete((result, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Shop transaction failed", error);
                lang.send(player, "command.error");
                return;
            }
            if (result instanceof Result.Failure<PlayerShopService.ShopReceipt> failure) {
                Replies.sendFailure(player, lang, failure);
                return;
            }
            PlayerShopService.ShopReceipt receipt = result.orElseThrow();
            lang.send(player, selling ? "shop.sold" : "shop.bought",
                    Replies.p("amount", String.valueOf(receipt.amount())),
                    Replies.p("item", receipt.material().name()),
                    Replies.p("price", Money.format(receipt.price(), configs)),
                    Replies.p("balance", Money.format(receipt.balance(), configs)));

            notifyOwner(shop, player, receipt, selling);
        }));
    }

    /** The owner sees their own sign's terms rather than trading with themselves. */
    private void describe(Player owner, PlayerShop shop) {
        lang.send(owner, "shop.info",
                Replies.p("item", shop.material()),
                Replies.p("quantity", String.valueOf(shop.terms().quantity())),
                Replies.p("buy", price(shop.terms().customerPays())),
                Replies.p("sell", price(shop.terms().customerGets())),
                Replies.p("stock", String.valueOf(stock(shop))));
    }

    private void notifyOwner(PlayerShop shop, Player customer,
                             PlayerShopService.ShopReceipt receipt, boolean customerSold) {
        Player owner = org.bukkit.Bukkit.getPlayer(shop.owner());
        if (owner == null) {
            return;
        }
        lang.send(owner, customerSold ? "shop.owner-bought" : "shop.owner-sold",
                Replies.p("player", customer.getName()),
                Replies.p("amount", String.valueOf(receipt.amount())),
                Replies.p("item", receipt.material().name()),
                Replies.p("price", Money.format(receipt.price(), configs)));
    }

    private Optional<Inventory> chestOf(PlayerShop shop) {
        var world = org.bukkit.Bukkit.getWorld(shop.world());
        if (world == null) {
            return Optional.empty();
        }
        Block block = world.getBlockAt(shop.chestX(), shop.chestY(), shop.chestZ());
        return block.getState() instanceof Container container
                ? Optional.of(container.getInventory())
                : Optional.empty();
    }

    private int stock(PlayerShop shop) {
        var material = org.bukkit.Material.matchMaterial(shop.material());
        return chestOf(shop).filter(inventory -> material != null)
                .map(inventory -> PlayerShopService.count(inventory, material))
                .orElse(0);
    }

    private String price(BigDecimal amount) {
        return amount == null ? "-" : Money.format(amount, configs);
    }
}
