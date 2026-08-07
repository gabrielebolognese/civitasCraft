package dev.civitas.command.city;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /cc} and {@code /city chat}, SPEC 9.2.
 *
 * <p>The last command stub in the plugin. M13 built {@code /ac} because alliance chat was in
 * its deliverable and left this one, which was assigned to no milestone at all and has been
 * carrying an "M2" marker since the tree was first declared. It is closed here.
 *
 * <p>Deliberately a sibling of {@link dev.civitas.command.diplomacy.AllianceChatCommand}
 * rather than a mode of it. The two differ in exactly one thing — who hears it — but that one
 * thing is the whole security property: a message meant for your own city must not be able to
 * reach an ally through a flag that was set wrongly. Two small classes cannot make that
 * mistake; one class with a boolean can.
 *
 * <p>The message goes in with {@code Placeholder.unparsed}, so a player cannot inject
 * MiniMessage into a channel that reaches everyone they play with.
 */
public final class CityChatCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;

    public CityChatCommand(Supplier<CivitasServices> services, LangManager lang) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /**
     * The root command.
     *
     * <p>Registered as {@code citychat} with {@code cc} as its alias rather than the reverse.
     * Brigadier has no notion of an alias, so the literal is what appears in the command tree
     * and in every error message; {@code /cc} is what a player types.
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("citychat")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> usage(context.getSource().getSender()))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> speak(context.getSource().getSender(),
                                StringArgumentType.getString(context, "message"))))
                .build();
    }

    /** {@code /city chat <message>}, the long form SPEC 9.2 lists beside {@code /cc}. */
    public ArgumentBuilder<CommandSourceStack, ?> subcommand() {
        return Commands.literal("chat")
                .executes(context -> usage(context.getSource().getSender()))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> speak(context.getSource().getSender(),
                                StringArgumentType.getString(context, "message"))));
    }

    private int usage(Audience audience) {
        lang.send(audience, "chat.city-usage");
        return Command.SINGLE_SUCCESS;
    }

    private int speak(Audience audience, String message) {
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(audience, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(audience instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        Optional<City> own = current.registry().cityOf(player.getUniqueId());
        if (own.isEmpty()) {
            lang.send(player, "city.none");
            return Command.SINGLE_SUCCESS;
        }
        if (message.isBlank()) {
            return usage(player);
        }

        for (CityMember member : own.get().members()) {
            Player online = Bukkit.getPlayer(member.uuid());
            if (online != null) {
                lang.send(online, "chat.city-channel",
                        LangManager.placeholder("city", own.get().name()),
                        LangManager.placeholder("player", player.getName()),
                        LangManager.placeholder("message", message));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
