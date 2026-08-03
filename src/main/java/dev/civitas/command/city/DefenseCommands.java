package dev.civitas.command.city;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.defense.DefenseUnit;
import dev.civitas.core.defense.DefenseUnitType;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /city defense}, SPEC 9.2 and 12.3. */
public final class DefenseCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public DefenseCommands(Supplier<CivitasServices> services, LangManager lang,
                           Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ArgumentBuilder<CommandSourceStack, ?> build() {
        return Commands.literal("defense")
                .executes(context -> openMenu(context.getSource().getSender()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource().getSender())))
                .then(Commands.literal("buy")
                        .then(Commands.argument("unit", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    CivitasServices current = services.get();
                                    if (current != null) {
                                        current.defense().catalogue().all().stream()
                                                .map(DefenseUnitType::key)
                                                .filter(key -> key.startsWith(
                                                        builder.getRemaining()
                                                                .toLowerCase(
                                                                    java.util.Locale.ROOT)))
                                                .forEach(builder::suggest);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> buy(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "unit")))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> dismiss(context.getSource().getSender(),
                                        IntegerArgumentType.getInteger(context, "id")))));
    }

    // ==================================================================================
    // Subcommands
    // ==================================================================================

    private int openMenu(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        new dev.civitas.gui.menus.DefenseMenu(services.get().menus(), services.get(),
                context.player(), context.city(), null).open();
        return Command.SINGLE_SUCCESS;
    }

    private int list(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        List<DefenseUnit> units = services.get().defense().registry()
                .of(context.city().id());

        lang.sendRaw(audience, "defense.list-header",
                LangManager.placeholder("active", String.valueOf(units.stream()
                        .filter(DefenseUnit::active).count())),
                LangManager.placeholder("max", String.valueOf(
                        services.get().defense().maxUnits(context.city()))));

        if (units.isEmpty()) {
            lang.sendRaw(audience, "defense.list-empty");
            return Command.SINGLE_SUCCESS;
        }
        for (DefenseUnit unit : units) {
            String name = services.get().defense().catalogue().byKey(unit.type())
                    .map(DefenseUnitType::displayName)
                    .orElse(unit.type());
            lang.sendRaw(audience, unit.active() ? "defense.list-entry"
                            : "defense.list-entry-inactive",
                    LangManager.placeholder("id", String.valueOf(unit.id())),
                    LangManager.placeholder("unit", name),
                    LangManager.placeholder("world", unit.world()),
                    LangManager.placeholder("x", String.valueOf((int) unit.x())),
                    LangManager.placeholder("z", String.valueOf((int) unit.z())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int buy(Audience audience, String key) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Optional<DefenseUnitType> type = services.get().defense().catalogue().byKey(key);
        if (type.isEmpty()) {
            lang.send(context.player(), "defense.unknown-unit-named",
                    LangManager.placeholder("unit", key));
            return Command.SINGLE_SUCCESS;
        }

        Replies.reply(services.get().defense()
                        .purchase(context.player().getUniqueId(), context.city(), type.get()),
                context.player(), lang, scheduler, logger,
                egg -> giveEgg(context.player(), egg, type.get()));
        return Command.SINGLE_SUCCESS;
    }

    private void giveEgg(Player player, ItemStack egg, DefenseUnitType type) {
        player.getInventory().addItem(egg).values()
                .forEach(left -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), left));
        lang.send(player, "defense.bought",
                LangManager.placeholder("unit", type.displayName()));
    }

    private int dismiss(Audience audience, int id) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Optional<DefenseUnit> unit = services.get().defense().registry().byId(id);
        if (unit.isEmpty() || unit.get().cityId() != context.city().id()) {
            lang.send(context.player(), "defense.not-yours");
            return Command.SINGLE_SUCCESS;
        }

        Replies.reply(services.get().defense()
                        .dismiss(context.player().getUniqueId(), context.city(), unit.get()),
                context.player(), lang, scheduler, logger,
                removed -> lang.send(context.player(), "defense.dismissed"));
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private record Context(Player player, City city) { }

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
}
