package dev.civitas.command.player;

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
import dev.civitas.core.market.MarketService;
import dev.civitas.core.shop.PlayerShopService;
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
 * {@code /sell hand [amount]} and {@code /sell all <material>}, SPEC 9.1.
 *
 * <p>Items are taken out of the inventory <em>before</em> the sale is attempted and put back
 * if it fails. Paying first and taking afterwards would let a player log out in the gap and
 * keep both, which is the one bug a shop command must not have.
 */
public final class SellCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public SellCommand(Supplier<CivitasServices> services, LangManager lang,
                       Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("sell")
                .requires(source -> source.getSender().hasPermission("civitas.market.use"))
                .executes(context -> {
                    lang.send(context.getSource().getSender(), "market.sell-usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("hand")
                        .executes(context -> sellHand(context.getSource().getSender(), -1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(context -> sellHand(context.getSource().getSender(),
                                        IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("all")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .suggests(WorthCommand.MarketSuggestions.materials(services))
                                .executes(context -> sellAll(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "item")))))
                .build();
    }

    // ==================================================================================
    // /sell hand
    // ==================================================================================

    private int sellHand(Audience audience, int requested) {
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        Result<ItemStack> accepted = services.get().marketFilter().accept(held);
        if (accepted instanceof Result.Failure<ItemStack> failure) {
            Replies.sendFailure(player, lang, failure);
            return Command.SINGLE_SUCCESS;
        }

        int amount = requested < 0 ? held.getAmount() : Math.min(requested, held.getAmount());
        if (requested > held.getAmount()) {
            lang.send(player, "market.not-enough",
                    Replies.p("amount", String.valueOf(requested)),
                    Replies.p("held", String.valueOf(held.getAmount())));
            return Command.SINGLE_SUCCESS;
        }

        Material material = held.getType();
        if (!services.get().market().registry().trades(material.name())) {
            lang.send(player, "market.not-traded", Replies.p("item", material.name()));
            return Command.SINGLE_SUCCESS;
        }

        // Taken first; returned by returnItems if the sale does not go through.
        held.setAmount(held.getAmount() - amount);
        player.getInventory().setItemInMainHand(held.getAmount() > 0 ? held : null);

        sell(player, material, amount);
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // /sell all
    // ==================================================================================

    private int sellAll(Audience audience, String typed) {
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

        // Only plain stacks count, for the SPEC 17.3 cases 29 and 30 reasons: an enchanted
        // or renamed item of the same material must not be swept up at the plain price.
        int amount = PlayerShopService.count(player.getInventory(), material);
        if (amount <= 0) {
            lang.send(player, "market.you-have-none", Replies.p("item", material.name()));
            return Command.SINGLE_SUCCESS;
        }

        PlayerShopService.remove(player.getInventory(), material, amount);
        sell(player, material, amount);
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Shared
    // ==================================================================================

    private void sell(Player player, Material material, int amount) {
        MarketService market = services.get().market();
        market.sell(player.getUniqueId(), material.name(), amount)
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(java.util.logging.Level.SEVERE, "Market sale failed", error);
                        returnItems(player, material, amount);
                        lang.send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<MarketService.Receipt> failure) {
                        returnItems(player, material, amount);
                        Replies.sendFailure(player, lang, failure);
                        return;
                    }
                    MarketService.Receipt receipt = result.orElseThrow();
                    lang.send(player, "market.sold",
                            Replies.p("amount", String.valueOf(receipt.amount())),
                            Replies.p("item", receipt.item().material()),
                            Replies.p("gross", format(receipt.gross())),
                            Replies.p("tax", format(receipt.tax())),
                            Replies.p("net", format(receipt.net())),
                            Replies.p("balance", format(receipt.balance())));
                    reportQuota(player, receipt);
                }));
    }

    /**
     * SPEC 21.5's three quota messages, in the order a player meets them.
     *
     * <p>Each fires once at the moment it becomes true rather than on every sale, because the
     * quota is a fact about the day and repeating it on every stack is how a soft cap starts
     * to read as nagging.
     */
    private void reportQuota(Player player, MarketService.Receipt receipt) {
        var market = services.get().market();
        var quota = market.quota();
        if (quota.isEmpty() || !quota.get().enabled()) {
            return;
        }
        var charge = receipt.quota();
        String reset = QuotaCommand.remaining(
                quota.get().nextReset(System.currentTimeMillis()) - System.currentTimeMillis());

        if (charge.crossed()) {
            // The sale that ran the quota out. Said once, with the way around it.
            lang.send(player, "market.quota-hit",
                    Replies.p("multiplier",
                            quota.get().overQuotaMultiplier().stripTrailingZeros()
                                    .toPlainString()),
                    Replies.p("reset", reset));
            return;
        }
        if (charge.over()) {
            lang.send(player, "market.quota-over",
                    Replies.p("listed", format(receipt.listed())),
                    Replies.p("gross", format(receipt.gross())));
            return;
        }
        // SPEC 23.5.1's 80% warning, on the sale that crosses it and not after.
        java.math.BigDecimal threshold = quota.get().dailyQuota()
                .multiply(java.math.BigDecimal.valueOf(warnAtPercent()))
                .divide(java.math.BigDecimal.valueOf(100), java.math.RoundingMode.FLOOR);
        java.math.BigDecimal before = charge.used().subtract(receipt.gross());
        if (charge.used().compareTo(threshold) >= 0 && before.compareTo(threshold) < 0) {
            lang.send(player, "market.quota-warn",
                    Replies.p("percent", String.valueOf(
                            charge.used().multiply(java.math.BigDecimal.valueOf(100))
                                    .divide(quota.get().dailyQuota(),
                                            0, java.math.RoundingMode.FLOOR).intValue())),
                    Replies.p("remaining", format(charge.remaining())),
                    Replies.p("reset", reset));
        }
    }

    private int warnAtPercent() {
        return services.get().economy().configs().get(dev.civitas.config.ConfigFile.ECONOMY)
                .getInt("market.quota-warn-percent", 80);
    }

    /** Puts back what a failed sale had already taken. Overflow drops at the player's feet. */
    private void returnItems(Player player, Material material, int amount) {
        var leftovers = player.getInventory().addItem(new ItemStack(material, amount));
        leftovers.values().forEach(stack ->
                player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private String format(java.math.BigDecimal amount) {
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
