package dev.civitas.core.combat;

import java.util.Locale;
import java.util.Optional;

/**
 * Places where PvP is off whatever else is true, SPEC 37's {@code exclusion-zones}.
 *
 * <p>A zone is not a rule about who is fighting, it is a rule about where they are standing.
 * That distinction matters once wars exist: SPEC 33.5 keeps the resource worlds peaceful even
 * during a war, so "is there a war on" cannot be the only thing the decision looks at.
 */
public enum PvpZone {

    /**
     * The built hub, SPEC 32.7.
     *
     * <p>"PvP is disabled there under all circumstances including active wars." It holds the
     * onboarding path and the recruitment board, so it is where a brand new player stands
     * reading signs.
     */
    SPAWN("combat.pvp-denied-spawn"),

    /** Any chunk an admin marked with {@code /ca claim protect}, SPEC 9.4.3. */
    ADMIN_PROTECTED("combat.pvp-denied-admin"),

    /**
     * A personal Mining Claim, SPEC 32.6.
     *
     * <p>Listed by SPEC 37 and not yet buildable: mining claims arrive with their own
     * milestone. Until then {@code PvpPolicy.isMiningClaim} answers no, in the same shape as
     * every other seam in this plugin.
     */
    MINING_CLAIM("combat.pvp-denied-mining-claim");

    private final String messageKey;

    PvpZone(String messageKey) {
        this.messageKey = messageKey;
    }

    /** Why the player was refused, in terms they can act on. */
    public String messageKey() {
        return messageKey;
    }

    /** Resolves a name from {@code combat.yml}, however the operator capitalised it. */
    public static Optional<PvpZone> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PvpZone zone : values()) {
            if (zone.name().equals(wanted)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }
}
