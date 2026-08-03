package dev.civitas.command.city;

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
import dev.civitas.core.upgrade.UpgradeService;
import dev.civitas.core.upgrade.UpgradeType;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/** {@code /city upgrade} and {@code /city vault}, SPEC 9.2. */
public final class UpgradeCommands {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public UpgradeCommands(Supplier<CivitasServices> services, LangManager lang,
                           Scheduler scheduler, Logger logger) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // /city upgrade
    // ==================================================================================

    public ArgumentBuilder<CommandSourceStack, ?> upgrade() {
        return Commands.literal("upgrade")
                .executes(context -> openMenu(context.getSource().getSender()))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (UpgradeType type : UpgradeType.values()) {
                                if (type.key().startsWith(builder.getRemaining()
                                        .toLowerCase(java.util.Locale.ROOT))) {
                                    builder.suggest(type.key());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> buy(context.getSource().getSender(),
                                StringArgumentType.getString(context, "key"))));
    }

    private int openMenu(Audience audience) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        new dev.civitas.gui.menus.UpgradesMenu(services.get().menus(), services.get(),
                context.player(), context.city(), null).open();
        return Command.SINGLE_SUCCESS;
    }

    private int buy(Audience audience, String key) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }
        Optional<UpgradeType> type = UpgradeType.parse(key);
        if (type.isEmpty()) {
            lang.send(context.player(), "upgrade.unknown",
                    LangManager.placeholder("key", key));
            return Command.SINGLE_SUCCESS;
        }

        Replies.reply(services.get().upgrades()
                        .purchase(context.player().getUniqueId(), context.city(), type.get()),
                context.player(), lang, scheduler, logger,
                purchase -> lang.send(context.player(), "upgrade.purchased",
                        LangManager.placeholder("name",
                                services.get().upgrades().displayName(purchase.type())),
                        LangManager.placeholder("level", String.valueOf(purchase.level()))));
        return Command.SINGLE_SUCCESS;
    }

    // ==================================================================================
    // /city vault
    // ==================================================================================

    public ArgumentBuilder<CommandSourceStack, ?> vault() {
        return Commands.literal("vault")
                .executes(context -> openVault(context.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> openVault(context.getSource().getSender(),
                                IntegerArgumentType.getInteger(context, "page"))));
    }

    /**
     * Opens a vault page.
     *
     * @param page as a player counts them, from one; the service counts from zero
     */
    private int openVault(Audience audience, int page) {
        Context context = contextOf(audience);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }

        Result<Integer> allowed = services.get().vaults()
                .checkAccess(context.player().getUniqueId(), context.city(), page - 1);
        if (allowed instanceof Result.Failure<Integer> failure) {
            Replies.sendFailure(context.player(), lang, failure);
            return Command.SINGLE_SUCCESS;
        }

        services.get().vaultView().open(context.player(), context.city(), page - 1);
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

    /** Exposed for the help page. */
    public UpgradeService upgrades() {
        return services.get().upgrades();
    }
}
