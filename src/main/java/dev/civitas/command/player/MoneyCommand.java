package dev.civitas.command.player;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /money} and {@code /balance}, SPEC 9.1.
 *
 * <p>Reading your own balance never touches the database: SPEC 2.3 keeps balances in memory
 * for exactly this. Looking up another player does, but only because their name has to be
 * resolved to a UUID first.
 */
public final class MoneyCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public MoneyCommand(Supplier<CivitasServices> services, LangManager lang,
                        Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("money")
                .requires(source -> source.getSender().hasPermission("civitas.economy.balance"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    if (notReady(audience)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    BigDecimal balance =
                            services.get().economy().balanceOrZero(player.getUniqueId());
                    lang.send(player, "economy.balance-self",
                            Replies.p("amount", format(balance)));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(dev.civitas.command.Suggest.onlinePlayers())
                        .executes(context -> {
                            Audience audience = context.getSource().getSender();
                            if (notReady(audience)) {
                                return Command.SINGLE_SUCCESS;
                            }
                            String typed = StringArgumentType.getString(context, "player");
                            lookupAndReport(audience, typed);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void lookupAndReport(Audience audience, String typed) {
        PlayerLookup lookup = services.get().lookup();
        lookup.resolve(typed).whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(java.util.logging.Level.SEVERE, "Balance lookup failed", error);
                lang.send(audience, "command.error");
                return;
            }
            if (resolved == null || resolved.isEmpty()) {
                lang.send(audience, "player.unknown", Replies.p("player", typed));
                return;
            }
            BigDecimal balance = services.get().economy().balanceOrZero(resolved.get().uuid());
            lang.send(audience, "economy.balance-other",
                    Replies.p("player", resolved.get().name()),
                    Replies.p("amount", format(balance)));
        }));
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
