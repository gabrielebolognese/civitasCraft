package dev.civitas.core.combat;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC 33.8's combat tag: what stops a fight being ended by a menu.
 *
 * <p>A player is tagged when they deal or receive damage from another player. While tagged they
 * cannot teleport, open the city vault, or log out safely. They can still walk, ride, boat,
 * pearl and take a portal — SPEC 33.8 draws the line exactly there: "Escaping by moving is
 * always legitimate. Escaping by menu is not."
 *
 * <h2>Two durations, and why SPEC argues for the shorter one</h2>
 *
 * <p>30 seconds in peacetime, 120 in a war. SPEC 33.8 makes the case against the obvious longer
 * number in its own words: "120 seconds, not 300. Five minutes is long enough that a single
 * arrow from an unseen archer locks a player out of teleporting for the length of a real
 * activity, and a harasser who lands one hit every four minutes can keep a target tagged
 * indefinitely. Two minutes is long enough that fleeing an ambush by teleport is impossible,
 * which is the actual goal."
 *
 * <h2>It refreshes, it does not stack</h2>
 *
 * <p>Each hit resets the timer to its full duration rather than adding to it. Stacking is the
 * natural implementation and it is the harassment vector SPEC's own paragraph describes: twenty
 * arrows would mean ten minutes of lockout from one engagement.
 *
 * <p>Pure and in memory. A tag is a fact about the last two minutes and nothing about it is
 * worth surviving a restart — a player who was tagged when the server died has already had their
 * fight interrupted by something much larger.
 */
public final class CombatTag {

    /** When a tag ends, and whether it was a war tag, which the action bar shows differently. */
    public record Tag(long until, boolean war) {
    }

    private final Map<UUID, Tag> tagged = new ConcurrentHashMap<>();

    private final long peacetimeMillis;
    private final long warMillis;

    public CombatTag(long peacetimeMillis, long warMillis) {
        this.peacetimeMillis = Math.max(0, peacetimeMillis);
        this.warMillis = Math.max(0, warMillis);
    }

    /**
     * Tags both parties to a hit.
     *
     * <p>Both, because SPEC 33.8 says "deal or receive". Tagging only the victim would let an
     * archer fire and teleport out, which is the whole behaviour this exists to prevent.
     */
    public void hit(UUID attacker, UUID victim, boolean war, long now) {
        tag(attacker, war, now);
        tag(victim, war, now);
    }

    /**
     * Tags one player.
     *
     * <p>A war tag never shortens into a peacetime one while it is running. SPEC 33.9 case 115
     * says a peacetime tag extends when a war becomes ACTIVE; the reverse — a stray peacetime
     * hit cutting a war tag from two minutes to thirty seconds — would be a way to shorten it
     * deliberately.
     */
    public void tag(UUID player, boolean war, long now) {
        Objects.requireNonNull(player, "player");
        long duration = war ? warMillis : peacetimeMillis;
        if (duration <= 0) {
            return;
        }
        Tag current = tagged.get(player);
        boolean stillWar = war
                || (current != null && current.war() && current.until() > now);
        long until = now + (stillWar ? warMillis : duration);

        // Refresh, never stack. Taking the later of the two is what makes a second hit reset
        // the clock rather than extend an already-long one.
        if (current != null && current.until() > until && current.war() == stillWar) {
            return;
        }
        tagged.put(player, new Tag(until, stillWar));
    }

    /** Whether this player is tagged right now. */
    public boolean isTagged(UUID player, long now) {
        Tag tag = tagged.get(player);
        if (tag == null) {
            return false;
        }
        if (now >= tag.until()) {
            tagged.remove(player);
            return false;
        }
        return true;
    }

    /** How long is left, in millis, or zero when untagged. */
    public long remaining(UUID player, long now) {
        Tag tag = tagged.get(player);
        return tag == null || now >= tag.until() ? 0L : tag.until() - now;
    }

    /** Whether the running tag is a war one, which the action bar colours differently. */
    public boolean isWarTag(UUID player, long now) {
        Tag tag = tagged.get(player);
        return tag != null && now < tag.until() && tag.war();
    }

    public Optional<Tag> of(UUID player) {
        return Optional.ofNullable(tagged.get(player));
    }

    /**
     * SPEC 33.9 case 114: a war ending shortens a war tag to the peacetime remainder.
     *
     * <p>Not cleared. The fight that produced it is still happening; only its stakes changed.
     */
    public void warEnded(UUID player, long now) {
        Tag tag = tagged.get(player);
        if (tag == null || !tag.war() || now >= tag.until()) {
            return;
        }
        long shortened = Math.min(tag.until(), now + peacetimeMillis);
        tagged.put(player, new Tag(shortened, false));
    }

    /** SPEC 33.9 case 115: a war starting extends a peacetime tag to the war duration. */
    public void warStarted(UUID player, long now) {
        Tag tag = tagged.get(player);
        if (tag == null || tag.war() || now >= tag.until()) {
            return;
        }
        tagged.put(player, new Tag(now + warMillis, true));
    }

    /** Dropped on death and on quit: the tag has done its job either way. */
    public void clear(UUID player) {
        tagged.remove(player);
    }

    public int size() {
        return tagged.size();
    }

    public long peacetimeMillis() {
        return peacetimeMillis;
    }

    public long warMillis() {
        return warMillis;
    }
}
