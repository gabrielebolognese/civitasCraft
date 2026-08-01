package dev.civitas.core.protection;

import java.util.Set;

import dev.civitas.core.city.CityPermission;

/**
 * Something a player can attempt inside a claim, and the rank flag that permits it.
 *
 * <p>One enum rather than a method per rule so that every listener asks the same question in
 * the same way, and so the mapping from SPEC 5.5's prose to SPEC 5.4's flags exists in
 * exactly one readable place.
 */
public enum ProtectionAction {

    /** Breaking or placing a block, SPEC 5.5. */
    BUILD(Set.of(CityPermission.BUILD), "protection.denied.build"),

    /**
     * Opening a container.
     *
     * <p>Either flag will do: SPEC 5.4 defines {@code CONTAINER_READONLY} as "open but not
     * remove items", so a Recruit may look inside. Taking is a separate action.
     */
    CONTAINER_OPEN(Set.of(CityPermission.CONTAINER, CityPermission.CONTAINER_READONLY),
            "protection.denied.container"),

    /** Removing an item from a container, which read-only access does not permit. */
    CONTAINER_TAKE(Set.of(CityPermission.CONTAINER), "protection.denied.container-take"),

    /** Doors, buttons, levers, plates, beds, anvils, enchanting tables, SPEC 5.5. */
    INTERACT(Set.of(CityPermission.INTERACT), "protection.denied.interact"),

    /**
     * Filling or emptying a bucket.
     *
     * <p>Gated on BUILD rather than INTERACT because a bucket changes blocks: emptying lava
     * into someone's wooden hall is griefing, not interacting.
     */
    BUCKET(Set.of(CityPermission.BUILD), "protection.denied.bucket"),

    /**
     * Damaging an entity that is not hostile.
     *
     * <p>Gated on BUILD for the same reason: killing a city's breeding stock or shooting
     * down its item frames destroys what the city built.
     */
    ENTITY_DAMAGE(Set.of(CityPermission.BUILD), "protection.denied.entity"),

    /** Walking on farmland, SPEC 5.5's "farmland trampling by non-members". */
    FARMLAND_TRAMPLE(Set.of(CityPermission.BUILD), "protection.denied.trample"),

    /** Trading with a villager, subject to the SPEC 5.5 config toggle. */
    VILLAGER_TRADE(Set.of(CityPermission.INTERACT), "protection.denied.villager"),

    /** Attacking another player inside a claim, SPEC 5.5. Never a rank flag. */
    PVP(Set.of(), "protection.denied.pvp");

    private final Set<CityPermission> anyOf;
    private final String messageKey;

    ProtectionAction(Set<CityPermission> anyOf, String messageKey) {
        this.anyOf = anyOf;
        this.messageKey = messageKey;
    }

    /**
     * The flags that permit this action; holding any one is enough.
     *
     * <p>Empty for {@link #PVP}, which no rank can ever grant: SPEC 5.5 makes it a function
     * of the war state, not of membership.
     */
    public Set<CityPermission> anyOf() {
        return anyOf;
    }

    public String messageKey() {
        return messageKey;
    }

    /** Whether a rank can ever permit this, as opposed to only the war state. */
    public boolean isRankGoverned() {
        return !anyOf.isEmpty();
    }
}
