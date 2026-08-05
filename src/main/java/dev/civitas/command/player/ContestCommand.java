package dev.civitas.command.player;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.contest.Contest;
import dev.civitas.core.contest.ContestService;
import dev.civitas.core.contest.PlotRegion;
import dev.civitas.core.contest.VoteAxis;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.storage.row.ContestEntryRow;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /contest}, SPEC 9.1 and SPEC 13.4.
 *
 * <p>Six things a player can do with a contest: see what it is, mark the two corners of an
 * entry, submit it, list what everyone else entered, go and look at one, and score it.
 */
public final class ContestCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public ContestCommand(Supplier<CivitasServices> services, LangManager lang,
                          Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("contest")
                .requires(source -> source.getSender().hasPermission("civitas.contest.use"))
                .executes(context -> {
                    info(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("mark").executes(context -> {
                    mark(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("submit").executes(context -> {
                    submit(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("list").executes(context -> {
                    list(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("visit")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    visit(context.getSource().getSender(),
                                            IntegerArgumentType.getInteger(context, "entry"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("vote")
                        .then(Commands.argument("entry", IntegerArgumentType.integer(1))
                                .then(Commands.argument("creativity", IntegerArgumentType.integer())
                                        .then(Commands.argument("technical", IntegerArgumentType.integer())
                                                .then(Commands.argument("theme", IntegerArgumentType.integer())
                                                        .executes(context -> {
                                                            vote(context.getSource().getSender(),
                                                                    IntegerArgumentType.getInteger(context, "entry"),
                                                                    IntegerArgumentType.getInteger(context, "creativity"),
                                                                    IntegerArgumentType.getInteger(context, "technical"),
                                                                    IntegerArgumentType.getInteger(context, "theme"));
                                                            return Command.SINGLE_SUCCESS;
                                                        }))))))
                .build();
    }

    // ==================================================================================
    // Information
    // ==================================================================================

    private void info(Audience audience) {
        ContestService contests = contestsOrNull(audience);
        if (contests == null) {
            return;
        }
        Optional<Contest> running = contests.current();
        if (running.isEmpty()) {
            lang.send(audience, "contest.none");
            return;
        }

        Contest contest = running.get();
        long now = System.currentTimeMillis();
        lang.sendRaw(audience, "contest.info",
                Replies.p("theme", contest.theme()),
                Replies.p("phase", plain(contest.state().messageKey())),
                Replies.p("remaining", describe(contest.millisUntilNextPhase(now))));
    }

    private void list(Audience audience) {
        ContestService contests = contestsOrNull(audience);
        if (contests == null) {
            return;
        }
        contests.submittedEntries().whenComplete((entries, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(audience, "command.error");
                return;
            }
            if (entries.isEmpty()) {
                lang.send(audience, "contest.no-entries");
                return;
            }
            lang.sendRaw(audience, "contest.list.header");
            int index = 1;
            for (ContestEntryRow entry : entries) {
                lang.sendRaw(audience, "contest.list.entry",
                        Replies.p("index", String.valueOf(index++)),
                        Replies.p("city", cityName(entry.cityId())),
                        Replies.p("score", String.format("%.2f", entry.score())));
            }
        }));
    }

    // ==================================================================================
    // Entering, SPEC 13.4 steps 2 and 3
    // ==================================================================================

    private void mark(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City city = cityOrNull(player);
        if (city == null) {
            return;
        }

        Location at = player.getLocation();
        services.get().contests().mark(player.getUniqueId(), city, at.getWorld().getName(),
                        at.getBlockX(), at.getBlockY(), at.getBlockZ(), System.currentTimeMillis())
                .whenComplete((result, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        logger.log(java.util.logging.Level.SEVERE, "Contest mark failed", error);
                        lang.send(player, "command.error");
                        return;
                    }
                    if (result instanceof Result.Failure<PlotRegion> failure) {
                        Replies.sendFailure(player, lang, failure);
                        return;
                    }
                    PlotRegion region = result.orElse(null);
                    if (region == null) {
                        lang.send(player, "contest.marked-first",
                                Replies.p("x", String.valueOf(at.getBlockX())),
                                Replies.p("y", String.valueOf(at.getBlockY())),
                                Replies.p("z", String.valueOf(at.getBlockZ())));
                        return;
                    }
                    lang.send(player, "contest.marked-region",
                            Replies.p("width", String.valueOf(region.width())),
                            Replies.p("height", String.valueOf(region.height())),
                            Replies.p("depth", String.valueOf(region.depth())));
                }));
    }

    private void submit(Audience audience) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        City city = cityOrNull(player);
        if (city == null) {
            return;
        }

        Replies.reply(services.get().contests().submit(player.getUniqueId(), city,
                        System.currentTimeMillis()),
                player, lang, scheduler, logger,
                entry -> lang.send(player, "contest.submitted",
                        Replies.p("city", city.name())));
    }

    // ==================================================================================
    // Visiting and voting, SPEC 13.4 step 4
    // ==================================================================================

    private void visit(Audience audience, int index) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        withEntry(player, index, entry -> {
            Optional<PlotRegion> region = PlotRegion.parse(entry.plotRegion());
            if (region.isEmpty()) {
                lang.send(player, "contest.unknown-entry");
                return;
            }
            World world = player.getServer().getWorld(region.get().world());
            if (world == null) {
                lang.send(player, "contest.unknown-entry");
                return;
            }

            // Above the build rather than inside it. The entry sits in the entrant's own
            // claims, so M4's protection already stops a visitor touching anything; the
            // height is so they arrive looking at it rather than in the middle of it.
            int x = (int) Math.floor(region.get().centreX());
            int z = (int) Math.floor(region.get().centreZ());
            int y = Math.max(region.get().maxY() + viewHeight(), world.getHighestBlockYAt(x, z) + 1);

            player.teleportAsync(new Location(world, x + 0.5, y, z + 0.5));
            lang.send(player, "contest.visiting",
                    Replies.p("city", cityName(entry.cityId())));
        });
    }

    private void vote(Audience audience, int index, int creativity, int technical, int theme) {
        Player player = playerOrNull(audience);
        if (player == null) {
            return;
        }
        withEntry(player, index, entry -> {
            Map<VoteAxis, Integer> scores = new EnumMap<>(VoteAxis.class);
            scores.put(VoteAxis.CREATIVITY, creativity);
            scores.put(VoteAxis.TECHNICAL_SKILL, technical);
            scores.put(VoteAxis.THEME_FIT, theme);

            Replies.reply(services.get().contests().vote(player.getUniqueId(), entry.id(), scores,
                            System.currentTimeMillis()),
                    player, lang, scheduler, logger,
                    vote -> lang.send(player, "contest.voted",
                            Replies.p("city", cityName(entry.cityId())),
                            Replies.p("score", String.format("%.1f", vote.combined()))));
        });
    }

    /** Resolves the 1-based number a player typed against the submitted entries. */
    private void withEntry(Player player, int index, java.util.function.Consumer<ContestEntryRow> action) {
        services.get().contests().submittedEntries()
                .whenComplete((entries, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    if (index < 1 || index > entries.size()) {
                        lang.send(player, "contest.unknown-entry");
                        return;
                    }
                    action.accept(entries.get(index - 1));
                }));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private int viewHeight() {
        return services.get().contests().maxRegionSize() > 0
                ? Math.min(16, services.get().contests().maxRegionSize())
                : 16;
    }

    private String cityName(int cityId) {
        return services.get().registry().city(cityId).map(City::name).orElse("?");
    }

    private String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 24) {
            return TimeUnit.MILLISECONDS.toDays(millis) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

    private ContestService contestsOrNull(Audience audience) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!current.contests().isEnabled()) {
            lang.send(audience, "contest.disabled");
            return null;
        }
        return current.contests();
    }

    private Player playerOrNull(Audience audience) {
        if (contestsOrNull(audience) == null) {
            return null;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        return player;
    }

    private City cityOrNull(Player player) {
        Optional<City> city = services.get().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return null;
        }
        return city.get();
    }
}
