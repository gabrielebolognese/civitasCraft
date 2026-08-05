package dev.civitas.core.diplomacy;

import java.util.Locale;
import java.util.Optional;

/**
 * What one city is to another, SPEC 14.1.
 *
 * <p>Exactly one of these holds for any pair at any moment. The order below is the order they
 * take precedence in when two could apply: a war outranks a truce, and a truce outranks the
 * alliance that is currently in its notice period.
 */
public enum Relation {

    /** The default. Normal protection, and war may be declared. */
    NEUTRAL,

    /** Mutual and formal. Cannot be declared upon, may join each other's wars. */
    ALLY,

    /** Time-limited non-aggression. War cannot be declared until it expires. */
    TRUCE,

    /** An active war, SPEC 11. */
    AT_WAR,

    /** A post-war marker. Cosmetic, and decays after 30 days. */
    ENEMY;

    /** The language key for this state's name. */
    public String messageKey() {
        return "diplomacy.relation." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<Relation> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        for (Relation relation : values()) {
            if (relation.name().equals(normalised)) {
                return Optional.of(relation);
            }
        }
        return Optional.empty();
    }
}
