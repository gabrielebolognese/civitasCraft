package dev.civitas.core.defense;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.core.city.CityColour;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Everything a spawn applies to one unit, worked out with no server in the room.
 *
 * <h2>Why this is a record and not a method on the spawner</h2>
 *
 * <p>{@link DefenseSpawner} has never once executed under test. MockBukkit does not implement
 * {@code setRemoveWhenFarAway}, which SPEC 30.2 case 106 requires and the spawner calls on every
 * spawn, and an unimplemented Bukkit method is recorded by JUnit as a <em>skip</em> rather than
 * a failure — so a suite in which none of SPEC 27.1's stats was ever checked prints green.
 * {@link UnitMaterializer} already carries a {@code useSpawn} seam written for exactly that.
 *
 * <p>So the numbers live here, where they can be asserted against the shipped {@code defense.yml}
 * one row of SPEC 27.1 at a time, and the spawner becomes the part that only applies them.
 * Nothing in this class touches {@code org.bukkit.attribute.Attribute}: its constants resolve
 * through a registry, and a class whose static initialiser throws is poisoned for the life of
 * the JVM — the trap M6b spent a milestone on. Attributes are named by their value here and
 * mapped to constants in the spawner, which does have a server.
 *
 * @param attackDamage        zero for the two units SPEC 27 gives no damage at all
 * @param scale               SPEC 27.7's 1.8x Colossus; 1.0 means leave the attribute alone
 * @param armour              the total, SPEC 27.6. See {@link #stripArmourFromEquipment()}
 * @param invulnerable        SPEC 27.3's Keeper outside a war
 * @param suppressDaylightBurning  SPEC 30.2 case 108, on every materialisation
 * @param suppressReinforcements   SPEC 30.2 case 109
 * @param leatherColour       SPEC 27's "dyed leather in the city's colour", when it wears any
 * @param collarColour        SPEC 27.4's dyed collar
 */
public record UnitShaping(
        EntityType mob,
        double maxHealth,
        double attackDamage,
        double movementSpeed,
        double followRange,
        double scale,
        double armour,
        double armourToughness,
        double knockbackResistance,
        boolean invulnerable,
        boolean suppressDaylightBurning,
        boolean suppressReinforcements,
        Map<DefenseUnitType.EquipmentSlotKey, Material> equipment,
        Map<String, Integer> mainHandEnchantments,
        Optional<Color> leatherColour,
        Optional<DyeColor> collarColour) {

    public UnitShaping {
        Objects.requireNonNull(mob, "mob");
        equipment = Map.copyOf(equipment);
        mainHandEnchantments = Map.copyOf(mainHandEnchantments);
        Objects.requireNonNull(leatherColour, "leatherColour");
        Objects.requireNonNull(collarColour, "collarColour");
    }

    /**
     * How a unit is shaped for a city.
     *
     * @param fortification    the SPEC 5.7 Fortification level, which is the only thing that
     *                         moves a unit's stats after it is bought
     * @param healthBonusPerLevel SPEC 5.7's "+5% defense unit health" per level
     * @param atWar            SPEC 27.3: a Keeper is invulnerable outside one and 40 HP inside
     */
    public static UnitShaping of(DefenseUnitType type, int cityId, int fortification,
                                 double healthBonusPerLevel, boolean atWar) {
        Objects.requireNonNull(type, "type");
        double bonus = 1 + healthBonusPerLevel / 100.0 * Math.max(0, fortification);

        return new UnitShaping(
                type.mob(),
                type.health() * bonus,
                type.damage(),
                type.speed(),
                type.range(),
                type.scale(),
                type.armour(),
                type.armourToughness(),
                type.knockbackResistance(),
                type.invulnerableOutsideWar() && !atWar,
                burnsInDaylight(type.mob()),
                type.mob() == EntityType.ZOMBIE,
                type.equipment(),
                type.mainHandEnchantments(),
                leatherColourFor(type, cityId),
                collarColourFor(type, cityId));
    }

    /**
     * Whether this mob would set itself on fire at sunrise, SPEC 30.2 case 108.
     *
     * <p>"{@code setShouldBurnInDay(false)} on zombies and skeletons, verified on every
     * materialization." Under SPEC 25.4 a unit respawns whenever a player walks past, so a
     * flag applied only at purchase would hold until the first time anybody left.
     */
    private static boolean burnsInDaylight(EntityType mob) {
        return mob == EntityType.ZOMBIE || mob == EntityType.SKELETON;
    }

    /**
     * SPEC 25.3: "Dyed leather in the city's colour is the single highest-value cosmetic here."
     *
     * <p>Only for a unit that wears leather. A Colossus wears nothing and a Warhound's colour
     * is on its collar.
     */
    private static Optional<Color> leatherColourFor(DefenseUnitType type, int cityId) {
        boolean wearsLeather = type.equipment().values().stream()
                .anyMatch(material -> material.name().startsWith("LEATHER_"));
        return wearsLeather
                ? Optional.of(Color.fromRGB(CityColour.of(cityId).value()))
                : Optional.empty();
    }

    /** SPEC 27.4: "Wolf, with a dyed collar in the city colour." */
    private static Optional<DyeColor> collarColourFor(DefenseUnitType type, int cityId) {
        return type.mob() == EntityType.WOLF
                ? Optional.of(dyeFor(CityColour.of(cityId)))
                : Optional.empty();
    }

    /**
     * Whether the worn armour must be stripped of its protection before it is put on.
     *
     * <p>SPEC 25.3 files dyed leather under <b>appearance</b>, beside custom names and team
     * colours, and SPEC 27.6 states the City Guard's armour as a number in the same table that
     * gives it leather. Those two are only consistent if the leather protects nothing: worn
     * armour contributes through attribute modifiers, so a base of 8 plus a full leather set
     * would be 15, and a 90 HP unit at 15 armour is most of the way to the unbeatable garrison
     * SPEC 25.2 Rule 1 exists to forbid.
     *
     * <p>So the leather is cosmetic, {@link #armour()} is the truth, and this says so.
     */
    public boolean stripArmourFromEquipment() {
        return armour > 0;
    }

    /**
     * The dye nearest a city's colour.
     *
     * <p>A wolf collar is a {@link DyeColor} and a city's colour is a chat colour, and there is
     * no conversion between them in the API. Fifteen entries rather than a nearest-RGB search,
     * because the wheel is fixed and a lookup cannot drift.
     */
    private static DyeColor dyeFor(NamedTextColor colour) {
        DyeColor dye = DYES.get(colour);
        return dye == null ? DyeColor.WHITE : dye;
    }

    private static final Map<NamedTextColor, DyeColor> DYES = dyes();

    private static Map<NamedTextColor, DyeColor> dyes() {
        Map<NamedTextColor, DyeColor> map = new LinkedHashMap<>();
        map.put(NamedTextColor.DARK_BLUE, DyeColor.BLUE);
        map.put(NamedTextColor.DARK_GREEN, DyeColor.GREEN);
        map.put(NamedTextColor.DARK_AQUA, DyeColor.CYAN);
        map.put(NamedTextColor.DARK_RED, DyeColor.RED);
        map.put(NamedTextColor.DARK_PURPLE, DyeColor.PURPLE);
        map.put(NamedTextColor.GOLD, DyeColor.ORANGE);
        map.put(NamedTextColor.GRAY, DyeColor.LIGHT_GRAY);
        map.put(NamedTextColor.DARK_GRAY, DyeColor.GRAY);
        map.put(NamedTextColor.BLUE, DyeColor.LIGHT_BLUE);
        map.put(NamedTextColor.GREEN, DyeColor.LIME);
        map.put(NamedTextColor.AQUA, DyeColor.CYAN);
        map.put(NamedTextColor.RED, DyeColor.RED);
        map.put(NamedTextColor.LIGHT_PURPLE, DyeColor.MAGENTA);
        map.put(NamedTextColor.YELLOW, DyeColor.YELLOW);
        map.put(NamedTextColor.WHITE, DyeColor.WHITE);
        return Map.copyOf(map);
    }
}
