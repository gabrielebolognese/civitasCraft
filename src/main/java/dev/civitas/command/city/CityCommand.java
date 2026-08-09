package dev.civitas.command.city;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.HelpPages;
import dev.civitas.command.Replies;
import dev.civitas.command.Suggest;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityPermission;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.city.CityService;
import dev.civitas.core.city.Placement;
import dev.civitas.core.city.RankService;
import dev.civitas.core.claim.BorderRenderer;
import dev.civitas.core.claim.Claim;
import dev.civitas.core.claim.ChunkKey;
import dev.civitas.core.claim.ClaimCostEngine;
import dev.civitas.core.claim.ClaimMap;
import dev.civitas.core.claim.ClaimService;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TreasuryService;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /city} command tree, SPEC 9.1 and 9.2.
 *
 * <p>Only the subcommands M2 can honour are wired up. The rest are registered as explicit
 * leaves that say which milestone brings them, rather than being left out: a player who
 * types {@code /city claim} should be told it is not ready, not told the command does not
 * exist.
 */
public final class CityCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;
    private final HelpPages helpPages;

    /**
     * @param services returns null until the async database open has finished, which is why
     *                 every executor calls {@link #notReady} before doing anything
     */
    public CityCommand(Supplier<CivitasServices> services, LangManager lang,
                       Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.helpPages = new HelpPages(lang);
    }

    private CityService cities() {
        return services.get().cities();
    }

    private RankService ranks() {
        return services.get().ranks();
    }

    private PlayerLookup lookup() {
        return services.get().lookup();
    }

    private ClaimService claims() {
        return services.get().claims();
    }

    private ClaimMap claimMap() {
        return services.get().map();
    }

    private BorderRenderer borders() {
        return services.get().borders();
    }

    private TreasuryService treasury() {
        return services.get().treasury();
    }

    /** True, having already told the sender, if storage is not open yet. */
    private boolean notReady(Audience audience) {
        if (services.get() != null) {
            return false;
        }
        lang.send(audience, "plugin.starting");
        return true;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("city")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(this::showOwnCity)
                .then(help())
                .then(create())
                .then(info())
                .then(list())
                .then(join())
                .then(accept())
                .then(deny())
                .then(leave())
                .then(invite())
                .then(kick())
                .then(transfer())
                .then(disband())
                .then(rank())
                .then(setMotd())
                .then(open())
                .then(rename())
                .then(spawn())
                .then(setSpawn())
                .then(claim())
                .then(unclaim())
                .then(mapCommand())
                .then(here())
                .then(border())
                .then(deposit())
                .then(withdraw())
                .then(new OutpostCommands(services, lang, scheduler, logger).build())
                .then(new WaystationCommands(services, lang, scheduler, logger).build())
                .then(upgradeCommands().upgrade())
                .then(upgradeCommands().vault())
                .then(new DefenseCommands(services, lang, scheduler, logger).build())
                // SPEC 9.2 lists city chat twice, as /city chat and as /cc. Both are the same
                // handler, so neither can drift into reaching a different set of players.
                .then(new CityChatCommand(services, lang).subcommand())
                .then(hall())
                .build();
    }

    // ==================================================================================
    // SPEC 9.1, paginated help
    // ==================================================================================

    /**
     * {@code /city help [page]}.
     *
     * <p>The one subcommand that does not check {@link #notReady}: help is what a player
     * reaches for when nothing else is working, so it must answer during the startup window
     * in which storage is still opening. It reads no state to do so.
     */
    private ArgumentBuilder<CommandSourceStack, ?> help() {
        return Commands.literal("help")
                .executes(context -> sendHelp(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context ->
                                sendHelp(context, IntegerArgumentType.getInteger(context, "page"))));
    }

    private int sendHelp(CommandContext<CommandSourceStack> context, int page) {
        CommandSender sender = context.getSource().getSender();
        helpPages.send(sender, sender::hasPermission, page);
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Subcommands implemented in M2
    // ==================================================================================

    private ArgumentBuilder<CommandSourceStack, ?> create() {
        return Commands.literal("create")
                .requires(source -> source.getSender().hasPermission("civitas.city.create"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            Player player = player(context);
                            if (player == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            String name = StringArgumentType.getString(context, "name");
                            Replies.reply(
                                    cities().create(player.getUniqueId(), name,
                                            Placement.of(player.getLocation())),
                                    player, lang, scheduler, logger,
                                    city -> {
                                        lang.send(player, "city.create.success",
                                                Replies.p("name", city.name()));
                                        Bukkit.broadcast(lang.get("city.create.broadcast",
                                                Replies.p("name", city.name()),
                                                Replies.p("player", player.getName())));
                                    });
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private ArgumentBuilder<CommandSourceStack, ?> info() {
        return Commands.literal("info")
                .executes(this::showOwnCity)
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(cityNames())
                        .executes(context -> {
                            Audience audience = context.getSource().getSender();
                            if (notReady(audience)) {
                                return Command.SINGLE_SUCCESS;
                            }
                            String name = StringArgumentType.getString(context, "city");
                            Optional<City> city = cities().registry().cityByName(name);
                            if (city.isEmpty()) {
                                lang.send(audience, "city.unknown");
                            } else {
                                sendInfo(audience, city.get());
                            }
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private ArgumentBuilder<CommandSourceStack, ?> list() {
        return Commands.literal("list")
                .executes(context -> sendList(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context ->
                                sendList(context, IntegerArgumentType.getInteger(context, "page"))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> join() {
        return Commands.literal("join")
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(cityNames())
                        .executes(context -> withCityArgument(context, (player, city) -> {
                            Replies.reply(cities().joinOpen(player.getUniqueId(), city),
                                    player, lang, scheduler, logger,
                                    joined -> lang.send(player, "city.join.success",
                                            Replies.p("name", joined.name())));
                        })));
    }

    private ArgumentBuilder<CommandSourceStack, ?> accept() {
        return Commands.literal("accept")
                .executes(context -> {
                    // No argument: this accepts a pending mayorship transfer, SPEC 5.3.
                    Player player = player(context);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Replies.reply(cities().acceptTransfer(player.getUniqueId()),
                            player, lang, scheduler, logger,
                            city -> {
                                lang.send(player, "city.transfer.accepted",
                                        Replies.p("name", city.name()));
                                announce(city, "city.transfer.announce",
                                        Replies.p("player", player.getName()));
                            });
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(cityNames())
                        .executes(context -> withCityArgument(context, (player, city) ->
                                Replies.reply(cities().acceptInvite(player.getUniqueId(), city),
                                        player, lang, scheduler, logger,
                                        joined -> {
                                            lang.send(player, "city.join.success",
                                                    Replies.p("name", joined.name()));
                                            announce(joined, "city.join.announce",
                                                    Replies.p("player", player.getName()));
                                        }))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> deny() {
        return Commands.literal("deny")
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(cityNames())
                        .executes(context -> withCityArgument(context, (player, city) ->
                                Replies.reply(cities().denyInvite(player.getUniqueId(), city),
                                        player, lang, scheduler, logger,
                                        ignored -> lang.send(player, "city.invite.denied",
                                                Replies.p("name", city.name()))))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> leave() {
        return Commands.literal("leave")
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    lang.send(audience, "city.leave.confirm-hint");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("confirm")
                        .executes(context -> withOwnCity(context, (player, city) ->
                                Replies.reply(cities().leave(player.getUniqueId(), city),
                                        player, lang, scheduler, logger,
                                        left -> {
                                            lang.send(player, "city.leave.success",
                                                    Replies.p("name", left.name()));
                                            announce(left, "city.leave.announce",
                                                    Replies.p("player", player.getName()));
                                        }))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> invite() {
        return Commands.literal("invite")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(onlinePlayers())
                        .executes(context -> withResolvedTarget(context, (player, city, target) ->
                                Replies.reply(cities().invite(player.getUniqueId(), city, target.uuid()),
                                        player, lang, scheduler, logger,
                                        ignored -> {
                                            lang.send(player, "city.invite.sent",
                                                    Replies.p("player", target.name()));
                                            Player online = Bukkit.getPlayer(target.uuid());
                                            if (online != null) {
                                                lang.send(online, "city.invite.received",
                                                        Replies.p("name", city.name()),
                                                        Replies.p("player", player.getName()));
                                            }
                                        }))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> kick() {
        return Commands.literal("kick")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(cityMembers())
                        .executes(context -> withResolvedTarget(context, (player, city, target) ->
                                Replies.reply(cities().kick(player.getUniqueId(), city, target.uuid()),
                                        player, lang, scheduler, logger,
                                        ignored -> {
                                            lang.send(player, "city.kick.success",
                                                    Replies.p("player", target.name()));
                                            Player online = Bukkit.getPlayer(target.uuid());
                                            if (online != null) {
                                                lang.send(online, "city.kick.kicked",
                                                        Replies.p("name", city.name()));
                                            }
                                        }))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> transfer() {
        return Commands.literal("transfer")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(cityMembers())
                        .executes(context -> withResolvedTarget(context, (player, city, target) -> {
                            Result<Void> offered = cities().offerTransfer(player.getUniqueId(), city,
                                    target.uuid(), target.online());
                            if (offered instanceof Result.Failure<Void> failure) {
                                Replies.sendFailure(player, lang, failure);
                                return;
                            }
                            lang.send(player, "city.transfer.offered",
                                    Replies.p("player", target.name()));
                            Player online = Bukkit.getPlayer(target.uuid());
                            if (online != null) {
                                lang.send(online, "city.transfer.offer-received",
                                        Replies.p("name", city.name()),
                                        Replies.p("player", player.getName()));
                            }
                        })));
    }

    private ArgumentBuilder<CommandSourceStack, ?> disband() {
        return Commands.literal("disband")
                .executes(context -> withOwnCity(context, (player, city) ->
                        lang.send(player, "city.disband.confirm-hint",
                                Replies.p("name", city.name()))))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> withOwnCity(context, (player, city) -> {
                            String typed = StringArgumentType.getString(context, "name");
                            if (!typed.equals(city.name())) {
                                lang.send(player, "city.disband.name-mismatch",
                                        Replies.p("name", city.name()));
                                return;
                            }
                            Replies.reply(cities().disband(player.getUniqueId(), city),
                                    player, lang, scheduler, logger,
                                    disbanded -> Bukkit.broadcast(lang.get("city.disband.broadcast",
                                            Replies.p("name", disbanded.name()))));
                        })));
    }

    private ArgumentBuilder<CommandSourceStack, ?> setMotd() {
        return Commands.literal("setmotd")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> withOwnCity(context, (player, city) ->
                                Replies.reply(cities().setMotd(player.getUniqueId(), city,
                                                StringArgumentType.getString(context, "text")),
                                        player, lang, scheduler, logger,
                                        updated -> lang.send(player, "city.settings.motd-set")))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> open() {
        return Commands.literal("open")
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("true");
                            builder.suggest("false");
                            return builder.buildFuture();
                        })
                        .executes(context -> withOwnCity(context, (player, city) -> {
                            boolean value = Boolean.parseBoolean(
                                    StringArgumentType.getString(context, "value"));
                            Replies.reply(cities().setOpenJoin(player.getUniqueId(), city, value),
                                    player, lang, scheduler, logger,
                                    updated -> lang.send(player, value
                                            ? "city.settings.open-on"
                                            : "city.settings.open-off"));
                        })));
    }

    private ArgumentBuilder<CommandSourceStack, ?> rename() {
        return Commands.literal("rename")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> withOwnCity(context, (player, city) ->
                                Replies.reply(cities().rename(player.getUniqueId(), city,
                                                StringArgumentType.getString(context, "name")),
                                        player, lang, scheduler, logger,
                                        renamed -> lang.send(player, "city.settings.renamed",
                                                Replies.p("name", renamed.name()))))));
    }

    // ==================================================================================
    // /city rank ...
    // ==================================================================================

    private ArgumentBuilder<CommandSourceStack, ?> rank() {
        return Commands.literal("rank")
                .executes(context -> withOwnCity(context, this::sendRanks))
                .then(Commands.literal("list")
                        .executes(context -> withOwnCity(context, this::sendRanks)))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("weight", IntegerArgumentType.integer(0, 99))
                                        .executes(context -> withOwnCity(context, (player, city) ->
                                                Replies.reply(ranks().create(player.getUniqueId(), city,
                                                                StringArgumentType.getString(context, "name"),
                                                                IntegerArgumentType.getInteger(context, "weight")),
                                                        player, lang, scheduler, logger,
                                                        created -> lang.send(player, "city.rank.created",
                                                                Replies.p("rank", created.name()))))))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("rank", StringArgumentType.word())
                                .suggests(rankNames())
                                .executes(context -> withRank(context, (player, city, rank) ->
                                        Replies.reply(ranks().delete(player.getUniqueId(), city, rank),
                                                player, lang, scheduler, logger,
                                                deleted -> lang.send(player, "city.rank.deleted",
                                                        Replies.p("rank", deleted.name())))))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(cityMembers())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .suggests(rankNames())
                                        .executes(context -> withResolvedTarget(context,
                                                (player, city, target) -> {
                                                    String rankName =
                                                            StringArgumentType.getString(context, "rank");
                                                    Optional<CityRank> rank = city.rankByName(rankName);
                                                    if (rank.isEmpty()) {
                                                        lang.send(player, "city.rank.unknown",
                                                                Replies.p("rank", rankName));
                                                        return;
                                                    }
                                                    Replies.reply(ranks().assign(player.getUniqueId(), city,
                                                                    target.uuid(), rank.get()),
                                                            player, lang, scheduler, logger,
                                                            assigned -> lang.send(player, "city.rank.assigned",
                                                                    Replies.p("player", target.name()),
                                                                    Replies.p("rank", assigned.name())));
                                                })))))
                .then(Commands.literal("perm")
                        .then(Commands.argument("rank", StringArgumentType.word())
                                .suggests(rankNames())
                                .then(Commands.argument("flag", StringArgumentType.word())
                                        .suggests(permissionFlags())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    builder.suggest("on");
                                                    builder.suggest("off");
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> withRank(context,
                                                        (player, city, rank) -> {
                                                            String flagName =
                                                                    StringArgumentType.getString(context, "flag");
                                                            Optional<CityPermission> flag =
                                                                    CityPermission.parse(flagName);
                                                            if (flag.isEmpty()) {
                                                                lang.send(player, "city.rank.unknown-flag",
                                                                        Replies.p("flag", flagName));
                                                                return;
                                                            }
                                                            boolean granted = "on".equalsIgnoreCase(
                                                                    StringArgumentType.getString(context, "value"));
                                                            Replies.reply(ranks().setPermission(
                                                                            player.getUniqueId(), city, rank,
                                                                            flag.get(), granted),
                                                                    player, lang, scheduler, logger,
                                                                    updated -> lang.send(player,
                                                                            granted
                                                                                    ? "city.rank.perm-granted"
                                                                                    : "city.rank.perm-revoked",
                                                                            Replies.p("rank", updated.name()),
                                                                            Replies.p("flag", flag.get().name())));
                                                        }))))));
    }

    // ==================================================================================
    // Land, SPEC 6
    // ==================================================================================

    private ArgumentBuilder<CommandSourceStack, ?> claim() {
        return Commands.literal("claim")
                .executes(context -> withOwnCity(context, this::claimHere))
                .then(Commands.literal("auto")
                        .executes(context -> withOwnCity(context, (player, city) -> {
                            boolean on = claims().toggleAutoClaim(player.getUniqueId());
                            lang.send(player, on ? "claim.auto-on" : "claim.auto-off");
                            if (on) {
                                claimHere(player, city);
                            }
                        })))
                .then(Commands.literal("radius")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 5))
                                .executes(context -> withOwnCity(context, (player, city) -> {
                                    int radius = IntegerArgumentType.getInteger(context, "n");
                                    Replies.reply(claims().claimRadius(player.getUniqueId(), city,
                                                    player.getWorld().getName(),
                                                    chunkX(player), chunkZ(player), radius),
                                            player, lang, scheduler, logger,
                                            bought -> {
                                                lang.send(player, "claim.radius-success",
                                                        Replies.p("count", String.valueOf(bought.size())),
                                                        Replies.p("cost", totalCost(bought)));
                                                bought.forEach(claim -> borders().highlightChunk(player,
                                                        claim.world(), claim.chunkX(), claim.chunkZ()));
                                            });
                                }))));
    }

    private void claimHere(Player player, City city) {
        String world = player.getWorld().getName();
        int chunkX = chunkX(player);
        int chunkZ = chunkZ(player);

        Replies.reply(claims().claim(player.getUniqueId(), city, world, chunkX, chunkZ),
                player, lang, scheduler, logger,
                claim -> {
                    lang.send(player, "claim.success",
                            Replies.p("chunk", chunkX + "," + chunkZ),
                            Replies.p("cost", claim.costPaid().toPlainString()));
                    borders().highlightChunk(player, world, chunkX, chunkZ);
                });
    }

    private ArgumentBuilder<CommandSourceStack, ?> unclaim() {
        return Commands.literal("unclaim")
                .executes(context -> withOwnCity(context, (player, city) ->
                        Replies.reply(claims().unclaim(player.getUniqueId(), city,
                                        player.getWorld().getName(), chunkX(player), chunkZ(player)),
                                player, lang, scheduler, logger,
                                claim -> lang.send(player, "claim.unclaim-success",
                                        Replies.p("chunk", claim.chunkX() + "," + claim.chunkZ()),
                                        Replies.p("refund", claims().costs()
                                                .refundFor(claim.costPaid()).toPlainString())))))
                .then(Commands.literal("radius")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 5))
                                .executes(context -> withOwnCity(context, (player, city) ->
                                        Replies.reply(claims().unclaimRadius(player.getUniqueId(), city,
                                                        player.getWorld().getName(),
                                                        chunkX(player), chunkZ(player),
                                                        IntegerArgumentType.getInteger(context, "n")),
                                                player, lang, scheduler, logger,
                                                released -> lang.send(player, "claim.unclaim-radius-success",
                                                        Replies.p("count",
                                                                String.valueOf(released.size()))))))));
    }

    /** SPEC 6.5: the ASCII chunk map. */
    private ArgumentBuilder<CommandSourceStack, ?> mapCommand() {
        return Commands.literal("map")
                .executes(context -> {
                    Player player = player(context);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Optional<City> own = cities().registry().cityOf(player.getUniqueId());
                    lang.sendRaw(player, "claim.map-header");
                    claimMap().render(player.getWorld().getName(), chunkX(player), chunkZ(player), own)
                            .forEach(player::sendMessage);
                    lang.sendRaw(player, "claim.map-legend");
                    return Command.SINGLE_SUCCESS;
                });
    }

    /** SPEC 9.1: who owns the chunk the player is standing in. */
    private ArgumentBuilder<CommandSourceStack, ?> here() {
        return Commands.literal("here")
                .executes(context -> {
                    Player player = player(context);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    int chunkX = chunkX(player);
                    int chunkZ = chunkZ(player);
                    Optional<Claim> claim = claims().registry()
                            .at(player.getWorld().getName(), chunkX, chunkZ);

                    if (claim.isEmpty()) {
                        lang.send(player, "claim.here-wilderness",
                                Replies.p("chunk", chunkX + "," + chunkZ));
                        return Command.SINGLE_SUCCESS;
                    }
                    Optional<City> owner = cities().registry().city(claim.get().cityId());
                    lang.send(player, "claim.here-owned",
                            Replies.p("chunk", chunkX + "," + chunkZ),
                            Replies.p("city", owner.map(City::name).orElse("?")),
                            Replies.p("type", claim.get().type().name()));
                    return Command.SINGLE_SUCCESS;
                });
    }

    /** SPEC 6.5: toggle the particle outline. */
    private ArgumentBuilder<CommandSourceStack, ?> border() {
        return Commands.literal("border")
                .executes(context -> {
                    Player player = player(context);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    boolean on = borders().toggle(player);
                    lang.send(player, on ? "claim.border-on" : "claim.border-off");
                    return Command.SINGLE_SUCCESS;
                });
    }

    private static int chunkX(Player player) {
        return ChunkKey.toChunk(player.getLocation().getBlockX());
    }

    private static int chunkZ(Player player) {
        return ChunkKey.toChunk(player.getLocation().getBlockZ());
    }

    private static String totalCost(List<Claim> claimed) {
        return claimed.stream()
                .map(Claim::costPaid)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .toPlainString();
    }

    // ==================================================================================
    // Treasury, SPEC 9.2
    // ==================================================================================

    private ArgumentBuilder<CommandSourceStack, ?> deposit() {
        return Commands.literal("deposit")
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(context -> withOwnCity(context, (player, city) ->
                                withAmount(context, player, amount ->
                                        Replies.reply(treasury().deposit(player.getUniqueId(),
                                                        city, amount),
                                                player, lang, scheduler, logger,
                                                after -> lang.send(player, "city.treasury.deposited",
                                                        Replies.p("amount", money(amount)),
                                                        Replies.p("treasury", money(after))))))));
    }

    private ArgumentBuilder<CommandSourceStack, ?> withdraw() {
        return Commands.literal("withdraw")
                .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(context -> withOwnCity(context, (player, city) ->
                                withAmount(context, player, amount ->
                                        Replies.reply(treasury().withdraw(player.getUniqueId(),
                                                        city, amount),
                                                player, lang, scheduler, logger,
                                                after -> lang.send(player, "city.treasury.withdrawn",
                                                        Replies.p("amount", money(amount)),
                                                        Replies.p("treasury", money(after))))))));
    }

    /** Parses the {@code amount} argument, reporting the failure if it is not a plain number. */
    private void withAmount(CommandContext<CommandSourceStack> context, Player player,
                            java.util.function.Consumer<java.math.BigDecimal> action) {
        Result<java.math.BigDecimal> parsed =
                Money.parse(StringArgumentType.getString(context, "amount"));
        if (parsed instanceof Result.Failure<java.math.BigDecimal> failure) {
            Replies.sendFailure(player, lang, failure);
            return;
        }
        action.accept(parsed.orElseThrow());
    }

    private String money(java.math.BigDecimal amount) {
        return Money.format(amount, services.get().economy().configs());
    }

    // ==================================================================================
    // Output
    // ==================================================================================

    private int showOwnCity(CommandContext<CommandSourceStack> context) {
        Audience audience = context.getSource().getSender();
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        Optional<City> city = cities().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(audience, "city.none");
            return Command.SINGLE_SUCCESS;
        }
        // SPEC 8.3: "/city (no arguments) also opens the Main Menu from anywhere".
        new dev.civitas.gui.menus.MainMenu(services.get().menus(), services.get(), player,
                city.get()).open();
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Spawn, SPEC 5.6
    // ==================================================================================

    private UpgradeCommands upgradeCommands() {
        return new UpgradeCommands(services, lang, scheduler, logger);
    }

    private ArgumentBuilder<CommandSourceStack, ?> spawn() {
        return Commands.literal("spawn")
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    if (notReady(audience)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    Result<Long> started = services.get().spawns().requestTeleport(player);
                    if (started instanceof Result.Failure<Long> failure) {
                        Replies.sendFailure(player, lang, failure);
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }

    private ArgumentBuilder<CommandSourceStack, ?> setSpawn() {
        return Commands.literal("setspawn")
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    if (notReady(audience)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    Optional<City> city = cities().registry().cityOf(player.getUniqueId());
                    if (city.isEmpty()) {
                        lang.send(audience, "city.none");
                        return Command.SINGLE_SUCCESS;
                    }
                    var at = player.getLocation();
                    Replies.reply(cities().setSpawn(player.getUniqueId(), city.get(),
                                    at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                                    at.getYaw(), at.getPitch()),
                            audience, lang, scheduler, logger,
                            updated -> lang.send(audience, "gui.settings.spawn-set"));
                    return Command.SINGLE_SUCCESS;
                });
    }

    /**
     * {@code /city hall}, SPEC 8.1.
     *
     * <p>SPEC 8.1 allows one free replacement if the block was somehow destroyed. There is no
     * way to prove it was, so this simply gives the mayor the item: the block does nothing on
     * its own that {@code /city} does not, so a spare one costs the server nothing.
     */
    private ArgumentBuilder<CommandSourceStack, ?> hall() {
        return Commands.literal("hall")
                .executes(context -> {
                    Audience audience = context.getSource().getSender();
                    if (notReady(audience)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!(context.getSource().getSender() instanceof Player player)) {
                        lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
                        return Command.SINGLE_SUCCESS;
                    }
                    Optional<City> city = cities().registry().cityOf(player.getUniqueId());
                    if (city.isEmpty()) {
                        lang.send(audience, "city.none");
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!city.get().isMayor(player.getUniqueId())) {
                        lang.send(audience, "city.hall.mayor-only");
                        return Command.SINGLE_SUCCESS;
                    }
                    player.getInventory().addItem(services.get().cityHall().item(city.get()))
                            .values()
                            .forEach(left -> player.getWorld()
                                    .dropItemNaturally(player.getLocation(), left));
                    lang.send(audience, "city.hall.given");
                    return Command.SINGLE_SUCCESS;
                });
    }

    private void sendInfo(Audience audience, City city) {
        String mayorName = nameOf(city.mayorUuid());
        lang.sendRaw(audience, "city.info.header", Replies.p("name", city.name()));
        lang.sendRaw(audience, "city.info.tag",
                Replies.p("tag", city.tag() == null ? "-" : city.tag()));
        lang.sendRaw(audience, "city.info.mayor", Replies.p("mayor", mayorName));
        lang.sendRaw(audience, "city.info.members",
                Replies.p("count", String.valueOf(city.memberCount())),
                Replies.p("cap", String.valueOf(cities().memberCap(city))));
        lang.sendRaw(audience, "city.info.treasury",
                Replies.p("amount", city.treasury().toPlainString()));
        lang.sendRaw(audience, "city.info.open",
                Replies.p("value", String.valueOf(city.isOpenJoin())));
        if (!city.motd().isBlank()) {
            lang.sendRaw(audience, "city.info.motd", Replies.p("motd", city.motd()));
        }
    }

    private int sendList(CommandContext<CommandSourceStack> context, int page) {
        Audience audience = context.getSource().getSender();
        if (notReady(audience)) {
            return Command.SINGLE_SUCCESS;
        }
        List<City> all = cities().registry().cities().stream()
                .sorted((a, b) -> Integer.compare(b.memberCount(), a.memberCount()))
                .toList();

        int perPage = 10;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int clamped = Math.min(page, pages);
        lang.sendRaw(audience, "city.list.header",
                Replies.p("page", String.valueOf(clamped)),
                Replies.p("pages", String.valueOf(pages)));

        if (all.isEmpty()) {
            lang.sendRaw(audience, "city.list.empty");
            return Command.SINGLE_SUCCESS;
        }

        int from = (clamped - 1) * perPage;
        for (City city : all.subList(from, Math.min(from + perPage, all.size()))) {
            lang.sendRaw(audience, "city.list.entry",
                    Replies.p("name", city.name()),
                    Replies.p("members", String.valueOf(city.memberCount())),
                    Replies.p("mayor", nameOf(city.mayorUuid())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendRanks(Player player, City city) {
        lang.sendRaw(player, "city.rank.header", Replies.p("name", city.name()));
        city.ranks().stream()
                .sorted((a, b) -> Integer.compare(b.weight(), a.weight()))
                .forEach(rank -> lang.sendRaw(player, "city.rank.entry",
                        Replies.p("rank", rank.name()),
                        Replies.p("weight", String.valueOf(rank.weight())),
                        Replies.p("permissions", String.valueOf(rank.permissions().size())),
                        Replies.p("members", String.valueOf(city.membersWithRank(rank.id())))));
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    /** A leaf that names the milestone bringing this subcommand, rather than a syntax error. */
    private LiteralArgumentBuilder<CommandSourceStack> notYet(String literal, int milestone) {
        return Commands.literal(literal)
                .executes(context -> {
                    lang.send(context.getSource().getSender(), Msg.COMMAND_NOT_IMPLEMENTED,
                            Replies.p("command", "city " + literal),
                            Replies.p("milestone", "M" + milestone));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> {
                            lang.send(context.getSource().getSender(), Msg.COMMAND_NOT_IMPLEMENTED,
                                    Replies.p("command", "city " + literal),
                                    Replies.p("milestone", "M" + milestone));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private Player player(CommandContext<CommandSourceStack> context) {
        if (notReady(context.getSource().getSender())) {
            return null;
        }
        if (context.getSource().getSender() instanceof Player player) {
            return player;
        }
        lang.send(context.getSource().getSender(), Msg.COMMAND_PLAYER_ONLY);
        return null;
    }

    private interface CityAction {
        void run(Player player, City city);
    }

    private interface TargetAction {
        void run(Player player, City city, PlayerLookup.Resolved target);
    }

    private interface RankAction {
        void run(Player player, City city, CityRank rank);
    }

    private int withOwnCity(CommandContext<CommandSourceStack> context, CityAction action) {
        Player player = player(context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Optional<City> city = cities().registry().cityOf(player.getUniqueId());
        if (city.isEmpty()) {
            lang.send(player, "city.none");
            return Command.SINGLE_SUCCESS;
        }
        action.run(player, city.get());
        return Command.SINGLE_SUCCESS;
    }

    private int withCityArgument(CommandContext<CommandSourceStack> context, CityAction action) {
        Player player = player(context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, "city");
        Optional<City> city = cities().registry().cityByName(name);
        if (city.isEmpty()) {
            lang.send(player, "city.unknown");
            return Command.SINGLE_SUCCESS;
        }
        action.run(player, city.get());
        return Command.SINGLE_SUCCESS;
    }

    private int withRank(CommandContext<CommandSourceStack> context, RankAction action) {
        return withOwnCity(context, (player, city) -> {
            String rankName = StringArgumentType.getString(context, "rank");
            Optional<CityRank> rank = city.rankByName(rankName);
            if (rank.isEmpty()) {
                lang.send(player, "city.rank.unknown", Replies.p("rank", rankName));
                return;
            }
            action.run(player, city, rank.get());
        });
    }

    /** Resolves the {@code player} argument, which may name someone who is offline. */
    private int withResolvedTarget(CommandContext<CommandSourceStack> context, TargetAction action) {
        return withOwnCity(context, (player, city) -> {
            String typed = StringArgumentType.getString(context, "player");
            CompletableFuture<Optional<PlayerLookup.Resolved>> resolution = lookup().resolve(typed);
            resolution.whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
                if (error != null || resolved == null || resolved.isEmpty()) {
                    lang.send(player, "player.unknown", Replies.p("player", typed));
                    return;
                }
                action.run(player, city, resolved.get());
            }));
        });
    }

    private void announce(City city, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        for (CityMember member : city.members()) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, key, resolvers);
            }
        }
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    // --- suggestions -------------------------------------------------------------------

    private SuggestionProvider<CommandSourceStack> cityNames() {
        return (context, builder) -> {
            if (services.get() == null) {
                return builder.buildFuture();
            }
            cities().registry().cities().stream()
                    .map(City::name)
                    .filter(name -> name.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> rankNames() {
        return (context, builder) -> {
            if (services.get() != null && context.getSource().getSender() instanceof Player player) {
                cities().registry().cityOf(player.getUniqueId()).ifPresent(city ->
                        city.ranks().stream()
                                .map(CityRank::name)
                                .filter(name -> name.toLowerCase()
                                        .startsWith(builder.getRemaining().toLowerCase()))
                                .forEach(builder::suggest));
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> cityMembers() {
        return (context, builder) -> {
            if (services.get() != null && context.getSource().getSender() instanceof Player player) {
                cities().registry().cityOf(player.getUniqueId()).ifPresent(city -> {
                    for (CityMember member : city.members()) {
                        String name = nameOf(member.uuid());
                        if (name.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                            builder.suggest(name);
                        }
                    }
                });
            }
            return builder.buildFuture();
        };
    }

    /**
     * Online player names.
     *
     * <p>Delegates to {@link Suggest#onlinePlayers()}, which M23 extracted from this method
     * after finding four verbatim copies of it elsewhere in the tree — two of which lowercased
     * without a locale, so a player named Ian would not have matched "i" on a Turkish server.
     */
    private SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return Suggest.onlinePlayers();
    }

    private SuggestionProvider<CommandSourceStack> permissionFlags() {
        return (context, builder) -> {
            for (CityPermission permission : CityPermission.values()) {
                if (permission.name().toLowerCase()
                        .startsWith(builder.getRemaining().toLowerCase())) {
                    builder.suggest(permission.name());
                }
            }
            return builder.buildFuture();
        };
    }
}
