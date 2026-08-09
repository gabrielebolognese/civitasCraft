package dev.civitas.core.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The colour SPEC asks for and never defines.
 *
 * <p>SPEC 26.2 has units "glow in the city colour" and SPEC 27 dresses the roster in "dyed
 * leather in the city's colour", but no section defines one and no column holds one. This is
 * therefore an invention, and what these tests protect is the only property that makes an
 * invented colour safe to build on: that it never changes under a city.
 */
class CityColourTest {

    @Test
    @DisplayName("a city's colour never changes, because a restart must not repaint its guards")
    void stableAcrossCalls() {
        for (int id = 1; id <= 200; id++) {
            assertEquals(CityColour.of(id), CityColour.of(id),
                    "the derivation must depend on the id and on nothing else");
        }
    }

    @Test
    @DisplayName("every city resolves to a real colour, including id 0 and negatives")
    void everyCityHasOne() {
        for (int id = -50; id <= 500; id++) {
            assertNotNull(CityColour.of(id), "no city may be left without a colour, id " + id);
        }
    }

    @Test
    @DisplayName("consecutive ids get different colours, because neighbours are founded in a row")
    void neighboursDiffer() {
        for (int id = 1; id < 200; id++) {
            assertNotEquals(CityColour.of(id), CityColour.of(id + 1),
                    "cities founded one after another are the ones most likely to border "
                            + "each other, and to be confused; id " + id);
        }
    }

    @Test
    @DisplayName("the wheel is used in full, so no colour is wasted")
    void everyColourIsReachable() {
        Set<NamedTextColor> seen = new HashSet<>();
        for (int id = 0; id < CityColour.distinctColours(); id++) {
            seen.add(CityColour.of(id));
        }
        assertEquals(CityColour.distinctColours(), seen.size(),
                "the stride must be coprime with the wheel, or some colours never appear");
    }

    @Test
    @DisplayName("black is not a city colour, because a black outline at night is not visible")
    void blackIsExcluded() {
        for (int id = 0; id < 500; id++) {
            assertNotEquals(NamedTextColor.BLACK, CityColour.of(id));
        }
    }

    @Test
    @DisplayName("the team name comes from the id, so a rename does not orphan it")
    void teamNameIsFromTheId() {
        assertEquals(CityColour.teamName(7), CityColour.teamName(7));
        assertNotEquals(CityColour.teamName(7), CityColour.teamName(8));
        assertTrue(CityColour.teamName(7).contains("7"));
    }
}
