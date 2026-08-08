package dev.civitas.core.travel;

/**
 * The destinations SPEC 32.7 tabulates, each with its own cost, cooldown and warmup.
 *
 * <p>Per destination rather than one global cooldown, because SPEC 32.7 gives them different
 * numbers on purpose: {@code /spawn} is a minute and {@code /rtp} is five, since one takes a
 * player out of trouble and the other is how they find land.
 */
public enum TravelKind {

    /** The server hub, SPEC 32.7. Free, 60s cooldown, 5s warmup. */
    SPAWN("spawn"),

    /** SPEC 32.4's random teleport in the main overworld. 500 C, 5 min, 5s. */
    RTP("rtp"),

    /** The same into a resource world. Free, 2 min, 5s, and a wider radius. */
    RTP_RESOURCE("rtp-resource"),

    /** An admin-defined public warp, SPEC 32.7. Free, 30s, 5s. */
    WARP("warp");

    private final String key;

    TravelKind(String key) {
        this.key = key;
    }

    /** The {@code world.yml} section holding this destination's numbers. */
    public String configPath() {
        return "travel." + key;
    }

    /** The {@code lang/} key for a cooldown refusal that names the destination. */
    public String messageKey() {
        return "travel.kind-" + key;
    }

    public String key() {
        return key;
    }
}
