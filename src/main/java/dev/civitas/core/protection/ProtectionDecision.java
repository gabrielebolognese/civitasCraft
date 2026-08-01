package dev.civitas.core.protection;

import java.util.Map;

/**
 * The answer to "may this player do this here".
 *
 * <p>Deliberately not a {@code Result}: this is asked several times per tick on a busy
 * server, and the allowed case must allocate nothing. {@link #ALLOWED} is a shared instance,
 * so the common answer costs one field read.
 *
 * @param allowed      whether to let the event proceed
 * @param reason       stable machine-readable code, for logs and tests
 * @param messageKey   what to tell the player, or null when allowed
 * @param placeholders values for that message
 */
public record ProtectionDecision(
        boolean allowed,
        String reason,
        String messageKey,
        Map<String, String> placeholders) {

    /** The answer for wilderness, for members with the flag, and for anyone with bypass. */
    public static final ProtectionDecision ALLOWED =
            new ProtectionDecision(true, "ALLOWED", null, Map.of());

    public static ProtectionDecision deny(String reason, String messageKey) {
        return new ProtectionDecision(false, reason, messageKey, Map.of());
    }

    public static ProtectionDecision deny(String reason, String messageKey,
                                          Map<String, String> placeholders) {
        return new ProtectionDecision(false, reason, messageKey, placeholders);
    }

    public boolean denied() {
        return !allowed;
    }
}
