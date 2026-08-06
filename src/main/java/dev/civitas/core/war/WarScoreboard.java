package dev.civitas.core.war;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRegistry;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The SPEC 8.8 war sidebar, toggled by {@code /war scoreboard}.
 *
 * <h2>Off by default</h2>
 * SPEC 9.3 makes this a toggle rather than something the plugin imposes. A sidebar is the most
 * intrusive piece of interface a plugin owns: it covers a third of the screen and there is no
 * polite way to share it with another plugin that wants the same slot. So nobody sees one
 * until they ask, and asking again takes it away.
 *
 * <h2>Composing the lines is separate from showing them</h2>
 * {@link #lines} is a pure function of the war and the clock, so what the sidebar says can be
 * tested without a scoreboard, a server, or a player.
 */
public final class WarScoreboard implements org.bukkit.event.Listener {

    private static final String OBJECTIVE = "civitas_war";

    private final WarRegistry wars;
    private final CityRegistry cities;
    private final LangManager lang;

    /** Players who asked to see it. */
    private final Set<UUID> watching = ConcurrentHashMap.newKeySet();

    public WarScoreboard(WarRegistry wars, CityRegistry cities, LangManager lang) {
        this.wars = Objects.requireNonNull(wars, "wars");
        this.cities = Objects.requireNonNull(cities, "cities");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /** @return whether the sidebar is now on for this player */
    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (watching.remove(id)) {
            hide(player);
            return false;
        }
        watching.add(id);
        return true;
    }

    public boolean isWatching(UUID player) {
        return watching.contains(player);
    }

    public void forget(UUID player) {
        watching.remove(player);
    }

    /**
     * Drops a departing player's preference.
     *
     * <p>The set would otherwise grow for the life of the process, and a returning player gets
     * a fresh scoreboard from the server anyway, so remembering the toggle across a session
     * would leave them with an entry that refers to nothing.
     */
    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /**
     * What the sidebar says for a war right now.
     *
     * <p>Two shapes, matching SPEC 8.8: a countdown while the fighting has not started, and
     * the score once it has.
     */
    public List<Component> lines(War war, long now) {
        List<Component> lines = new ArrayList<>();
        String attackerName = nameOf(war.attackerCityId());
        String defenderName = nameOf(war.defenderCityId());

        switch (war.state()) {
            case DECLARED, PREP -> {
                lines.add(lang.get("war.board.phase",
                        LangManager.placeholder("phase", plain(war.state().messageKey()))));
                lines.add(lang.get("war.board.sides",
                        LangManager.placeholder("attacker", attackerName),
                        LangManager.placeholder("defender", defenderName)));
                lines.add(lang.get("war.board.starts-in",
                        LangManager.placeholder("remaining",
                                describe(war.millisUntilNextPhase(now)))));
            }
            case ACTIVE -> {
                lines.add(lang.get("war.board.score",
                        LangManager.placeholder("city", attackerName),
                        LangManager.placeholder("score", String.valueOf(war.attackerScore()))));
                lines.add(lang.get("war.board.score",
                        LangManager.placeholder("city", defenderName),
                        LangManager.placeholder("score", String.valueOf(war.defenderScore()))));
                lines.add(lang.get("war.board.ends-in",
                        LangManager.placeholder("remaining",
                                describe(war.millisUntilNextPhase(now)))));
            }
            default -> lines.add(lang.get("war.board.over"));
        }
        return lines;
    }

    /**
     * Brings every watcher's sidebar up to date.
     *
     * <p>On the server thread, and cheap when nobody is watching or nobody is at war, which is
     * the normal case on any server most of the time.
     */
    public void refresh(long now) {
        if (watching.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!watching.contains(player.getUniqueId())) {
                continue;
            }
            Optional<War> war = cities.cityOf(player.getUniqueId())
                    .flatMap(city -> wars.engagedWarOf(city.id()));
            if (war.isEmpty()) {
                hide(player);
                continue;
            }
            show(player, war.get(), now);
        }
    }

    private void show(Player player, War war, long now) {
        Scoreboard board = player.getScoreboard();
        if (board == null || board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY,
                    lang.get("war.board.title"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // Cleared and redrawn rather than diffed: a sidebar is at most four lines and the
        // scores change every few seconds anyway.
        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        List<Component> lines = lines(war, now);
        int score = lines.size();
        for (Component line : lines) {
            String text = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(line);
            // Scoreboard entries must be unique; padding with a colour reset keeps two
            // identical lines apart without showing anything.
            objective.getScore(uniquify(text, score)).setScore(score);
            score--;
        }
    }

    private static String uniquify(String text, int index) {
        String padded = text + "§r".repeat(Math.max(0, index % 8));
        return padded.length() > 40 ? padded.substring(0, 40) : padded;
    }

    private void hide(Player player) {
        Scoreboard board = player.getScoreboard();
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective != null) {
            objective.unregister();
        }
    }

    private String nameOf(int cityId) {
        return cities.city(cityId).map(City::name).orElse("?");
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get(key));
    }

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
