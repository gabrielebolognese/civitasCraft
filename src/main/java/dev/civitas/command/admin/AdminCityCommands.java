package dev.civitas.command.admin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.lang.LangManager;
import dev.civitas.util.PlayerLookup;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * SPEC 9.4.2, city administration.
 *
 * <h2>These are the commands that fix a broken city</h2>
 * Every one exists for a situation the player-facing rules cannot resolve. SPEC 17.1 case 5 is
 * the clearest: a mayor banned from the server leaves a city nobody can act for, and no amount
 * of player-facing design fixes that — only an admin can. {@code freeze} is the other end of
 * the same idea, for a city that must stop doing anything at all while a dispute is settled.
 *
 * <h2>Delete asks for the name, and that is not decoration</h2>
 * SPEC 9.4.2 requires typing the city name to delete it, and the reason is that the argument
 * before it is also a city name. Without the confirmation, one mistyped argument between
 * {@code /ca city delete} and {@code /ca city freeze} is the difference between a city being
 * paused and a city being gone.
 */
public final class AdminCityCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public AdminCityCommands(Supplier<CivitasServices> services, LangManager lang,
                             Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("city")
                .requires(source -> source.getSender().hasPermission("civitas.admin.city"))
                .then(cityAndPlayer("setmayor", this::setMayor))
                .then(cityAndPlayer("forceadd", this::forceAdd))
                .then(cityAndPlayer("forceremove", this::forceRemove))
                .then(cityAndWord("rename", this::rename))
                .then(cityAndWord("delete", this::delete))
                .then(cityOnly("restore", this::restore))
                .then(cityAndGreedy("freeze", this::freeze))
                .then(cityOnly("unfreeze", (audience, current, city) ->
                        setFrozen(audience, current, city, false, null)))
                .then(cityAndWord("setupkeep", this::setUpkeep))
                .then(cityOnly("forgivedebt", this::forgiveDebt));
    }

    // ==================================================================================
    // The commands
    // ==================================================================================

    /** SPEC 9.4.2: "Force mayorship transfer (offline-safe)." */
    private void setMayor(Audience audience, CivitasServices current, City city, String name) {
        resolve(current, audience, name, target -> {
            audit(audience, current, "CITY_SETMAYOR", city.name(),
                    "to " + target.name(), Map.of());
            Replies.reply(current.cities().adminSetMayor(city, target.uuid()),
                    audience, lang, scheduler, logger,
                    updated -> lang.send(audience, "admin.city.mayor-set",
                            Replies.p("city", city.name()),
                            Replies.p("player", target.name())));
        });
    }

    /** SPEC 9.4.2: "Force-add a member (bypasses cooldowns and caps)." */
    private void forceAdd(Audience audience, CivitasServices current, City city, String name) {
        resolve(current, audience, name, target -> {
            audit(audience, current, "CITY_FORCEADD", city.name(), target.name(), Map.of());
            Replies.reply(current.cities().adminForceAdd(city, target.uuid(),
                            System.currentTimeMillis()),
                    audience, lang, scheduler, logger,
                    updated -> lang.send(audience, "admin.city.member-added",
                            Replies.p("player", target.name()),
                            Replies.p("city", city.name())));
        });
    }

    /** SPEC 9.4.2: "Force-remove." */
    private void forceRemove(Audience audience, CivitasServices current, City city, String name) {
        resolve(current, audience, name, target -> {
            audit(audience, current, "CITY_FORCEREMOVE", city.name(), target.name(), Map.of());
            Replies.reply(current.cities().adminForceRemove(city, target.uuid()),
                    audience, lang, scheduler, logger,
                    updated -> lang.send(audience, "admin.city.member-removed",
                            Replies.p("player", target.name()),
                            Replies.p("city", city.name())));
        });
    }

    /** SPEC 9.4.2: "Force rename, free." */
    private void rename(Audience audience, CivitasServices current, City city, String newName) {
        audit(audience, current, "CITY_RENAME", city.name(), newName, Map.of());
        Replies.reply(current.cities().adminRename(city, newName),
                audience, lang, scheduler, logger,
                updated -> lang.send(audience, "admin.city.renamed",
                        Replies.p("name", newName)));
    }

    /** SPEC 9.4.2: "Soft delete, requires typing the city name." */
    private void delete(Audience audience, CivitasServices current, City city,
                        String confirmation) {
        if (!city.name().equalsIgnoreCase(confirmation)) {
            lang.send(audience, "admin.city.confirm-name",
                    Replies.p("city", city.name()));
            return;
        }
        audit(audience, current, "CITY_DELETE", city.name(), null, Map.of());
        Replies.reply(current.cities().adminDelete(city, System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                updated -> lang.send(audience, "admin.city.deleted",
                        Replies.p("city", city.name()),
                        Replies.p("days", "14")));
    }

    /** SPEC 9.4.2: "Restore a soft-deleted city within 14 days." */
    private void restore(Audience audience, CivitasServices current, City city) {
        audit(audience, current, "CITY_RESTORE", city.name(), null, Map.of());
        Replies.reply(current.cities().adminRestore(city, System.currentTimeMillis()),
                audience, lang, scheduler, logger,
                updated -> lang.send(audience, "admin.city.restored",
                        Replies.p("city", city.name())));
    }

    /** SPEC 9.4.2: freeze, with the mandatory reason SPEC asks for. */
    private void freeze(Audience audience, CivitasServices current, City city, String reason) {
        setFrozen(audience, current, city, true, reason);
    }

    private void setFrozen(Audience audience, CivitasServices current, City city, boolean frozen,
                           String reason) {
        audit(audience, current, frozen ? "CITY_FREEZE" : "CITY_UNFREEZE", city.name(), reason,
                Map.of());

        Replies.reply(current.cities().adminFreeze(city, frozen),
                audience, lang, scheduler, logger,
                updated -> {
                    lang.send(audience, frozen ? "admin.city.frozen" : "admin.city.unfrozen",
                            Replies.p("city", city.name()));
                    // SPEC 9.4.2: "Members are notified with the reason." A city that has
                    // silently stopped working is a bug report; one that says why is a rule.
                    if (frozen && reason != null) {
                        notifyMembers(city, reason);
                    }
                });
    }

    private void notifyMembers(City city, String reason) {
        for (var member : city.members()) {
            Player online = org.bukkit.Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, "admin.city.frozen-notice", Replies.p("reason", reason));
            }
        }
    }

    /** SPEC 9.4.2: "Temporary upkeep multiplier, e.g. for a returning-player grace period." */
    private void setUpkeep(Audience audience, CivitasServices current, City city, String raw) {
        double multiplier;
        try {
            multiplier = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            lang.send(audience, "economy.amount-invalid");
            return;
        }
        if (multiplier < 0 || multiplier > 100) {
            lang.send(audience, "admin.city.upkeep-range");
            return;
        }

        audit(audience, current, "CITY_SETUPKEEP", city.name(), raw, Map.of());
        current.upkeepOverrides().set(city.id(), multiplier, actorOf(audience))
                .whenComplete((written, error) -> scheduler.runOnMain(() -> {
                    if (error != null) {
                        lang.send(audience, "command.error");
                        return;
                    }
                    lang.send(audience, "admin.city.upkeep-set",
                            Replies.p("city", city.name()),
                            Replies.p("multiplier", raw));
                }));
    }

    /** SPEC 9.4.2: "Clears delinquency without payment." */
    private void forgiveDebt(Audience audience, CivitasServices current, City city) {
        audit(audience, current, "CITY_FORGIVEDEBT", city.name(), null, Map.of());
        Replies.reply(current.cities().adminForgiveDebt(city),
                audience, lang, scheduler, logger,
                updated -> lang.send(audience, "admin.city.debt-forgiven",
                        Replies.p("city", city.name())));
    }

    // ==================================================================================
    // Argument shapes
    // ==================================================================================

    @FunctionalInterface
    private interface CityAction {
        void run(Audience audience, CivitasServices services, City city);
    }

    @FunctionalInterface
    private interface CityArgAction {
        void run(Audience audience, CivitasServices services, City city, String argument);
    }

    private LiteralArgumentBuilder<CommandSourceStack> cityOnly(String name, CityAction action) {
        return Commands.literal(name)
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(this::suggestCities)
                        .executes(context -> withCity(context, (audience, current, city) ->
                                action.run(audience, current, city))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> cityAndWord(String name,
                                                                   CityArgAction action) {
        return Commands.literal(name)
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(this::suggestCities)
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(context -> withCity(context,
                                        (audience, current, city) -> action.run(audience, current,
                                                city, StringArgumentType.getString(context,
                                                        "value"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> cityAndGreedy(String name,
                                                                      CityArgAction action) {
        return Commands.literal(name)
                .then(Commands.argument("city", StringArgumentType.word())
                        .suggests(this::suggestCities)
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(context -> withCity(context,
                                        (audience, current, city) -> action.run(audience, current,
                                                city, StringArgumentType.getString(context,
                                                        "value"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> cityAndPlayer(String name,
                                                                     CityArgAction action) {
        return cityAndWord(name, action);
    }

    private int withCity(CommandContext<CommandSourceStack> context, CityAction action) {
        Audience audience = context.getSource().getSender();
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(context, "city");
        current.registry().cityByName(name).ifPresentOrElse(
                city -> action.run(audience, current, city),
                () -> lang.send(audience, "city.unknown"));
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private void resolve(CivitasServices current, Audience audience, String name,
                         java.util.function.Consumer<PlayerLookup.Resolved> then) {
        current.lookup().resolve(name).whenComplete((resolved, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(audience, "command.error");
                return;
            }
            if (resolved == null || resolved.isEmpty()) {
                lang.send(audience, "player.unknown", Replies.p("player", name));
                return;
            }
            then.accept(resolved.get());
        }));
    }

    private void audit(Audience audience, CivitasServices current, String action, String target,
                       String reason, Map<String, String> metadata) {
        current.audit().record(actorOf(audience), action, target, reason, metadata);
    }

    private static java.util.UUID actorOf(Audience audience) {
        return audience instanceof Player player ? player.getUniqueId() : null;
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
}
