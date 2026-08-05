package dev.civitas.core.events;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The SPEC 13.5 boss bar: what is running, and how long is left.
 *
 * <h2>One bar, shown and hidden</h2>
 * Adventure's boss bar is an object players are added to and removed from, not something
 * drawn each tick. So there is exactly one bar here for the whole server: it is shown when an
 * event starts, its title and progress are updated on a timer, and it is hidden when the event
 * ends. A player who joins mid-event is added on their next tick.
 *
 * <p>Hiding it is the part that has to be right. A bar left on screen after its event ended
 * tells every player something false, and unlike a stuck multiplier they can all see it.
 */
public final class EventBossBar {

    private final ConfigManager configs;
    private final LangManager lang;

    private BossBar bar;
    private ServerEventType showing;

    public EventBossBar(ConfigManager configs, LangManager lang) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public boolean isEnabled() {
        return configs.get(ConfigFile.EVENTS).getBoolean("events.boss-bar", true);
    }

    /** Whether a bar is on screen right now. */
    public boolean isShowing() {
        return bar != null;
    }

    /**
     * Brings the bar into line with what is running.
     *
     * <p>Called on a timer. Everything it does is idempotent, so a tick with nothing to change
     * costs a comparison.
     */
    public void refresh(Optional<ServerEvent> running, long now) {
        if (!isEnabled() || running.isEmpty()) {
            hide();
            return;
        }

        ServerEvent event = running.get();
        if (bar == null || showing != event.type()) {
            hide();
            bar = BossBar.bossBar(title(event, now), event.progress(now),
                    BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            showing = event.type();
        } else {
            bar.name(title(event, now));
            bar.progress(event.progress(now));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    /** Takes the bar off every screen. Safe to call when there is none. */
    public void hide() {
        if (bar == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        bar = null;
        showing = null;
    }

    private Component title(ServerEvent event, long now) {
        return lang.get("event.boss-bar",
                LangManager.placeholder("event", plain(event.type().nameKey())),
                LangManager.placeholder("remaining", describe(event.millisRemaining(now))));
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

    /** A rough remaining time, since the bar is glanced at rather than read. */
    static String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 24) {
            return TimeUnit.MILLISECONDS.toDays(millis) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }
}
