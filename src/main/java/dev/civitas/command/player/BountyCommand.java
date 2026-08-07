package dev.civitas.command.player;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.storage.row.BountyRow;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /bounty}, SPEC 4.7 and SPEC 9.1.
 *
 * <p>Two forms: placing one, and listing what is outstanding. Placing takes the money at once,
 * which the service does inside a transaction with the row, so a bounty cannot exist unpaid.
 *
 * <p>Nothing here decides when a bounty pays out. That is the kill listener's job, and it is
 * where SPEC 4.7's restriction lives: only a kill during an active war collects.
 */
public final class BountyCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public BountyCommand(Supplier<CivitasServices> services, LangManager lang,
                         Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("bounty")
                .requires(source -> source.getSender().hasPermission("civitas.bounty.use"))
                .executes(context -> {
                    list(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("list")
                        .executes(context -> {
                            list(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                                            .startsWith(builder.getRemaining()
                                                    .toLowerCase(java.util.Locale.ROOT)))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(this::place)))
                .build();
    }

    private int place(CommandContext<CommandSourceStack> context) {
        CivitasServices current = services.get();
        if (current == null) {
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
        BigDecimal staked = amount.orElseThrow();
        String typed = StringArgumentType.getString(context, "player");

        current.lookup().resolve(typed).whenComplete((resolved, error) ->
                scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Bounty lookup failed", error);
                        lang.send(player, "command.error");
                        return;
                    }
                    if (resolved == null || resolved.isEmpty()) {
                        lang.send(player, "player.unknown", Replies.p("player", typed));
                        return;
                    }
                    PlayerLookup.Resolved target = resolved.get();
                    Replies.reply(current.bounties().place(player.getUniqueId(), target.uuid(),
                                    staked, System.currentTimeMillis()),
                            player, lang, scheduler, logger,
                            placed -> {
                                lang.send(player, "bounty.placed",
                                        Replies.p("amount", format(placed.amount())),
                                        Replies.p("player", target.name()),
                                        Replies.p("days",
                                                String.valueOf(current.bounties().expiryDays())));
                                Player online = Bukkit.getPlayer(target.uuid());
                                if (online != null) {
                                    // The target is told. A bounty nobody knows about cannot
                                    // change how anybody plays, which is the point of one.
                                    lang.send(online, "bounty.on-you",
                                            Replies.p("amount", format(placed.amount())));
                                }
                            });
                }));
        return Command.SINGLE_SUCCESS;
    }

    private void list(net.kyori.adventure.audience.Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return;
        }

        current.bounties().listOpen().whenComplete((rows, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Bounty listing failed", error);
                lang.send(audience, "command.error");
                return;
            }
            if (rows == null || rows.isEmpty()) {
                lang.send(audience, "bounty.list-empty");
                return;
            }
            lang.sendRaw(audience, "bounty.list-header");
            for (BountyRow row : rows) {
                lang.sendRaw(audience, "bounty.list-entry",
                        Replies.p("player", nameOf(row.targetUuid())),
                        Replies.p("amount", format(row.amount())),
                        Replies.p("placer", nameOf(row.placerUuid())));
            }
        }));
    }

    private String nameOf(java.util.UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String format(BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }
}
