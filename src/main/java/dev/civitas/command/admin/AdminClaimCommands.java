package dev.civitas.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.3, claim administration.
 *
 * <h2>Everything here acts on where the admin is standing</h2>
 * SPEC 9.4.3 takes no coordinates on any of these, and that is the right shape: an admin
 * fixing a boundary walks it. The one exception is {@code purge}, which acts on a named city
 * because walking every chunk of one is not a thing anybody would do.
 *
 * <h2>These bypass rules rather than relaxing them</h2>
 * {@code force} ignores adjacency, contiguity, distance, cost and the war freeze — all five of
 * which exist to constrain a player. Each is bypassed by calling a separate admin path on the
 * claim service, never by passing a flag into the path a player uses. A flag would put the
 * bypass one bug away from being reachable by anybody.
 */
public final class AdminClaimCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AdminClaimCommands(Supplier<CivitasServices> services, LangManager lang,
                              Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("claim")
                .requires(source -> source.getSender().hasPermission("civitas.admin.claim"))
                .then(Commands.literal("info")
                        .executes(context -> run(context, this::info)))
                .then(Commands.literal("force")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .executes(context -> run(context, (player, current) ->
                                        force(player, current,
                                                StringArgumentType.getString(context, "city"))))))
                .then(Commands.literal("unclaim")
                        .executes(context -> run(context, this::unclaim)))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("city", StringArgumentType.word())
                                .suggests(this::suggestCities)
                                .executes(context -> run(context, (player, current) ->
                                        transfer(player, current,
                                                StringArgumentType.getString(context, "city"))))))
                .then(Commands.literal("protect")
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    builder.suggest("on");
                                    builder.suggest("off");
                                    return builder.buildFuture();
                                })
                                .executes(context -> run(context, (player, current) ->
                                        protect(player, current,
                                                StringArgumentType.getString(context, "state"),
                                                null)))
                                .then(Commands.argument("reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> run(context, (player, current) ->
                                                protect(player, current,
                                                        StringArgumentType.getString(context, "state"),
                                                        StringArgumentType.getString(context, "reason")))))));
    }

    // ==================================================================================
    // Reading
    // ==================================================================================

    /** SPEC 9.4.3: "Info on current chunk." */
    private void info(Player admin, CivitasServices current) {
        Location at = admin.getLocation();
        String world = at.getWorld().getName();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;

        boolean protectedChunk = current.adminProtection().isProtected(world, chunkX, chunkZ);
        current.claimRegistry().at(world, chunkX, chunkZ).ifPresentOrElse(claim ->
                lang.send(admin, "admin.claim.info-claimed",
                        Replies.p("world", world),
                        Replies.p("x", String.valueOf(chunkX)),
                        Replies.p("z", String.valueOf(chunkZ)),
                        Replies.p("city", current.registry().city(claim.cityId())
                                .map(City::name).orElse("#" + claim.cityId())),
                        Replies.p("type", claim.type().name()),
                        Replies.p("protected", protectedChunk ? "yes" : "no")),
                () -> lang.send(admin, "admin.claim.info-wilderness",
                        Replies.p("world", world),
                        Replies.p("x", String.valueOf(chunkX)),
                        Replies.p("z", String.valueOf(chunkZ)),
                        Replies.p("protected", protectedChunk ? "yes" : "no")));
    }

    // ==================================================================================
    // Mutating
    // ==================================================================================

    /**
     * SPEC 9.4.3: "Force-claim current chunk for a city, ignoring adjacency, distance, cost,
     * and contiguity."
     */
    private void force(Player admin, CivitasServices current, String cityName) {
        current.registry().cityByName(cityName).ifPresentOrElse(city -> {
            Location at = admin.getLocation();
            int chunkX = at.getBlockX() >> 4;
            int chunkZ = at.getBlockZ() >> 4;
            String world = at.getWorld().getName();

            current.audit().record(admin.getUniqueId(), "CLAIM_FORCE", city.name(), null,
                    Map.of("world", world, "chunk", chunkX + "," + chunkZ));

            Replies.reply(current.claims().adminForceClaim(city, world, chunkX, chunkZ,
                            admin.getUniqueId()),
                    admin, lang, scheduler, logger,
                    claim -> lang.send(admin, "admin.claim.forced",
                            Replies.p("city", city.name()),
                            Replies.p("x", String.valueOf(chunkX)),
                            Replies.p("z", String.valueOf(chunkZ))));
        }, () -> lang.send(admin, "city.unknown"));
    }

    /** SPEC 9.4.3: "Force-unclaim current chunk, no refund." */
    private void unclaim(Player admin, CivitasServices current) {
        Location at = admin.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        String world = at.getWorld().getName();

        current.claimRegistry().at(world, chunkX, chunkZ).ifPresentOrElse(claim -> {
            current.audit().record(admin.getUniqueId(), "CLAIM_UNCLAIM",
                    world + " " + chunkX + "," + chunkZ, null,
                    Map.of("city", String.valueOf(claim.cityId())));

            Replies.reply(current.claims().adminForceUnclaim(world, chunkX, chunkZ),
                    admin, lang, scheduler, logger,
                    removed -> lang.send(admin, "admin.claim.unclaimed",
                            Replies.p("x", String.valueOf(chunkX)),
                            Replies.p("z", String.valueOf(chunkZ))));
        }, () -> lang.send(admin, "admin.claim.not-claimed"));
    }

    /** SPEC 9.4.3: "Move ownership of current chunk to another city." */
    private void transfer(Player admin, CivitasServices current, String cityName) {
        current.registry().cityByName(cityName).ifPresentOrElse(city -> {
            Location at = admin.getLocation();
            int chunkX = at.getBlockX() >> 4;
            int chunkZ = at.getBlockZ() >> 4;
            String world = at.getWorld().getName();

            current.audit().record(admin.getUniqueId(), "CLAIM_TRANSFER", city.name(), null,
                    Map.of("world", world, "chunk", chunkX + "," + chunkZ));

            Replies.reply(current.claims().adminTransfer(city, world, chunkX, chunkZ),
                    admin, lang, scheduler, logger,
                    claim -> lang.send(admin, "admin.claim.transferred",
                            Replies.p("city", city.name()),
                            Replies.p("x", String.valueOf(chunkX)),
                            Replies.p("z", String.valueOf(chunkZ))));
        }, () -> lang.send(admin, "city.unknown"));
    }

    /**
     * SPEC 9.4.3: "Marks the chunk as admin-protected: unclaimable, unbuildable, war-immune."
     *
     * <p>The command that closes the last seam in the plugin. Three separate rules read the
     * answer — the claim precondition, the build check and the war grief check — and none of
     * them had anything to read until now.
     */
    private void protect(Player admin, CivitasServices current, String state, String reason) {
        Location at = admin.getLocation();
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        String world = at.getWorld().getName();
        boolean on = switch (state.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            default -> false;
        };

        current.audit().record(admin.getUniqueId(), on ? "CLAIM_PROTECT" : "CLAIM_UNPROTECT",
                world + " " + chunkX + "," + chunkZ, reason);

        var action = on
                ? current.adminProtection().protect(world, chunkX, chunkZ,
                        admin.getUniqueId(), reason)
                : current.adminProtection().unprotect(world, chunkX, chunkZ);

        action.whenComplete((changed, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Could not change chunk protection", error);
                lang.send(admin, "command.error");
                return;
            }
            lang.send(admin, on ? "admin.claim.protected" : "admin.claim.unprotected",
                    Replies.p("world", world),
                    Replies.p("x", String.valueOf(chunkX)),
                    Replies.p("z", String.valueOf(chunkZ)));
        }));
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /** Every command here needs a player, because every one acts on where they stand. */
    private int run(CommandContext<CommandSourceStack> context,
                    java.util.function.BiConsumer<Player, CivitasServices> action) {
        Audience sender = context.getSource().getSender();
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(sender, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(sender instanceof Player player)) {
            lang.send(sender, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player, current);
        return Command.SINGLE_SUCCESS;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCities(CommandContext<CommandSourceStack> context,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CivitasServices current = services.get();
        if (current != null) {
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            current.registry().cities().stream()
                    .map(City::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    /** Exposed for the help listing. */
    static List<String> subcommands() {
        return List.of("info", "force", "unclaim", "transfer", "protect");
    }
}
