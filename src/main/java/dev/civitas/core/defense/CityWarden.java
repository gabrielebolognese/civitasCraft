package dev.civitas.core.defense;

import java.util.Objects;
import java.util.Optional;

/**
 * The City Warden, SPEC 28. Every rule, and no Bukkit.
 *
 * <p>SPEC 28.1 is unusually clear about what this unit is for: "The Warden exists to be the thing
 * your city is known for. It is a prestige unlock and a landmark, not a weapon." Everything below
 * follows from that sentence, and from the asymmetry SPEC 28.4 calls "the entire design" — full
 * vanilla health, drastically reduced damage, so a trespasser in no armour dies in two hits and a
 * geared raider is barely scratched by a 500 HP obstacle standing between them and the City Hall.
 *
 * <h2>Why the rule is a class of its own</h2>
 *
 * <p>{@code org.bukkit.entity.Warden} is not implemented by MockBukkit, and neither is
 * {@code setPose}, {@code setRemoveWhenFarAway} or {@link io.papermc.paper.event.entity
 * .WardenAngerChangeEvent}. An unimplemented Bukkit method is recorded by JUnit as a <em>skip</em>
 * rather than a failure, so a suite in which none of SPEC 28's preconditions was ever checked
 * prints green. Every decision that matters therefore lives here, where it runs with no server in
 * the room, and {@link WardenSuppression} is only the part that applies it.
 */
public final class CityWarden {

    /**
     * The catalogue key the Warden's {@code defense_units} row carries.
     *
     * <p>SPEC 30.3 puts the {@code warden:} block at the top level of {@code defense.yml}, a
     * sibling of {@code units:}, so the Warden is not a shop line — but it is still a placed unit,
     * and {@link UnitMaterializer}, {@link DefenseLeash}, the upkeep sweep and the death path all
     * key off a catalogue type. {@link DefenseCatalogue} therefore parses the {@code warden:}
     * block into a type under this key, findable by {@code byKey} and absent from {@code all()}.
     * One row shape, one materialisation path, and nothing in the shop.
     */
    public static final String TYPE_KEY = "city_warden";

    private CityWarden() {
    }

    // ==================================================================================
    // What a city owns
    // ==================================================================================

    /**
     * A city's Warden.
     *
     * @param unitId          the {@code defense_units} row it stands as
     * @param recoveringUntil SPEC 28.6's peacetime recovery deadline, or null when it is present.
     *                        A timestamp rather than a scheduled task, because SPEC 30.2 case 98
     *                        forbids recovery being accelerated and a task cannot survive a crash
     */
    public record Owned(int cityId, int unitId, long purchasedAt, Long recoveringUntil) {

        public Owned {
            if (cityId <= 0) {
                throw new IllegalArgumentException("cityId must be positive");
            }
        }

        /** SPEC 28.7's fifth state: killed in peacetime, absent for six hours. */
        public boolean isRecovering(long now) {
            return recoveringUntil != null && now < recoveringUntil;
        }

        /** How long until it comes back, or empty if it is already here. */
        public Optional<Long> recoveryRemaining(long now) {
            return isRecovering(now)
                    ? Optional.of(recoveringUntil - now)
                    : Optional.empty();
        }

        /** The same Warden, driven underground. */
        public Owned recoveringUntil(long deadline) {
            return new Owned(cityId, unitId, purchasedAt, deadline);
        }

        /** The same Warden, back on the surface. */
        public Owned recovered() {
            return new Owned(cityId, unitId, purchasedAt, null);
        }
    }

    // ==================================================================================
    // SPEC 28.2, acquisition
    // ==================================================================================

    /** Why a purchase was refused, in the order SPEC 28.2 states the requirements. */
    public enum Refusal {

        /** {@code warden.enabled} is off on this server. */
        DISABLED("warden.disabled"),

        /** SPEC 28.2: "One per city, permanently. Never two, even if the first is destroyed." */
        ALREADY_OWNED("warden.already-owned"),

        /** SPEC 28.2: Fortification level 5, roughly 2,000,000 C of prior investment. */
        NEEDS_FORTIFICATION("warden.needs-fortification"),

        /** SPEC 28.2: "Must be placed in the core chunk. Cannot be moved afterwards." */
        NOT_CORE_CHUNK("warden.not-core-chunk");

        private final String messageKey;

