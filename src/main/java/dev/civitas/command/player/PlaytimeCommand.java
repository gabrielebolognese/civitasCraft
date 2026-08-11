package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.config.ConfigFile;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /playtime}, SPEC 22.3.
 *
 * <p>SPEC 22.1 lists it Low severity with a precise reason: "It gates several systems, so players
 * need to see it." Three of them, and none is obvious from the outside — SPEC 5.1 needs two hours
 * of <b>active</b> playtime before a city can be founded, SPEC 21.4 F12 pays no income at all for
 * the first sixty minutes, and SPEC 33.3 makes a new player un-attackable until two hours.
 *
 * <p>So this prints both figures and says what each unlocks. Printing one number would leave a
 * player who has been on for four hours wondering why they still cannot found a city, which is the
 * SPEC 4.2.1 filter working correctly and looking like a bug.
 */
public final class PlaytimeCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public PlaytimeCommand(Supplier<CivitasServices> services, LangManager lang,
                           Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("playtime")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    show(context.getSource().getSender(), null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Suggest.onlinePlayers())
                        .executes(context -> {
                            show(context.getSource().getSender(),
                                    StringArgumentType.getString(context, "player"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void show(Audience audience, String name) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return;
        }
        if (name == null && !(audience instanceof Player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return;
        }

        String target = name != null ? name : ((Player) audience).getName();
        current.lookup().resolve(target).whenComplete((resolved, error) ->
                scheduler.runOnMain(() -> {
                    if (error != null || resolved == null || resolved.isEmpty()) {
                        lang.send(audience, "player.unknown", Replies.p("player", target));
                        return;
                    }
                    read(audience, current, resolved.get());
                }));
    }

    private void read(Audience audience, CivitasServices current, PlayerLookup.Resolved found) {
        current.daos().players().findByUuid(found.uuid()).whenComplete((row, error) ->
                scheduler.runOnMain(() -> {
                    if (error != null || row == null || row.isEmpty()) {
                        logger.log(Level.FINE, "Could not read playtime", error);
                        lang.send(audience, "command.error");
                        return;
                    }
                    long total = row.get().totalPlaytimeMs();
                    long active = row.get().activePlaytimeMs();

                    lang.send(audience, "playtime.header",
                            Replies.p("player", found.name()));
                    lang.send(audience, "playtime.total", Replies.p("time", duration(total)));
                    // Both, always. SPEC 4.2.1's filter means these differ, and a player told
                    // only the first cannot work out why a gate has not opened.
                    lang.send(audience, "playtime.active", Replies.p("time", duration(active)));

                    long cityHours = current.economy().configs().get(ConfigFile.CITIES)
                            .getLong("creation.min-playtime-hours", 2);
                    lang.send(audience, active >= cityHours * 3_600_000L
                                    ? "playtime.can-found" : "playtime.cannot-found",
                            Replies.p("hours", String.valueOf(cityHours)));
                }));
    }

    /** SPEC 23.7's duration format: {@code 2d 4h}, {@code 18m}, {@code 45s}. */
    static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }
}
