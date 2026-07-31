package dev.civitas.listener;

import java.util.Objects;
import java.util.Optional;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.lang.LangManager;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prefixes chat with the speaker's city tag, SPEC 20 decision 6.
 *
 * <p>Registered at {@link EventPriority#LOW} and implemented by wrapping whatever renderer is
 * already set, rather than replacing it, so a dedicated chat plugin still has the last word
 * on formatting. Operators who want that plugin to own the prefix entirely can turn this off
 * with {@code cities.yml chat.city-tag-prefix-enabled}.
 *
 * <p>This runs on an async thread. It only reads the city cache, which is concurrent, and
 * touches no other Bukkit API.
 */
public final class CityChatListener implements Listener {

    private final CityRegistry registry;
    private final ConfigManager configs;
    private final LangManager lang;

    public CityChatListener(CityRegistry registry, ConfigManager configs, LangManager lang) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!configs.get(ConfigFile.CITIES).getBoolean("chat.city-tag-prefix-enabled", true)) {
            return;
        }

        Optional<City> city = registry.cityOf(event.getPlayer().getUniqueId());
        if (city.isEmpty()) {
            return;
        }
        String tag = city.get().tag();
        if (tag == null || tag.isBlank()) {
            return;
        }

        Component prefix = lang.get("chat.city-format", LangManager.placeholder("tag", tag));
        ChatRenderer previous = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) ->
                prefix.append(previous.render(source, sourceDisplayName, message, viewer)));
    }
}
