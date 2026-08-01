package dev.civitas.command.player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.market.MarketItem;
import dev.civitas.core.market.MarketService;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /shop}, SPEC 9.1.
 *
 * <p>SPEC 9.1 describes this as opening the market GUI, but the GUI framework is M7 and its
 * screens are M8. This prints the same catalogue and prices in chat and buys through
 * {@code /shop buy}, so the market is usable now; M8 replaces the presentation and keeps
 * every service call underneath it.
 */
public final class ShopCommand {

    private static final int PER_PAGE = 10;

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public ShopCommand(Supplier<CivitasServices> services, LangManager lang,
                       Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("shop")
                .requires(source -> source.getSender().hasPermission("civitas.market.use"))
                .executes(context -> list(context.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> list(context.getSource().getSender(),
                                IntegerArgumentType.getInteger(context, "page"))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .suggests(WorthCommand.MarketSuggestions.materials(services))
                                .executes(context -> buy(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "item"), 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> buy(context.getSource().getSender(),
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .build();
    }

    // ==================================================================================
    // The catalogue
    // ==================================================================================

    private int list(Audience audience, int page) {
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        MarketService market = services.get().market();
        if (!market.enabled()) {
            lang.send(audience, "market.disabled");
            return Command.SINGLE_SUCCESS;
        }

        List<MarketItem> all = market.registry().catalogue();
        if (all.isEmpty()) {
            lang.sendRaw(audience, "market.list-empty");
            return Command.SINGLE_SUCCESS;
        }

        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int clamped = Math.min(page, pages);
        lang.sendRaw(audience, "market.list-header",
                Replies.p("page", String.valueOf(clamped)),
                Replies.p("pages", String.valueOf(pages)));

        int from = (clamped - 1) * PER_PAGE;
        for (MarketItem item : all.subList(from, Math.min(from + PER_PAGE, all.size()))) {
            market.quote(item.material()).ifPresent(quote ->
                    lang.sendRaw(audience, "market.list-entry",
                            Replies.p("item", item.material()),
                            Replies.p("sell", format(quote.sellPrice())),
                            Replies.p("buy", format(quote.buyPrice()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Buying
    // ==================================================================================

    private int buy(Audience audience, String typed, int amount) {
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        Material material = Material.matchMaterial(typed);
        if (material == null || !services.get().market().registry().trades(material.name())) {
            lang.send(player, "market.not-traded", Replies.p("item", typed));
            return Command.SINGLE_SUCCESS;
        }
        if (dev.civitas.core.shop.PlayerShopService
                .freeSpaceFor(player.getInventory(), material) < amount) {
            lang.send(player, "market.no-room");
            return Command.SINGLE_SUCCESS;
        }

        services.get().market().buy(player.getUniqueId(), material.name(), amount)
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(java.util.logging.Level.SEVERE, "Market purchase failed", error);
                        lang.send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<MarketService.Receipt> failure) {
                        Replies.sendFailure(player, lang, failure);
                        return;
                    }
                    MarketService.Receipt receipt = result.orElseThrow();
                    // Money has already moved, so the items must be given out even if the
                    // player's inventory filled in the meantime; the overflow drops.
                    var leftovers = player.getInventory()
                            .addItem(new ItemStack(material, receipt.amount()));
                    leftovers.values().forEach(stack ->
                            player.getWorld().dropItemNaturally(player.getLocation(), stack));

                    lang.send(player, "market.bought",
                            Replies.p("amount", String.valueOf(receipt.amount())),
                            Replies.p("item", receipt.item().material()),
                            Replies.p("cost", format(receipt.net())),
                            Replies.p("balance", format(receipt.balance())));
                }));
        return Command.SINGLE_SUCCESS;
    }

    private String format(BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    private boolean notReady(Audience audience) {
        if (services.get() != null) {
            return false;
        }
        lang.send(audience, "plugin.starting");
        return true;
    }
}
