package dev.civitas.command.player;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.storage.row.LedgerRow;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /transactions}, SPEC 22.3.
 *
 * <h2>SPEC 22.1 rates this Critical, and the reason is stark</h2>
 *
 * <p>"A player had <b>no way to see their own transaction history</b>. Only admins could."
 *
 * <p>Every coin a player has ever earned or spent is in the ledger, SPEC 1.5 makes it the
 * authority for exactly that reason, and until now the only route to it was asking a moderator to
 * run {@code /ca ledger}. That is a strange thing to have to do about your own money.
 *
 * <p>Read from the ledger rather than from a cache, so what a player sees is what an admin sees.
 * A player-facing view that could disagree with the audit trail would be worse than none.
 */
public final class TransactionsCommand {

    /** SPEC 22.3: "last 30 days", and SPEC 22.2 rule 5 paginates every list at ten. */
    private static final int DEFAULT_DAYS = 30;
    private static final int PAGE_SIZE = 10;

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public TransactionsCommand(Supplier<CivitasServices> services, LangManager lang,
                               Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("transactions")
                .requires(source -> source.getSender().hasPermission("civitas.economy.balance"))
                .executes(context -> {
                    show(context.getSource().getSender(), null, 1);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            show(context.getSource().getSender(), null,
                                    IntegerArgumentType.getInteger(context, "page"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("type")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(TransactionsCommand::suggestTypes)
                                .executes(context -> {
                                    show(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "type"), 1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> {
                                            show(context.getSource().getSender(),
                                                    StringArgumentType.getString(context, "type"),
                                                    IntegerArgumentType.getInteger(context,
                                                            "page"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .build();
    }

    private void show(Audience audience, String type, int page) {
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return;
        }
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(player, "plugin.starting");
            return;
        }

        long since = System.currentTimeMillis() - (long) DEFAULT_DAYS * 86_400_000L;
        current.daos().ledger().findByPlayer(player.getUniqueId(), since, 500)
                .whenComplete((rows, error) -> scheduler.runOnMain(() -> {
                    if (error != null || rows == null) {
                        logger.log(Level.WARNING, "Could not read transactions for "
                                + player.getName(), error);
                        lang.send(player, "command.error");
                        return;
                    }
                    print(player, current, filter(rows, type), type, page);
                }));
    }

    private static List<LedgerRow> filter(List<LedgerRow> rows, String type) {
        if (type == null) {
            return rows;
        }
        String wanted = type.toUpperCase(Locale.ROOT);
        return rows.stream().filter(row -> row.type().equalsIgnoreCase(wanted)).toList();
    }

    private void print(Player player, CivitasServices current, List<LedgerRow> rows, String type,
                       int page) {
        if (rows.isEmpty()) {
            lang.send(player, type == null ? "transactions.none" : "transactions.none-of-type",
                    Replies.p("type", String.valueOf(type)));
            return;
        }

        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int wanted = Math.min(Math.max(1, page), pages);
        int from = (wanted - 1) * PAGE_SIZE;
        int to = Math.min(rows.size(), from + PAGE_SIZE);

        lang.send(player, "transactions.header",
                Replies.p("days", String.valueOf(DEFAULT_DAYS)),
                Replies.p("page", String.valueOf(wanted)),
                Replies.p("pages", String.valueOf(pages)),
                Replies.p("count", String.valueOf(rows.size())));

        for (LedgerRow row : rows.subList(from, to)) {
            // The sign carries the meaning, and SPEC 36.5 requires it to carry it without colour:
            // "Every positive amount carries a +, every negative carries a -."
            lang.send(player, row.amount().signum() >= 0
                            ? "transactions.credit" : "transactions.debit",
                    Replies.p("type", pretty(row.type())),
                    Replies.p("amount", Money.format(row.amount().abs(),
                            current.economy().configs())),
                    Replies.p("balance", Money.format(row.balanceAfter(),
                            current.economy().configs())),
                    Replies.p("when", stamp(row.timestamp())));
        }
    }

    /** {@code MARKET_SELL} reads as "Market sell" — a player did not choose the enum's name. */
    private static String pretty(String type) {
        String lower = type.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String stamp(long at) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(at));
    }

    private static java.util.concurrent.CompletableFuture<
            com.mojang.brigadier.suggestion.Suggestions> suggestTypes(
                    com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toUpperCase(Locale.ROOT);
        for (TransactionType type : TransactionType.values()) {
            if (type.name().startsWith(prefix)) {
                builder.suggest(type.name());
            }
        }
        return builder.buildFuture();
    }
}
