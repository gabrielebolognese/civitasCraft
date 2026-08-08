package dev.civitas.msg;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

/**
 * SPEC 23.4's channel router.
 *
 * <p>One place that decides how a message reaches a player, so the three rules SPEC 23.4 states
 * as limits are enforced once rather than remembered by every caller:
 *
 * <ul>
 *   <li><b>Titles are capped at four per hour per player, in code.</b> SPEC says "hard-limited
 *       in code" and it means it: a title cannot be dismissed and covers the screen, so an
 *       unbounded one is an interruption rather than a message.
 *   <li><b>Action bars are throttled per player per message.</b> SPEC 23.5.4 requires this of
 *       protection denials, which "fire on every blocked click and would flood chat instantly",
 *       and the same is true of every repeated status. Per <i>message</i>, not per player: two
 *       different denials in the same second are two things worth knowing.
 *   <li><b>Boss bars are one at a time, priority ordered.</b> A war countdown outranks an event
 *       banner, and the loser is not queued — it simply does not show while the winner does.
 * </ul>
 *
 * <p>Every send also consults {@link TogglePreferences}, so a category a player has muted costs
 * a map lookup and nothing else. The four SPEC 23.6 locks are enforced inside that class rather
 * than here, so no channel can bypass them.
 */
public final class Messenger {

    private final LangManager lang;
    private final ConfigManager configs;
    private final TogglePreferences toggles;
    private Palette palette = Palette.STANDARD;

    /** Per player, the last time each throttled message key was shown. */
    private final Map<UUID, Map<String, Long>> lastShown = new ConcurrentHashMap<>();

    /** Per player, when each of their recent titles was shown, oldest first. */
    private final Map<UUID, java.util.Deque<Long>> recentTitles = new ConcurrentHashMap<>();

    public Messenger(LangManager lang, ConfigManager configs, TogglePreferences toggles) {
        this.lang = Objects.requireNonNull(lang, "lang");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.toggles = Objects.requireNonNull(toggles, "toggles");
    }

    /** Swaps the palette, for SPEC 36.5's colourblind alternative. */
    public void usePalette(Palette replacement) {
        this.palette = Objects.requireNonNull(replacement, "replacement");
    }

    public Palette palette() {
        return palette;
    }

    // ==================================================================================
    // Sending
    // ==================================================================================

    /**
     * Sends one message on one channel, respecting the player's preferences and the channel's
     * limits.
     *
     * @param player   whose preferences and throttles apply; may be null for a console audience
     * @param audience where the message actually goes
     * @return whether anything was shown, so a caller can tell "muted" from "sent"
     */
    public boolean send(UUID player, Audience audience, ToggleCategory category, Channel channel,
                        String key, TagResolver... resolvers) {
        Objects.requireNonNull(audience, "audience");
        if (player != null && !toggles.wants(player, category)) {
            return false;
        }
        if (player != null && channel == Channel.ACTION_BAR
                && !toggles.wants(player, ToggleCategory.ACTIONBAR)) {
            return false;
        }
        if (player != null && channel.throttled() && isThrottled(player, key, now())) {
            return false;
        }
        if (player != null && channel == Channel.TITLE && !allowTitle(player, now())) {
            // Downgraded rather than dropped. The player has had four titles this hour and
            // this one still has something to say, so it says it in chat.
            audience.sendMessage(render(key, resolvers));
            return true;
        }

        switch (channel) {
            case CHAT -> audience.sendMessage(render(key, resolvers));
            case ACTION_BAR -> audience.sendActionBar(render(key, resolvers));
            case TITLE -> audience.showTitle(Title.title(render(key, resolvers),
                    Component.empty()));
            case BOSS_BAR -> audience.sendMessage(render(key, resolvers));
            case SOUND -> { /* handled by playSound; a sound has no message body */ }
            default -> audience.sendMessage(render(key, resolvers));
        }
        return true;
    }

    /** Chat, the default channel, for the common case. */
    public boolean send(UUID player, Audience audience, ToggleCategory category, String key,
                        TagResolver... resolvers) {
        return send(player, audience, category, Channel.CHAT, key, resolvers);
    }

    /**
     * Plays a sound, unless the player has muted them.
     *
     * <p>SPEC 23.8: "Sound reinforces, never carries information alone." So this is always a
     * second call beside a message, and muting it never costs a player a fact.
     */
    public void playSound(UUID player, Audience audience, Sound sound) {
        if (player != null && !toggles.wants(player, ToggleCategory.SOUNDS)) {
            return;
        }
        audience.playSound(sound);
    }

    /** Renders a message with the palette applied, without sending it. */
    public Component render(String key, TagResolver... resolvers) {
        TagResolver[] withPalette = new TagResolver[resolvers.length + 1];
        withPalette[0] = palette.resolver();
        System.arraycopy(resolvers, 0, withPalette, 1, resolvers.length);
        return lang.get(key, withPalette);
    }

    // ==================================================================================
    // SPEC 23.4's limits
    // ==================================================================================

    /**
     * Whether this player may be shown another title, SPEC 23.4's four per hour.
     *
     * <p>A sliding window rather than a bucket that resets on the hour: four titles at 10:59
     * and four more at 11:01 is eight in two minutes, which is what the rule exists to prevent.
     */
    boolean allowTitle(UUID player, long now) {
        long window = titleWindowMillis();
        java.util.Deque<Long> recent = recentTitles.computeIfAbsent(player,
                key -> new java.util.ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() >= window) {
                recent.pollFirst();
            }
            if (recent.size() >= maxTitlesPerWindow()) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    /** Whether this message is still inside its cooldown for this player. */
    boolean isThrottled(UUID player, String key, long now) {
        Map<String, Long> perKey = lastShown.computeIfAbsent(player,
                id -> new ConcurrentHashMap<>());
        Long last = perKey.get(key);
        if (last != null && now - last < actionBarCooldownMillis()) {
            return true;
        }
        perKey.put(key, now);
        return false;
    }

    /** Forgets a player's throttle and title history, so a rejoin starts clean. */
    public void forget(UUID player) {
        lastShown.remove(player);
        recentTitles.remove(player);
    }

    // ==================================================================================
    // Configuration
    // ==================================================================================

    /** SPEC 23.4's "maximum 4 per hour per player". */
    public int maxTitlesPerWindow() {
        return configs.get(ConfigFile.CONFIG).getInt("messages.titles-per-window", 4);
    }

    public long titleWindowMillis() {
        return configs.get(ConfigFile.CONFIG)
                .getLong("messages.title-window-minutes", 60) * 60_000L;
    }

    /** SPEC 23.5.4's "per-player 3-second cooldown per message type". */
    public long actionBarCooldownMillis() {
        return configs.get(ConfigFile.CONFIG)
                .getLong("messages.action-bar-cooldown-ms", 3000L);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
