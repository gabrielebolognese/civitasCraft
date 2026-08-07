package dev.civitas.command;

import java.util.Locale;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Suggestion providers shared across command trees, SPEC 19's "tab completion everywhere".
 *
 * <p>Extracted in M23 from the private copy in {@code CityCommand}. The admin tree took six
 * player-name arguments with no completion at all, and copying the provider a seventh time
 * would have been the wrong way to fix that.
 *
 * <p>Every provider filters on what the player has typed so far. Brigadier will do that
 * itself for a literal, but not for suggestions a provider adds, so leaving it out gives the
 * player the whole list back however much of a name they have entered.
 */
public final class Suggest {

    private Suggest() {
    }

    /**
     * Online player names.
     *
     * <p>Online only, even where the command itself is offline-safe — SPEC 9.4.2 requires
     * {@code /ca city setmayor} to work on an absent player, and it still does. Completing
     * from the whole player table would mean a database read per keystroke, which SPEC 2.1
     * forbids on the server thread, and would offer a list that grows without bound. A name
     * typed in full is always accepted whether or not it was offered.
     */
    public static SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (context, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }
}
