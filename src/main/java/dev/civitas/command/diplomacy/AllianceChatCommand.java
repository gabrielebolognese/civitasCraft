package dev.civitas.command.diplomacy;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.diplomacy.Alliance;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code /ac}, alliance chat, SPEC 9.2 and SPEC 14.1.
 *
 * <p>Reaches the speaker's own city and every city allied to it. A city in the SPEC 14.2
 * notice period is still an ally and still hears it: the whole point of the notice is that
 * the alliance holds until it expires.
 *
 * <p>The message is sent with {@code Placeholder.unparsed}, so a player cannot inject
 * MiniMessage into a channel that reaches several cities at once.
 */
public final class AllianceChatCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;

    public AllianceChatCommand(Supplier<CivitasServices> services, LangManager lang) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("ac")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> usage(context.getSource().getSender()))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> speak(context.getSource().getSender(),
                                StringArgumentType.getString(context, "message"))))
                .build();
    }

    private int usage(Audience audience) {
        lang.send(audience, "diplomacy.chat-usage");
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

        // Collected into a set first: two cities allied to the speaker's city may share a
        // member only in theory, but a city that somehow appears twice must not double-send.
        Set<UUID> recipients = new LinkedHashSet<>();
        own.get().members().forEach(member -> recipients.add(member.uuid()));

        boolean anyAlly = false;
        for (Alliance alliance : current.diplomacy().registry()
                .activeAlliancesOf(own.get().id())) {
            Optional<City> ally = current.registry()
                    .city(alliance.otherThan(own.get().id()));
            if (ally.isPresent()) {
                anyAlly = true;
                ally.get().members().forEach(member -> recipients.add(member.uuid()));
            }
        }
        if (!anyAlly) {
            lang.send(player, "diplomacy.chat-no-allies");
            return Command.SINGLE_SUCCESS;
        }

        for (UUID uuid : recipients) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                lang.send(online, "diplomacy.chat-format",
                        LangManager.placeholder("city", own.get().name()),
                        LangManager.placeholder("player", player.getName()),
                        LangManager.placeholder("message", message));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
