package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.gui.menus.ChallengesMenu;
import dev.civitas.gui.menus.QuestsMenu;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /quests} and {@code /challenges}, SPEC 9.1.
 *
 * <p>One class for both: they are the same command shape over two screens, and both have to
 * make sure the day's or the week's assignment exists before opening anything.
 */
public final class QuestsCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public QuestsCommand(Supplier<CivitasServices> services, LangManager lang,
                         Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralCommandNode<CommandSourceStack> buildQuests() {
        return Commands.literal("quests")
                .requires(source -> source.getSender().hasPermission("civitas.quests.use"))
                .executes(context -> {
                    Player player = playerOrNull(context.getSource().getSender());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    // Assign today's quests if this is the first look, then show them.
                    services.get().quests()
                            .todaysQuests(player.getUniqueId(), System.currentTimeMillis())
                            .whenComplete((rows, error) -> scheduler.runOnMain(() ->
                                    new QuestsMenu(services.get().menus(), services.get(), player)
                                            .open()));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildChallenges() {
        return Commands.literal("challenges")
                .requires(source -> source.getSender().hasPermission("civitas.quests.use"))
                .executes(context -> {
                    Player player = playerOrNull(context.getSource().getSender());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    var city = services.get().registry().cityOf(player.getUniqueId());
                    if (city.isEmpty()) {
                        lang.send(player, "city.none");
                        return Command.SINGLE_SUCCESS;
                    }
                    services.get().challenges()
                            .thisWeek(city.get().id(), System.currentTimeMillis())
                            .whenComplete((rows, error) -> scheduler.runOnMain(() ->
                                    new ChallengesMenu(services.get().menus(), services.get(),
                                            player, city.get()).open()));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private Player playerOrNull(Audience audience) {
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return null;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return null;
        }
        return player;
    }
}
