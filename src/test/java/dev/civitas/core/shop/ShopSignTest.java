package dev.civitas.core.shop;

import static dev.civitas.core.city.CityTestSupport.reasonOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.util.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reading the four lines of a shop sign, SPEC 4.5. */
class ShopSignTest {

    @TempDir
    Path directory;

    private ConfigManager configs;
    private ShopSign signs;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("shop-sign-test");
        quiet.setUseParentHandlers(false);
        quiet.setLevel(Level.OFF);

        configs = new ConfigManager(PluginResources.ofClasspath(directory.toFile(), quiet));
        configs.loadAll();
        signs = new ShopSign(configs);
    }

    private ShopTerms parsed(String quantity, String offer) {
        Result<ShopTerms> result = signs.parse(quantity, offer);
        assertTrue(result.isSuccess(), offer + ": " + reasonOf(result));
        return result.orElseThrow();
    }

    // ==================================================================================
    // The header
    // ==================================================================================

    @Test
    @DisplayName("the header is recognised however it is capitalised")
    void headerIsCaseInsensitive() {
        assertTrue(signs.isHeader("[Shop]"));
        assertTrue(signs.isHeader("[shop]"));
        assertTrue(signs.isHeader("  [SHOP]  "));
        assertFalse(signs.isHeader("Shop"));
        assertFalse(signs.isHeader("[Shops]"));
        assertFalse(signs.isHeader(null));
    }

    @Test
    @DisplayName("the header is a config key, so a server may use its own word")
    void headerIsConfigurable() {
        configs.get(ConfigFile.ECONOMY).set("player-shops.sign-header", "[Mercato]");

        assertTrue(signs.isHeader("[Mercato]"));
        assertFalse(signs.isHeader("[Shop]"));
    }

    // ==================================================================================
    // The offer line
    // ==================================================================================

    @Test
    @DisplayName("B alone makes a shop that only sells to customers")
    void buyOnly() {
        ShopTerms terms = parsed("16", "B 100");

        assertEquals(16, terms.quantity());
        assertTrue(terms.sellsToCustomers());
        assertFalse(terms.buysFromCustomers());
        assertEquals(0, new BigDecimal("100").compareTo(terms.customerPays()));
    }

    @Test
    @DisplayName("S alone makes a shop that only buys from customers")
    void sellOnly() {
        ShopTerms terms = parsed("64", "S 60");

        assertFalse(terms.sellsToCustomers());
        assertTrue(terms.buysFromCustomers());
        assertEquals(0, new BigDecimal("60").compareTo(terms.customerGets()));
    }

    @Test
    @DisplayName("both halves on one line, in either order")
    void both() {
        ShopTerms forward = parsed("32", "B 100 : S 60");
        ShopTerms reversed = parsed("32", "S 60 : B 100");

        assertEquals(forward, reversed, "the order a player writes them in cannot matter");
        assertEquals(0, new BigDecimal("100").compareTo(forward.customerPays()));
        assertEquals(0, new BigDecimal("60").compareTo(forward.customerGets()));
    }

    @ParameterizedTest(name = "\"{0}\" parses")
    @ValueSource(strings = {
            "B100", "b 100", "B  100", " B 100 ", "B 100:S 60", "B 100 ; S 60",
            "B 100 / S 60", "B 100 | S 60", "B 99.50", "S 0.01"})
    @DisplayName("the shapes a player will actually type all parse")
    void tolerantOfSpacing(String offer) {
        assertTrue(signs.parse("1", offer).isSuccess(), offer);
    }

    @ParameterizedTest(name = "\"{0}\" is refused")
    @ValueSource(strings = {
            "",                 // nothing offered
            "100",              // no B or S
            "X 100",            // not a kind
            "B",                // no price
            "B -100",           // negative
            "B 1e9",            // scientific notation
            "B 100 : B 50",     // the same side twice
            "B 100 : S",        // half a second offer
            "buy 100"})
    @DisplayName("anything else is refused rather than half-read")
    void badOffers(String offer) {
        assertTrue(signs.parse("1", offer).isFailure(), offer);
    }

    @Test
    @DisplayName("a shop that buys higher than it sells is refused, because it is a money pump")
    void invertedPricesAreRefused() {
        // Anyone could sell 16 wheat for 100, buy the same 16 back for 60, and repeat until
        // the owner's balance is gone.
        Result<ShopTerms> result = signs.parse("16", "B 60 : S 100");

        assertEquals("OFFER_INVERTED", reasonOf(result));
        assertEquals("60.00", ((Result.Failure<ShopTerms>) result).placeholders().get("buy"),
                "prices are already at the currency scale by the time they are reported");
    }

    @Test
    @DisplayName("equal prices are allowed: a shop may break even on purpose")
    void equalPricesAreFine() {
        assertTrue(signs.parse("16", "B 100 : S 100").isSuccess());
    }

    // ==================================================================================
    // The quantity line
    // ==================================================================================

    @ParameterizedTest(name = "quantity \"{0}\" is refused")
    @ValueSource(strings = {"", "0", "-1", "abc", "1.5", "1e3"})
    @DisplayName("the quantity must be a plain positive whole number")
    void badQuantities(String quantity) {
        assertTrue(signs.parse(quantity, "B 100").isFailure(), quantity);
    }

    @Test
    @DisplayName("a quantity nobody could carry is refused, with the limit named")
    void quantityIsCapped() {
        Result<ShopTerms> result = signs.parse("999999", "B 100");

        assertEquals("QUANTITY_TOO_LARGE", reasonOf(result));
        assertEquals("2304", ((Result.Failure<ShopTerms>) result).placeholders().get("max"));
    }

    // ==================================================================================
    // Unit prices, for showing next to the market price
    // ==================================================================================

    @Test
    @DisplayName("unit prices are floored, so a shop is never quoted cheaper than it is")
    void unitPrices() {
        ShopTerms terms = parsed("64", "B 100 : S 60");

        assertEquals(0, new BigDecimal("1.56").compareTo(terms.unitBuyPrice().orElseThrow()));
        assertEquals(0, new BigDecimal("0.93").compareTo(terms.unitSellPrice().orElseThrow()));
    }

    @Test
    @DisplayName("a shop that does neither cannot be built")
    void mustDoSomething() {
        try {
            new ShopTerms(16, null, null);
            org.junit.jupiter.api.Assertions.fail("a shop that neither buys nor sells is not a shop");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("buy"));
        }
    }
}
