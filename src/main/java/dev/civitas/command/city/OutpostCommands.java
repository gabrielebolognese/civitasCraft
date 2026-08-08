package dev.civitas.command.city;

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
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
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
                                        .executes(this::rename))));
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
        return Money.format(services.get().outposts().creationCost(city, chunkX, chunkZ),
                services.get().economy().configs());
    }
}
