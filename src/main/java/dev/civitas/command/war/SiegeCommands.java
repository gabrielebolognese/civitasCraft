package dev.civitas.command.war;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.economy.Money;
import dev.civitas.core.siege.SiegeCamp;
import dev.civitas.core.siege.SiegeService;
import dev.civitas.core.siege.SiegeUnitType;
import dev.civitas.core.war.War;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * {@code /war siege}, the interface to SPEC 29.
 *
 * <p><b>SPEC defines no command and no GUI for siege.</b> Section 29 describes camps and units
 * fully and says nothing about how a player reaches either; SPEC 9.3's {@code /war} tree has no
 * siege entry and SPEC 8.8's Wars menu has no siege slot. A system with no way to drive it is
 * inert, which is the same reasoning that shipped {@code /ca warp set} at M3b and {@code /toggle}
 * at M7a, so this is the minimum surface that makes SPEC 29 reachable: plant a camp, see what a
 * camp costs and where it may go, buy a unit, and list what has been fielded.
 */
public final class SiegeCommands {

    /** What a camp's marker block is made of. A banner, per SPEC 29.5's own wording. */
    private static final Material CAMP_BLOCK = Material.WHITE_BANNER;

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public SiegeCommands(Supplier<CivitasServices> services, LangManager lang, Scheduler scheduler,
                         Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("siege")
                .executes(context -> {
                    status(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("camp")
                        .executes(context -> {
                            placeCamp(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("buy")
                        .then(Commands.argument("unit", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    CivitasServices current = services.get();
                                    if (current != null && current.siege() != null) {
                                        String prefix = builder.getRemaining()
                                                .toLowerCase(java.util.Locale.ROOT);
                                        for (SiegeUnitType type
                                                : current.siege().catalogue().all()) {
                                            if (type.key().startsWith(prefix)) {
                                                builder.suggest(type.key());
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    buy(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "unit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("list")
                        .executes(context -> {
                            list(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    // ==================================================================================

    private void status(org.bukkit.command.CommandSender sender) {
        Context context = contextOf(sender);
        if (context == null) {
            return;
        }
        Player player = context.player();
        SiegeService siege = context.siege();

        lang.send(player, "siege.status-header",
                LangManager.placeholder("total", String.valueOf(context.war().siegeCapacity())));

        siege.pointsSpent(context.war().id(), context.city().id())
                .thenAccept(spent -> scheduler.runOnMain(() -> lang.send(player, "siege.status-budget",
                        LangManager.placeholder("used", String.valueOf(spent)),
                        LangManager.placeholder("total",
                                String.valueOf(context.war().siegeCapacity())))));

        Optional<SiegeCamp> camp = siege.campOf(context.war().id(), context.city().id());
        if (camp.isEmpty()) {
            lang.send(player, "siege.status-no-camp",
                    LangManager.placeholder("cost", money(siege.campCost(false))));
        } else {
            SiegeCamp standing = camp.get();
            lang.send(player, standing.stands() ? "siege.status-camp" : "siege.status-camp-lost",
                    LangManager.placeholder("x", String.valueOf(standing.x())),
                    LangManager.placeholder("z", String.valueOf(standing.z())),
                    LangManager.placeholder("health",
                            String.valueOf((long) standing.health())));
        }
    }

    private void placeCamp(org.bukkit.command.CommandSender sender) {
        Context context = contextOf(sender);
        if (context == null) {
            return;
        }
        Player player = context.player();
        Block block = player.getLocation().getBlock();

        Replies.reply(context.siege().placeCamp(player.getUniqueId(), context.war(),
                        context.city(), block.getWorld().getName(), block.getX(), block.getY(),
                        block.getZ(), System.currentTimeMillis()),
                player, lang, scheduler, logger,
                camp -> {
                    // The marker goes down only once the money has moved, so a refused placement
                    // never leaves a banner standing for a camp that does not exist.
                    block.setType(CAMP_BLOCK);
                    lang.send(player, "siege.camp-placed",
                            LangManager.placeholder("x", String.valueOf(camp.x())),
                            LangManager.placeholder("z", String.valueOf(camp.z())),
                            LangManager.placeholder("health",
                                    String.valueOf((long) camp.health())));
                });
    }

    private void buy(org.bukkit.command.CommandSender sender, String key) {
        Context context = contextOf(sender);
        if (context == null) {
            return;
        }
        Player player = context.player();
        Optional<SiegeUnitType> type = context.siege().catalogue().byKey(key);
        if (type.isEmpty()) {
            lang.send(player, "siege.unknown-unit");
            return;
        }

        Replies.reply(context.siege().buy(player.getUniqueId(), context.war(), context.city(),
                        type.get(), System.currentTimeMillis()),
                player, lang, scheduler, logger,
                row -> {
                    services.get().siegeSpawner().spawn(row, type.get());
                    lang.send(player, "siege.bought",
                            LangManager.placeholder("unit", type.get().displayName()),
                            LangManager.placeholder("cost", money(type.get().cost())));
                });
    }

    private void list(org.bukkit.command.CommandSender sender) {
        Context context = contextOf(sender);
        if (context == null) {
            return;
        }
        Player player = context.player();
        context.siege().unitsOf(context.war().id()).thenAccept(rows -> scheduler.runOnMain(() -> {
            List<?> mine = rows.stream()
                    .filter(row -> row.cityId() == context.city().id())
                    .toList();
            if (mine.isEmpty()) {
                lang.send(player, "siege.list-empty");
                return;
            }
            lang.send(player, "siege.list-header",
                    LangManager.placeholder("count", String.valueOf(mine.size())));
            rows.stream()
                    .filter(row -> row.cityId() == context.city().id())
                    .forEach(row -> lang.send(player, "siege.list-entry",
                            LangManager.placeholder("unit", row.type()),
                            LangManager.placeholder("points", String.valueOf(row.points())),
                            LangManager.placeholder("state",
                                    lang.plain(row.alive() ? "siege.alive" : "siege.dead"))));
        }));
    }

    // ==================================================================================

    private String money(java.math.BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    /** The four things every subcommand needs, resolved once with one refusal each. */
    private record Context(Player player, City city, War war, SiegeService siege) {
    }

    private Context contextOf(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, dev.civitas.lang.Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        CivitasServices current = services.get();
        if (current == null || current.siege() == null) {
            lang.send(player, "plugin.starting");
            return null;
        }
        Optional<City> city = current.registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return null;
        }
        Optional<War> war = current.wars().registry().engagedWarOf(city.get().id());
        if (war.isEmpty()) {
            lang.send(player, "siege.no-war");
            return null;
        }
        return new Context(player, city.get(), war.get(), current.siege());
    }
}
