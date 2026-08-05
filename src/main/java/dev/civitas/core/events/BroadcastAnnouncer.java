package dev.civitas.core.events;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import dev.civitas.lang.LangManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The real {@link EventScheduler.EventAnnouncer}: SPEC 13.5's server-wide announcements.
 *
 * <p>Every online player, rather than only city members, because SPEC 13.5 calls these
 * "shared server moments" and a player who has not founded a city yet is exactly who a
 * Founders' Week is aimed at.
 */
public final class BroadcastAnnouncer implements EventScheduler.EventAnnouncer {

    private final LangManager lang;

    public BroadcastAnnouncer(LangManager lang) {
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    @Override
    public void announceUpcoming(ServerEvent event, long millisUntilStart) {
        broadcast("event.announce.upcoming",
                LangManager.placeholder("event", plain(event.type().nameKey())),
                LangManager.placeholder("description", plain(event.type().descriptionKey())),
                LangManager.placeholder("when", describe(millisUntilStart)));
    }

    @Override
    public void announceEnded(ServerEvent event) {
        broadcast("event.announce.ended",
                LangManager.placeholder("event", plain(event.type().nameKey())));
    }

    private void broadcast(String key,
                           net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... values) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            lang.send(player, key, values);
        }
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

    private static String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }
}
