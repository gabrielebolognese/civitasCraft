package dev.civitas.command.city;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.economy.Money;
import dev.civitas.core.outpost.Outpost;
import dev.civitas.core.outpost.OutpostCostEngine;
import dev.civitas.core.outpost.OutpostService;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.msg.Formats;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The six {@code /city outpost} subcommands, SPEC 7.3.
 *
 * <p>Split out of {@link CityCommand} because that class is already the longest in the plugin
 * and this is a self-contained subtree.
 */
public final class OutpostCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public OutpostCommands(Supplier<CivitasServices> services, LangManager lang,
                           Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ArgumentBuilder<CommandSourceStack, ?> build() {
        return Commands.literal("outpost")
                .executes(context -> list(context.getSource().getSender()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource().getSender())))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> create(context,
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("delete")
                        .then(named("name", this::delete)))
                .then(Commands.literal("tp")
                        .then(named("name", this::teleport)))
                .then(Commands.literal("setwarp")
                        .then(named("name", this::setWarp)))
                // The old name is an outpost the city already has, so it is offered the
                // same way delete, tp and setwarp offer it. The new one is invented and
                // cannot be.
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(outpostNames())
                                .then(Commands.argument("new", StringArgumentType.word())
                                        .executes(this::rename))))
                // SPEC 39.11, new with the SPEC 39 rework: an outpost is one to four chunks
                // now, so it can be grown and trimmed rather than only created and deleted.
                .then(Commands.literal("claim")
                        .then(named("name", this::claimChunk)))
                .then(Commands.literal("unclaim")
                        .executes(this::unclaimChunk))
                .then(Commands.literal("info")
                        .then(named("name", this::info)))
                .then(Commands.literal("cost")
                        .executes(this::cost));
    }

    /**
     * Completes against the outposts the sender's own city has.
     *
     * <p>Separate from {@link #named} because {@code rename} needs the suggestions without
     * the executor: it takes a second argument, and an argument node that both executes and
     * has a child would run the handler for {@code /city outpost rename Foo} with no new name
     * to read, throwing where a usage message belongs.
     */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>
            outpostNames() {
        return (context, builder) -> {
            CivitasServices current = services.get();
            if (current != null && context.getSource().getSender() instanceof Player player) {
                current.registry().cityOf(player.getUniqueId()).ifPresent(city ->
                        current.outposts().registry().of(city.id()).stream()
                                .map(Outpost::name)
                                .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                                        .startsWith(builder.getRemaining()
                                                .toLowerCase(java.util.Locale.ROOT)))
                                .forEach(builder::suggest));
            }
            return builder.buildFuture();
        };
    }

    /** An outpost-name argument that completes, and runs {@code handler} when it ends there. */
    private ArgumentBuilder<CommandSourceStack, ?> named(
            String argument, java.util.function.BiFunction<CommandContext<CommandSourceStack>,
            String, Integer> handler) {
        return Commands.argument(argument, StringArgumentType.word())
                .suggests(outpostNames())
                .executes(context -> handler.apply(context,
                        StringArgumentType.getString(context, argument)));
    }

    // ==================================================================================
    // The subcommands
    // ==================================================================================

    private int list(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }

        List<Outpost> all = services.get().outposts().registry().of(context.city().id());
        lang.sendRaw(audience, "outpost.list-header",
                LangManager.placeholder("count", String.valueOf(all.size())),
                LangManager.placeholder("max", String.valueOf(
                        services.get().outposts().maxOutposts(context.city()))));

        if (all.isEmpty()) {
            lang.sendRaw(audience, "outpost.list-empty");
            return Command.SINGLE_SUCCESS;
        }
        for (Outpost outpost : all) {
            Optional<Claim> chunk = services.get().outposts().claimOf(outpost);
            lang.sendRaw(audience, "outpost.list-entry",
                    LangManager.placeholder("name", outpost.name()),
                    LangManager.placeholder("world", chunk.map(Claim::world).orElse("?")),
                    LangManager.placeholder("x", chunk.map(claim ->
                            String.valueOf(claim.chunkX() * 16)).orElse("?")),
                    LangManager.placeholder("z", chunk.map(claim ->
                            String.valueOf(claim.chunkZ() * 16)).orElse("?")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> command, String name) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        Location at = player.getLocation();

        Replies.reply(services.get().outposts().create(player.getUniqueId(), context.city(),
                        name, at.getWorld().getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4,
                        at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()),
                player, lang, scheduler, logger,
                outpost -> lang.send(player, "outpost.created",
                        LangManager.placeholder("name", outpost.name())));
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> command, String name) {
        Named target = namedOutpost(command, name);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        Replies.reply(services.get().outposts()
                        .delete(target.player().getUniqueId(), target.city(), target.outpost()),
                target.player(), lang, scheduler, logger,
                outpost -> lang.send(target.player(), "outpost.deleted",
                        LangManager.placeholder("name", outpost.name())));
        return Command.SINGLE_SUCCESS;
    }

    private int teleport(CommandContext<CommandSourceStack> command, String name) {
        Named target = namedOutpost(command, name);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        Result<Long> started = services.get().outpostTeleport()
                .request(target.player(), target.city(), target.outpost());
        if (started instanceof Result.Failure<Long> failure) {
            Replies.sendFailure(target.player(), lang, failure);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setWarp(CommandContext<CommandSourceStack> command, String name) {
        Named target = namedOutpost(command, name);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        Location at = target.player().getLocation();
        Replies.reply(services.get().outposts().setWarp(target.player().getUniqueId(),
                        target.city(), target.outpost(), at.getWorld().getName(), at.getX(),
                        at.getY(), at.getZ(), at.getYaw(), at.getPitch()),
                target.player(), lang, scheduler, logger,
                outpost -> lang.send(target.player(), "outpost.warp-set",
                        LangManager.placeholder("name", outpost.name())));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /city outpost claim <name>}, SPEC 39.11.
     *
     * <p>Adds the chunk the player is standing in to an outpost they name. The name is required
     * rather than inferred from proximity: a city may hold six outposts, and guessing which one
     * a player meant is how somebody spends two million coins on the wrong one.
     */
    private int claimChunk(CommandContext<CommandSourceStack> command, String name) {
        Named target = namedOutpost(command, name);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = target.player();
        Location at = player.getLocation();

        Replies.reply(services.get().outposts().expand(player.getUniqueId(), target.city(),
                        target.outpost(), at.getWorld().getName(),
                        at.getBlockX() >> 4, at.getBlockZ() >> 4),
                player, lang, scheduler, logger,
                claim -> lang.send(player, "outpost.chunk-claimed",
                        LangManager.placeholder("name", target.outpost().name()),
                        LangManager.placeholder("chunks", String.valueOf(
                                services.get().outposts().chunkCount(target.outpost()))),
                        LangManager.placeholder("max", String.valueOf(
                                services.get().outposts().maxChunksPerOutpost()))));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /city outpost unclaim}, SPEC 39.11.
     *
     * <p>No name argument: the chunk a player is standing in belongs to exactly one outpost, so
     * there is nothing to disambiguate and asking would be asking a question the game can
     * already answer.
     */
    private int unclaimChunk(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        Location at = player.getLocation();

        Replies.reply(services.get().outposts().unclaimChunk(player.getUniqueId(),
                        context.city(), at.getWorld().getName(),
                        at.getBlockX() >> 4, at.getBlockZ() >> 4),
                player, lang, scheduler, logger,
                claim -> lang.send(player, "outpost.chunk-unclaimed",
                        LangManager.placeholder("x", String.valueOf(claim.chunkX())),
                        LangManager.placeholder("z", String.valueOf(claim.chunkZ()))));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /city outpost info <name>}, SPEC 39.11. */
    private int info(CommandContext<CommandSourceStack> command, String name) {
        Named target = namedOutpost(command, name);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        OutpostService service = services.get().outposts();
        Player player = target.player();
        Outpost outpost = target.outpost();

        lang.sendRaw(player, "outpost.info-header",
                LangManager.placeholder("name", outpost.name()));
        lang.sendRaw(player, "outpost.info-chunks",
                LangManager.placeholder("chunks", String.valueOf(service.chunkCount(outpost))),
                LangManager.placeholder("max", String.valueOf(service.maxChunksPerOutpost())));
        lang.sendRaw(player, "outpost.info-distance",
                LangManager.placeholder("blocks",
                        Formats.count(Math.round(service.blocksFromCore(target.city(), outpost)))));
        lang.sendRaw(player, "outpost.info-upkeep",
                LangManager.placeholder("amount", money(service.upkeepFor(target.city(), outpost))));
        lang.sendRaw(player, "outpost.info-teleport",
                LangManager.placeholder("amount",
                        money(service.teleportCost(target.city(), outpost))));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /city outpost cost}, SPEC 39.11's own addition to the command set.
     *
     * <p>SPEC argues for it directly: "A formula with four terms is opaque unless the game shows
     * its work, and a player about to spend two million coins deserves to see exactly why." So
     * this prints every term rather than a total.
     */
    private int cost(CommandContext<CommandSourceStack> command) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player player = context.player();
        Location at = player.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        OutpostService service = services.get().outposts();
        OutpostCostEngine.Breakdown breakdown =
                service.priceBreakdown(context.city(), 1, chunkX, chunkZ);
        long blocks = Math.round(service.blocksFromCore(context.city(), chunkX, chunkZ));

        lang.sendRaw(player, "outpost.cost-header");
        lang.sendRaw(player, "outpost.cost-base",
                LangManager.placeholder("amount", money(breakdown.base())));
        lang.sendRaw(player, "outpost.cost-distance",
                LangManager.placeholder("multiplier", twoPlaces(breakdown.distance())),
                LangManager.placeholder("blocks", Formats.count(blocks)));
        lang.sendRaw(player, "outpost.cost-chunk",
                LangManager.placeholder("multiplier", twoPlaces(breakdown.factor())));
        lang.sendRaw(player, "outpost.cost-members",
                LangManager.placeholder("divisor", twoPlaces(breakdown.divisor())));
        lang.sendRaw(player, "outpost.cost-total",
                LangManager.placeholder("amount", money(breakdown.total())));
        return Command.SINGLE_SUCCESS;
    }

    /** Every price in this class goes through here, so the formatter pair is written once. */
    private String money(BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    /** A multiplier as a player would read it: 1.25, not 1.2499999999999998. */
    private static String twoPlaces(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private int rename(CommandContext<CommandSourceStack> command) {
        Named target = namedOutpost(command, StringArgumentType.getString(command, "name"));
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        String newName = StringArgumentType.getString(command, "new");

        Replies.reply(services.get().outposts().rename(target.player().getUniqueId(),
                        target.city(), target.outpost(), newName),
                target.player(), lang, scheduler, logger,
                outpost -> lang.send(target.player(), "outpost.renamed",
                        LangManager.placeholder("name", outpost.name())));
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Resolving who and what
    // ==================================================================================

    private record Context(Player player, City city) { }

    private record Named(Player player, City city, Outpost outpost) { }

    private Context contextOf(Audience audience) {
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        Optional<City> city = services.get().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return null;
        }
        return new Context(player, city.get());
    }

    private Named namedOutpost(CommandContext<CommandSourceStack> command, String name) {
        Context context = contextOf(command.getSource().getSender());
        if (context == null) {
            return null;
        }
        Optional<Outpost> outpost = services.get().outposts().registry()
                .byName(context.city().id(), name);
        if (outpost.isEmpty()) {
            lang.send(context.player(), "outpost.unknown",
                    LangManager.placeholder("name", name));
            return null;
        }
        return new Named(context.player(), context.city(), outpost.get());
    }

    /**
     * The creation price where a player is standing.
     *
     * <p>Takes a position because SPEC 39.3 prices distance: the same outpost costs 31,721 at a
     * thousand blocks and 225,999 at a million, so a figure quoted without a place is not a
     * price. Part I 7.2's flat fee had no such need, which is what made it the wrong shape once
     * the world border went.
     */
    public String creationCost(City city, int chunkX, int chunkZ) {
        return money(services.get().outposts().creationCost(city, chunkX, chunkZ));
    }
}