        Refusal(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Whether this city may buy a Warden, and why not.
     *
     * <p>SPEC 30.2 case 100 is the reason this is asked <b>only</b> here and nowhere else: "A city
     * that reaches Fortification 5, buys a Warden, then is downgraded by an admin keeps it.
     * Downgrades do not retroactively remove purchased units." A defensive re-check at
     * materialisation time would read as good hygiene and would silently violate that case.
     *
     * @param owned    whether a Warden already stands, or is recovering, for this city
     * @param required SPEC 28.2's Fortification gate
     */
    public static Optional<Refusal> checkPurchase(boolean enabled, boolean owned,
                                                  int fortificationLevel, int required) {
        if (!enabled) {
            return Optional.of(Refusal.DISABLED);
        }
        if (owned) {
            return Optional.of(Refusal.ALREADY_OWNED);
        }
        if (fortificationLevel < required) {
            return Optional.of(Refusal.NEEDS_FORTIFICATION);
        }
        return Optional.empty();
    }

    /**
     * SPEC 28.2's placement rule: the core chunk, and nowhere else.
     *
     * <p>Bought and placed in one action rather than through SPEC 27.8's spawn item, and that is a
     * deliberate departure from every other unit. There is exactly one legal square metre for this
     * thing, so an item that could be carried away, dropped in lava or placed in the wrong chunk
     * adds no decision at all and adds one way to lose 750,000 C. The buyer stands in the core
     * chunk, which is the same requirement stated from the other end.
     */
    public static Optional<Refusal> checkPlacement(String coreWorld, int coreChunkX,
                                                   int coreChunkZ, String world,
                                                   int chunkX, int chunkZ) {
        boolean inCore = Objects.equals(coreWorld, world)
                && coreChunkX == chunkX && coreChunkZ == chunkZ;
        return inCore ? Optional.empty() : Optional.of(Refusal.NOT_CORE_CHUNK);
    }

    // ==================================================================================
    // SPEC 28.6, dying
    // ==================================================================================

    /**
     * Whether a killing blow actually kills it.
     *
     * <p>SPEC 28.6: "Outside a war, the City Warden cannot be permanently killed... Inside a war,
     * it dies permanently like every other unit, and must be repurchased at full price."
     *
     * <p>The reasoning SPEC gives is worth keeping next to the branch, because the rule looks
     * arbitrary without it: "a 2.75 million coin asset must not be removable by a single griefer
     * outside the sanctioned combat window". Making it merely drivable underground preserves the
     * achievement of beating it without letting a stranger delete a month of a city's investment.
     *
     * <p>ACTIVE only, matching {@code DefenseService.isCityAtWar}. SPEC 28.6 says "inside a war"
     * and SPEC 30.2 case 97 says "on the final day of a war", but SPEC 11.5 permits no grief
     * during PREP and SPEC 26.3 keeps units PASSIVE through it — a Warden that could be deleted
     * for good during a phase in which nothing else may be broken would be the one exception.
     */
    public static boolean diesPermanently(boolean cityInActiveWar) {
        return cityInActiveWar;
    }

    /** SPEC 28.6's six hours, as a deadline rather than a duration. */
    public static long recoveryEndsAt(long now, long recoveryHours) {
        return now + Math.max(0, recoveryHours) * 3_600_000L;
    }

    // ==================================================================================
    // SPEC 28.3, confinement
    // ==================================================================================

    /**
     * How far past the core chunk a position is, in blocks, or zero if it is inside.
     *
     * <p>SPEC 28.3: "Confined to the core chunk plus 6 blocks", against "free roaming" in vanilla,
     * and SPEC's own reason: "It guards the City Hall, it does not patrol the city."
     *
     * <p>{@link DefenseLeash} cannot answer this. Its measure is to the chunk a unit was
     * <em>placed</em> in, which is right for a guard and right here by coincidence — but its
     * caller reads {@code behaviour.leash-distance-blocks}, which is 8, and the Warden's is 6.
     * A separate measure keeps SPEC 28.3's number from being quietly overwritten by SPEC 27.8's.
     *
     * <p>Chebyshev distance in chunks, converted to blocks, which is the same approximation
     * {@code DefenseLeash.blocksOutsidePost} makes and for the same reason: against a leash of six
     * blocks it decides only whether the Warden turns at the chunk line or a few blocks past it.
     */
    public static double blocksOutsideCore(int coreChunkX, int coreChunkZ, int blockX, int blockZ) {
        int chunks = Math.max(Math.abs((blockX >> 4) - coreChunkX),
                Math.abs((blockZ >> 4) - coreChunkZ));
        if (chunks == 0) {
            return 0;
        }
        return (chunks - 1) * 16.0 + edgeOffset(blockX, blockZ);
    }

    /** How far into its own chunk a position sits, so one hugging the line reads low. */
    private static double edgeOffset(int blockX, int blockZ) {
        int withinX = Math.floorMod(blockX, 16);
        int withinZ = Math.floorMod(blockZ, 16);
        return Math.min(Math.min(withinX, 15 - withinX), Math.min(withinZ, 15 - withinZ));
    }

    /** Whether the Warden has strayed and must be put back. */
    public static boolean outsideConfinement(int coreChunkX, int coreChunkZ, int blockX,
                                             int blockZ, double leashBlocks) {
        return blocksOutsideCore(coreChunkX, coreChunkZ, blockX, blockZ) > Math.max(0, leashBlocks);
    }

    /**
     * Whether a trespasser is close enough to be blinded, SPEC 28.3's ten blocks.
     *
     * <p>Applied by the plugin to one named player rather than by the mob's own aura, which SPEC
     * 28.8 requires removed: a twenty-block aura that fires on anyone would blind a contest voter
     * walking past, and SPEC 25.2 Rule 2 makes peacetime tourism a shipping constraint.
     */
    public static boolean withinDarkness(double distance, double radius) {
        return distance >= 0 && distance <= Math.max(0, radius);
    }
}
