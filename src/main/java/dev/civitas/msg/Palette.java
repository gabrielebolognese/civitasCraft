package dev.civitas.msg;

import java.util.LinkedHashMap;
import java.util.Map;

import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * SPEC 23.2's colour palette, as MiniMessage tags.
 *
 * <p>SPEC 23.2: "Defined once as MiniMessage tag resolvers, referenced by semantic name
 * everywhere. <b>Changing the palette must never require touching a message string.</b>"
 *
 * <p>So a message says {@code <pos>+250</pos>}, never {@code <color:#4ADE80>}. The token names
 * what the text <i>means</i> — money coming in, the one thing this message is about, ordinary
 * body text — and the palette decides what that looks like. SPEC 23.1: "Colour has consistent
 * meaning. A player should be able to read the colour before reading the words."
 *
 * <h2>Why this is a resolver and not a set of constants</h2>
 *
 * <p>SPEC 36.5 requires a deuteranopia-safe alternative, because the palette leans on green and
 * red and that is the most common form of colour blindness. A palette that is a resolver can be
 * swapped for another one at render time; a palette that is thirteen constants compiled into
 * messages cannot. The alternative palette itself belongs to the accessibility milestone — this
 * class only has to make it possible.
 *
 * <p>Note also SPEC 36.5's stronger rule, which is not this class's job but which the catalogue
 * must respect: "No message ever conveys meaning by colour alone. Every positive amount carries
 * a {@code +}, every negative carries a {@code -}."
 */
public final class Palette {

    /** One token: the tag name, and the style it paints. */
    private record Token(String name, Style style) {
    }

    private final String id;
    private final Map<String, Style> tokens;
    private final TagResolver resolver;

    private Palette(String id, Map<String, Style> tokens) {
        this.id = id;
        this.tokens = Map.copyOf(tokens);

        TagResolver.Builder builder = TagResolver.builder();
        tokens.forEach((name, style) -> builder.resolver(
                TagResolver.resolver(name, Tag.styling(child -> child.merge(style)))));
        this.resolver = builder.build();
    }

    // ==================================================================================
    // SPEC 23.2's table
    // ==================================================================================

    /** The standard palette, exactly as SPEC 23.2 lists it. */
    public static final Palette STANDARD = new Palette("standard", standardTokens());

    private static Map<String, Style> standardTokens() {
        Map<String, Style> tokens = new LinkedHashMap<>();
        // Money in, success, gain.
        tokens.put("pos", colour(0x4ADE80));
        // Money out, failure, loss.
        tokens.put("neg", colour(0xF87171));
        // Currency amounts, always.
        tokens.put("money", colour(0xFBBF24));
        // The one thing the message is about. Bold as well as white, per SPEC 23.1's "the
        // important number is visually distinct".
        tokens.put("subject", colour(0xFFFFFF).decorate(TextDecoration.BOLD));
        // All ordinary text.
        tokens.put("body", colour(0x9CA3AF));
        // Brackets, separators, secondary detail.
        tokens.put("dim", colour(0x4B5563));
        tokens.put("city", colour(0x38BDF8));
        tokens.put("land", colour(0x4ADE80));
        tokens.put("war", colour(0xDC2626));
        tokens.put("quest", colour(0xC084FC));
        tokens.put("ally", colour(0xFCD34D));
        tokens.put("admin", colour(0xEF4444));
        tokens.put("link", colour(0x60A5FA).decorate(TextDecoration.UNDERLINED));
        return tokens;
    }

    private static Style colour(int rgb) {
        return Style.style(TextColor.color(rgb));
    }

    // ==================================================================================
    // Using it
    // ==================================================================================

    /** The resolver to hand MiniMessage, so every token in the table works in any message. */
    public TagResolver resolver() {
        return resolver;
    }

    /** Which palette this is, for {@code /toggle} and the tests. */
    public String id() {
        return id;
    }

    /** Every token name, so a test can assert the table is complete. */
    public java.util.Set<String> tokenNames() {
        return tokens.keySet();
    }

    /** The style a token paints, for the tests. */
    public Style styleOf(String token) {
        return tokens.get(token);
    }

    /**
     * A palette with the same token names and different styles.
     *
     * <p>The seam SPEC 36.5's colourblind palette will use. Requiring the same names is the
     * point: a variant that dropped a token would render {@code <pos>} as literal text in every
     * message that used it, which is worse than the colour it was trying to fix.
     */
    public Palette variant(String variantId, Map<String, Style> replacements) {
        Map<String, Style> merged = new LinkedHashMap<>(tokens);
        replacements.forEach((name, style) -> {
            if (!merged.containsKey(name)) {
                throw new IllegalArgumentException(
                        "palette variant " + variantId + " defines an unknown token: " + name);
            }
            merged.put(name, style);
        });
        return new Palette(variantId, merged);
    }
}
