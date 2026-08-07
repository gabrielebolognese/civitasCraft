package dev.civitas.listener;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.CityRegistry;
import dev.civitas.core.city.DormancyCache;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.dao.PlayerNoticeDao;
import dev.civitas.storage.row.PlayerNoticeRow;
import dev.civitas.util.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The two halves of SPEC 17.1 that happen when somebody comes back.
 *
 * <ul>
 *   <li><b>Case 2.</b> "On any member login, protection restores instantly." Instantly means
 *       on this event, not on the next sweep — a player logging in to defend their city must
 *       not have to wait out an hour of interval while somebody digs through the walls. It is
 *       a set removal, so it costs nothing and takes effect on the very next block event.</li>
 *   <li><b>Case 1.</b> "Old mayor is demoted to Co-Mayor, notified on next login." The notice
 *       was written to storage when the transfer happened, because the person it is for was
 *       absent by definition. This is where it is delivered, and deleted.</li>
 * </ul>
 *
 * <p>The wake-up is synchronous and the notice is not. Undoing dormancy is an in-memory set
 * operation that must have happened before the player's first block interaction; reading their
 * notice queue is a database call, and SPEC 2.1 does not allow that on the server thread.
 */
public final class InactivityJoinListener implements Listener {

    private final CityRegistry cities;
    private final DormancyCache dormancy;
    private final PlayerNoticeDao notices;
    private final LangManager lang;
    private final Scheduler scheduler;
    private final Logger logger;

    public InactivityJoinListener(CityRegistry cities, DormancyCache dormancy,
                                  PlayerNoticeDao notices, LangManager lang,
                                  Scheduler scheduler, Logger logger) {
        this.cities = Objects.requireNonNull(cities, "cities");
        this.dormancy = Objects.requireNonNull(dormancy, "dormancy");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        cities.cityOf(player.getUniqueId())
                .ifPresent(city -> dormancy.wake(city.id()));

        deliverNotices(player);
    }

    private void deliverNotices(Player player) {
        try {
            notices.findFor(player.getUniqueId())
                    .thenAccept(waiting -> scheduler.runOnMain(() -> show(player, waiting)))
                    .exceptionally(error -> {
                        logger.log(Level.WARNING, "Could not read notices for "
                                + player.getName(), error);
                        return null;
                    });
        } catch (RuntimeException e) {
            // A closed pool throws out of the call rather than failing the future — the trap
            // recorded at M18. A lost notice is not worth a stack trace in a player's face.
            logger.log(Level.WARNING, "Could not read notices for " + player.getName(), e);
        }
    }

    private void show(Player player, List<PlayerNoticeRow> waiting) {
        if (waiting.isEmpty() || !player.isOnline()) {
            return;
        }
        for (PlayerNoticeRow notice : waiting) {
            lang.send(player, notice.messageKey(),
                    LangManager.placeholders(placeholdersOf(notice)));
        }
        // Deleted only after they were shown. A notice that cleared itself before delivery
        // would be lost for good on a disconnect at the wrong moment, and the whole point of
        // storing it was that this player is hard to reach.
        try {
            notices.deleteFor(player.getUniqueId()).exceptionally(error -> {
                logger.log(Level.WARNING, "Could not clear notices for "
                        + player.getName(), error);
                return null;
            });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not clear notices for " + player.getName(), e);
        }
    }

    /**
     * Parses the placeholder blob.
     *
     * <p>A hand-rolled reader for a flat map of string to string, which is all this column
     * ever holds: the alternative is a JSON dependency for one field. Anything it cannot read
     * yields no placeholders rather than an exception, so a malformed row costs the values in
     * one message rather than the message itself.
     */
    static Map<String, String> placeholdersOf(PlayerNoticeRow notice) {
        String json = notice.placeholders();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        while (matcher.find()) {
            values.put(matcher.group(1),
                    matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return values;
    }
}
