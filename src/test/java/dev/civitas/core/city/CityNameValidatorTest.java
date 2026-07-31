package dev.civitas.core.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.util.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC 5.1 preconditions 4 and 5, against the packaged {@code cities.yml}. */
class CityNameValidatorTest {

    @TempDir
    Path directory;

    private CityNameValidator validator;

    @BeforeEach
    void setUp() {
        ConfigManager configs = new ConfigManager(
                PluginResources.ofClasspath(directory.toFile(), CityTestSupport.quietLogger()));
        configs.loadAll();
        validator = new CityNameValidator(configs);
    }

    private static String reason(Result<String> result) {
        return result instanceof Result.Failure<String> failure ? failure.reason() : "SUCCESS";
    }

    @ParameterizedTest
    @ValueSource(strings = {"Roma", "Ostia", "New_Rome", "abc", "A1", "Roma123", "___"})
    @DisplayName("names matching the configured pattern and length are accepted")
    void acceptsValidNames(String name) {
        if (name.length() < 3) {
            return;
        }
        assertTrue(validator.validate(name).isSuccess(), name + " should be valid");
    }

    @Test
    @DisplayName("length is bounded at both ends by the configured limits")
    void lengthBounds() {
        assertEquals("NAME_LENGTH", reason(validator.validate("Ro")));
        assertEquals("SUCCESS", reason(validator.validate("Rom")));
        assertEquals("SUCCESS", reason(validator.validate("A".repeat(24))));
        assertEquals("NAME_LENGTH", reason(validator.validate("A".repeat(25))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Roma!", "Roma Nova", "Roma-Nova", "Roma.Nova", "Rom@", "Roma\n"})
    @DisplayName("anything outside the configured character class is refused")
    void rejectsInvalidCharacters(String name) {
        assertEquals("NAME_PATTERN", reason(validator.validate(name)));
    }

    @Test
    @DisplayName("MiniMessage in a name is refused, so it cannot reach a message unparsed")
    void rejectsMarkup() {
        assertEquals("NAME_PATTERN", reason(validator.validate("<red>Roma")));
        assertEquals("NAME_PATTERN", reason(validator.validate("Roma</red>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Staff", "server", "console", "null", "undefined"})
    @DisplayName("blocked names are refused whatever their capitalisation")
    void rejectsBlockedNames(String name) {
        assertEquals("NAME_BLOCKED", reason(validator.validate(name)));
    }

    @Test
    @DisplayName("an empty or absent name is refused rather than throwing")
    void rejectsEmpty() {
        assertEquals("NAME_EMPTY", reason(validator.validate(null)));
        assertEquals("NAME_EMPTY", reason(validator.validate("")));
        assertEquals("NAME_EMPTY", reason(validator.validate("   ")));
    }

    @Test
    @DisplayName("the length message carries the configured bounds so the player is told them")
    void lengthMessageCarriesBounds() {
        Result.Failure<String> failure = (Result.Failure<String>) validator.validate("Ro");

        assertEquals("3", failure.placeholders().get("min"));
        assertEquals("24", failure.placeholders().get("max"));
    }

    @Test
    @DisplayName("a tag is derived from the name, upper-cased and trimmed to the column width")
    void tagDerivation() {
        assertEquals("ROMA", CityNameValidator.deriveTag("Roma"));
        assertEquals("OSTI", CityNameValidator.deriveTag("Ostia"));
        assertEquals("ABC", CityNameValidator.deriveTag("abc"));
        assertEquals("NEWR", CityNameValidator.deriveTag("New_Rome"));
        assertTrue(CityNameValidator.deriveTag("A".repeat(24)).length() <= 5,
                "a tag must fit the VARCHAR(5) column");
    }
}
