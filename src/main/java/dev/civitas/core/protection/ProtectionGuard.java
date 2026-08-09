package dev.civitas.core.protection;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.civitas.core.claim.ChunkKey;
import dev.civitas.lang.LangManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The bridge between Bukkit events and {@link ProtectionService}.
 *
 * <p>Listeners hold one of these and ask it a single question. It resolves the bypass
 * permission, converts a {@link Location} into the world and chunk the service wants, and
 * tells the player why they were refused.
 *
 * <p>Refusals are throttled per player. Walking into a claim and holding down left-click
 * produces one denial per tick, and twenty identical lines a second is worse than silence.
 */
public final class ProtectionGuard {

    /** The SPEC 10 node that lets an admin build anywhere. */
    public static final String BYPASS_PERMISSION = "civitas.bypass.claim";

    private final ProtectionService protection;
    private final LangManager lang;

    /** When each player was last told they could not do something. */
    private final Map<UUID, Long> lastDenial = new ConcurrentHashMap<>();

    public ProtectionGuard(ProtectionService protection, LangManager lang) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public ProtectionService service() {
        return protection;
    }

    /**
     * Whether the player may act here, telling them if not.
     *
     * @return true if the event should proceed
     */
    public boolean allows(Player player, Location location, ProtectionAction action) {
        ProtectionDecision decision = decide(player, location, action, true);
        if (decision.denied()) {
            notify(player, decision);
        }
        return decision.allowed();
    }

    /**
     * The same question without a message.
     *
     * <p>For events that fire many times for one player action, such as an explosion's block
     * list, where a message per block would be absurd.
     *
     * <p><b>And without a SPEC 26.2 violation.</b> A refusal the player was never shown is not
     * something they kept doing after being told; SPEC 26.2's whole structure is that a
     * trespasser is warned before anything happens to them, and counting a refusal they could
     * not see would break that at the first step. Concretely, the two callers are stepping on
     * somebody's pressure plate — silent by design, "a player walking past a door should not be
     * nagged for something they did with their feet" — and the second half of a bucket pour,
     * whose first half already counted.
     */
    public boolean allowsSilently(Player player, Location location, ProtectionAction action) {
        return decide(player, location, action, false).allowed();
    }

    private ProtectionDecision decide(Player player, Location location, ProtectionAction action,
                                      boolean countsAsViolation) {
        if (location == null || location.getWorld() == null) {
            return ProtectionDecision.ALLOWED;
        }
        int chunkX = ChunkKey.toChunk(location.getBlockX());
        int chunkZ = ChunkKey.toChunk(location.getBlockZ());
        ProtectionDecision decision = protection.check(
                player.getUniqueId(),
                player.hasPermission(BYPASS_PERMISSION),
                location.getWorld().getName(),
                chunkX, chunkZ,
                action);

        if (countsAsViolation) {
            reportViolation(player, location, chunkX, chunkZ, decision);
        }
        return decision;
    }

    /**
     * Reports a violation for something no protection check covers, SPEC 26.2.
     *
     * <p>Two of SPEC 26.2's six sources never reach {@link #decide} at all. "Damaging a defense
     * unit" is refused before the guard is asked, because most units are hostile mob types and
     * {@code EntityProtectionListener} lets hostile mobs be hit. "Damaging a city member" is
     * decided by {@code PvpPolicy}, which cancels without ever producing a protection decision.
     * Both are violations SPEC names explicitly, so both call this instead.
     *
     * <p>It runs the ordinary {@code ENTITY_DAMAGE} check and throws the answer away, purely so
     * that "is this person a non-member here" is answered by the same code as everywhere else.
     * Re-deciding it locally would be a second membership rule, and the trusted-ally exemption
     * SPEC 26.2 requires would then have to be remembered twice.
     */
    public void reportDirectViolation(Player player, Location location) {
        if (violations != null) {
            decide(player, location, ProtectionAction.ENTITY_DAMAGE, true);
        }
    }

    // ==================================================================================
    // SPEC 26.2's violations
    // ==================================================================================

    /**
     * Tells the trespass response about a refusal, if it was one that counts.
     *
     * <p>Reported from here rather than from each listener because every protected action
     * already funnels through this one method, and six hooks would be six chances for a later
     * milestone to add a seventh protected action and forget.
     *
     * <p><b>Only {@code NOT_A_MEMBER}.</b> SPEC 26.2 counts violations "by a non-member", and
     * the obvious reading — that any refusal is a violation — is wrong in a way that would be
     * very visible in play: a city's own citizen who lacks {@code CONTAINER} is refused too,
     * and counting it would have a city's guards warn and then attack the people who live
     * there for rattling a locked chest. Members are refused with {@code NO_CITY_PERMISSION};
     * trusted allies are not refused at all, so both fall out of this check rather than
     * needing one of their own.
     */
    private void reportViolation(Player player, Location location, int chunkX, int chunkZ,
                                 ProtectionDecision decision) {
        if (violations == null || decision.allowed()
                || !"NOT_A_MEMBER".equals(decision.reason())) {
            return;
        }
        protection.cityAt(location.getWorld().getName(), chunkX, chunkZ).ifPresent(city ->
                violations.violated(city.id(), player, location));
    }

    private Violations violations;

    /** What the guard reports a trespass to. Filled by M12c. */
    @FunctionalInterface
    public interface Violations {

        void violated(int cityId, Player player, Location where);
    }

    public void useViolations(Violations sink) {
        this.violations = Objects.requireNonNull(sink, "sink");
    }

    /** Sends a refusal on the action bar, at most once per cooldown. */
    public void notify(Player player, ProtectionDecision decision) {
        if (decision.messageKey() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDenial.get(player.getUniqueId());
        if (last != null && now - last < protection.denyMessageCooldownMillis()) {
            return;
        }
        lastDenial.put(player.getUniqueId(), now);

        player.sendActionBar(lang.get(decision.messageKey(),
                LangManager.placeholders(decision.placeholders())));
    }

    /** Forgets a player's throttle state, so a rejoin starts clean. */
    public void forget(UUID player) {
        lastDenial.remove(player);
    }
}
