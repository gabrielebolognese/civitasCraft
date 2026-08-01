package dev.civitas.command.player;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.market.MarketService;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /worth [item]}, SPEC 9.1.
 *
 * <p>Reads the price cache, so it costs nothing to spam while deciding what to farm, which
 * is exactly what a player will do with it.
 */
public final class WorthCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;

    public WorthCommand(Supplier<CivitasServices> services, LangManager lang) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("worth")
                .requires(source -> source.getSender().hasPermission("civitas.market.use"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    if (notReady(audience)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    ItemStack held = player.getInventory().getItemInMainHand();
                    if (held.getType().isAir()) {
                        lang.send(player, "market.empty-hand");
                        return Command.SINGLE_SUCCESS;
                    }
                    report(player, held.getType().name(), held.getAmount());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(MarketSuggestions.materials(services))
                        .executes(context -> {
                            Audience audience = context.getSource().getSender();
                            if (notReady(audience)) {
                                return Command.SINGLE_SUCCESS;
                            }
                            report(audience, StringArgumentType.getString(context, "item"), 1);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void report(Audience audience, String material, int held) {
        MarketService market = services.get().market();
        Optional<MarketService.Quote> quote = market.quote(material);
        if (quote.isEmpty()) {
            lang.send(audience, "market.not-traded", Replies.p("item", material));
            return;
        }

        MarketService.Quote current = quote.get();
        lang.send(audience, "market.worth",
                Replies.p("item", current.item().material()),
                Replies.p("sell", format(current.sellPrice())),
                Replies.p("buy", format(current.buyPrice())));

        if (held > 1) {
            BigDecimal gross = market.previewSell(material, held).orElse(BigDecimal.ZERO);
            lang.send(audience, "market.worth-stack",
                    Replies.p("amount", String.valueOf(held)),
                    Replies.p("total", format(gross)));
        }
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

    /** Shared by {@code /worth} and {@code /sell all}. */
    static final class MarketSuggestions {

        private MarketSuggestions() {
        }

        static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> materials(
                Supplier<CivitasServices> services) {
            return (context, builder) -> {
                CivitasServices current = services.get();
                if (current != null) {
                    String typed = builder.getRemaining().toUpperCase(java.util.Locale.ROOT);
                    current.market().registry().catalogue().stream()
                            .map(item -> item.material())
                            .filter(name -> name.startsWith(typed))
                            .forEach(builder::suggest);
                }
                return builder.buildFuture();
            };
        }
    }
}
