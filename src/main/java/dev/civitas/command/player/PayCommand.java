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
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /pay}, SPEC 9.1.
 *
 * <p>The amount is parsed by {@link Money}, which floors it and refuses anything that is not
 * a plain positive decimal (SPEC 17.3 case 26, SPEC 17.5 case 68). Everything else, including
 * paying yourself, is the economy service's business.
 */
public final class PayCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public PayCommand(Supplier<CivitasServices> services, LangManager lang,
                      Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("pay")
                .requires(source -> source.getSender().hasPermission("civitas.economy.pay"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .filter(name -> name.toLowerCase()
                                            .startsWith(builder.getRemaining().toLowerCase()))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(this::pay)))
                .build();
    }

    private int pay(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (services.get() == null) {
            lang.send(context.getSource().getSender(), "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player player)) {
            lang.send(context.getSource().getSender(), Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }

        Result<BigDecimal> amount = Money.parse(StringArgumentType.getString(context, "amount"));
        if (amount instanceof Result.Failure<BigDecimal> failure) {
            Replies.sendFailure(player, lang, failure);
            return Command.SINGLE_SUCCESS;
        }
        BigDecimal moved = amount.orElseThrow();

        String typed = StringArgumentType.getString(context, "player");
        PlayerLookup lookup = services.get().lookup();

        lookup.resolve(typed).whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(java.util.logging.Level.SEVERE, "Payment lookup failed", error);
                lang.send(player, "command.error");
                return;
            }
            if (resolved == null || resolved.isEmpty()) {
                lang.send(player, "player.unknown", Replies.p("player", typed));
                return;
            }

            PlayerLookup.Resolved target = resolved.get();
            Replies.reply(services.get().economy().pay(player.getUniqueId(), target.uuid(), moved),
                    player, lang, scheduler, logger,
                    remaining -> {
                        lang.send(player, "economy.pay-sent",
                                Replies.p("amount", format(moved)),
                                Replies.p("player", target.name()),
                                Replies.p("balance", format(remaining)));

                        Player online = Bukkit.getPlayer(target.uuid());
                        if (online != null) {
                            lang.send(online, "economy.pay-received",
                                    Replies.p("amount", format(moved)),
                                    Replies.p("player", player.getName()));
                        }
                    });
        }));
        return Command.SINGLE_SUCCESS;
    }

    private String format(BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }
}
