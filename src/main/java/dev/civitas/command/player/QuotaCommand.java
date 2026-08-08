package dev.civitas.command.player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.economy.Money;
import dev.civitas.core.market.SellQuota;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /quota}, SPEC 22.3.
 *
 * <p>SPEC 22.1 lists this as a <b>High</b> severity omission from Part I: "the quota in 21.5 is
 * meaningless if players cannot see their remaining quota". A soft cap a player cannot inspect
 * is indistinguishable from prices that mysteriously halved, and the first thing they will do
 * is report the market as broken.
 *
 * <p>Reports what SPEC 22.3 asks for: "Used, remaining, reset time, current multiplier", plus
 * one line saying player shops are exempt — that line is the whole behavioural nudge SPEC 21.5
 * wants the quota to produce, and the moment a player reads it is when they are looking for a
 * way around the cap.
 */
public final class QuotaCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public QuotaCommand(Supplier<CivitasServices> services, LangManager lang,
                        Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("quota")
                .requires(source -> source.getSender().hasPermission("civitas.market.use"))
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    CivitasServices ready = services.get();
                    if (ready == null) {
                        lang.send(audience, "plugin.starting");
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    Optional<SellQuota> quota = ready.market().quota();
                    if (quota.isEmpty() || !quota.get().enabled()) {
                        lang.send(player, "market.quota-disabled");
                        return Command.SINGLE_SUCCESS;
                    }
                    report(player, quota.get());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private void report(Player player, SellQuota quota) {
        long now = System.currentTimeMillis();
        quota.status(player.getUniqueId(), now)
                .whenComplete((status, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    send(player, status, now);
                }));
    }

    private void send(Player player, SellQuota.Status status, long now) {
        lang.send(player, "market.quota-status",
                Replies.p("used", format(status.used())),
                Replies.p("quota", format(status.quota())));
        lang.send(player, "market.quota-status-remaining",
                Replies.p("remaining", format(status.remaining())),
                Replies.p("percent", String.valueOf(status.percent())));
        lang.send(player, "market.quota-status-rate",
                Replies.p("multiplier", rate(status.multiplier())));
        lang.send(player, "market.quota-status-reset",
                Replies.p("reset", remaining(status.resetsAt() - now)));
        lang.send(player, "market.quota-status-shops");
    }

    private String format(BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    /** {@code 1} rather than {@code 1.00}, and {@code 0.2} rather than {@code 0.20}. */
    private static String rate(BigDecimal multiplier) {
        return multiplier.stripTrailingZeros().toPlainString();
    }

    /**
     * How long until the reset, coarsely.
     *
     * <p>Local rather than shared: SPEC 23.7 asks for one central duration formatter and
     * assigns it to the message framework in M7a. Building a shared one here would be
     * building part of that milestone early, and the version M7a wants is the one every
     * other message will use.
     */
    static String remaining(long millis) {
        if (millis <= 0) {
            return "0m";
        }
        long minutes = BigDecimal.valueOf(millis)
                .divide(BigDecimal.valueOf(60_000), 0, RoundingMode.CEILING).longValue();
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours <= 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }
}
