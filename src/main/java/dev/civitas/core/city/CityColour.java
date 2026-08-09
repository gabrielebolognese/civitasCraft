package dev.civitas.core.city;

import java.util.List;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * A colour for a city, derived from its id.
 *
 * <h2>This is an invention, not a reading</h2>
 *
 * <p>SPEC 26.2 says units "glow in the city colour" and SPEC 27 says the roster wears "dyed
 * leather in the city's colour", but <b>no section of SPEC defines a city colour and no column
 * holds one</b>. SPEC 3.2's {@code cities} table has a name, a tag, a display name and a motd,
 * and nothing that is a colour; the nearest thing anywhere is SPEC 8.10 slot 22's "city banner",
 * which is not implemented, is not a chat colour, and is used for the map and for contests.
 *
 * <p>The two ways to fill the gap are to let a city choose one, which is a feature SPEC never
 * asked for and would need a column, a command, a GUI and a migration, or to derive one. This
 * derives one. It is the conservative option because it adds nothing a player has to learn and
 * nothing that can be got wrong, and a city that later wants to pick its own colour can be given
 * a column that falls back to this.
 *
 * <h2>What "stable" has to mean here</h2>
 *
 * <p>The derivation must depend on the city id and on nothing else. A colour that moved with the
 * city's name, its member count or the order rows loaded in would repaint a city's whole garrison
 * on a rename or a restart, and a player who has learnt that the blue guards are Roma's would
 * have learnt something untrue.
 */
public final class CityColour {

    /**
     * The colours a city can be.
     *
     * <p>Fifteen rather than sixteen: {@link NamedTextColor#BLACK} is dropped because the only
     * thing this colour is used for is a glowing outline, and a black outline at night is not a
     * colour so much as an absence of one. Every other named colour reads clearly against both
     * terrain and sky.
     */
    private static final List<NamedTextColor> WHEEL = List.of(
            NamedTextColor.DARK_BLUE,
            NamedTextColor.DARK_GREEN,
            NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED,
            NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD,
            NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY,
            NamedTextColor.BLUE,
            NamedTextColor.GREEN,
            NamedTextColor.AQUA,
            NamedTextColor.RED,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.YELLOW,
            NamedTextColor.WHITE);

    /**
     * Coprime with the wheel's length, so ids 1 to 15 land on fifteen different colours.
     *
     * <p>A stride rather than a hash: cities are founded with consecutive ids, so the ones most
     * likely to border each other are the ones most likely to be confused, and stepping seven
     * places round the wheel puts neighbouring ids as far apart as the wheel allows.
     */
    private static final int STRIDE = 7;

    private CityColour() {
    }

    /** The colour of a city, for as long as that city exists. */
    public static NamedTextColor of(int cityId) {
        return WHEEL.get(Math.floorMod(cityId * STRIDE, WHEEL.size()));
    }

    /**
     * The scoreboard team that carries a city's colour.
     *
     * <p>A glow is rendered in the entity's team colour and in no other way, so a team per city
     * is what makes SPEC 26.2's "glow in the city colour" possible at all without a resource
     * pack. Named from the id rather than the name so a rename does not orphan a team.
     */
    public static String teamName(int cityId) {
        return "civitas_city_" + cityId;
    }

    /** How many distinct colours exist, so two cities in fifteen share one. */
    public static int distinctColours() {
        return WHEEL.size();
    }
}
