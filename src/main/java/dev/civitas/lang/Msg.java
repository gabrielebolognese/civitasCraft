package dev.civitas.lang;

/**
 * Every message key used by the plugin.
 *
 * <p>Java code references a constant here, never a literal key and never the text itself.
 * {@code LangKeysTest} asserts that each constant resolves in every shipped language file,
 * so a typo or an untranslated key fails the build rather than reaching a player.
 */
public final class Msg {

    private Msg() {
    }

    public static final String PREFIX = "prefix";

    public static final String GENERAL_MISSING_MESSAGE = "general.missing-message";

    public static final String PLUGIN_ENABLED = "plugin.enabled";
    public static final String PLUGIN_DISABLED = "plugin.disabled";
    public static final String PLUGIN_RELOADED = "plugin.reloaded";

    public static final String COMMAND_NOT_IMPLEMENTED = "command.not-implemented";
    public static final String COMMAND_PLAYER_ONLY = "command.player-only";
    public static final String COMMAND_NO_PERMISSION = "command.no-permission";

    /** Every key above, for the completeness test. Keep in sync when adding a constant. */
    public static final java.util.List<String> ALL = java.util.List.of(
            PREFIX,
            GENERAL_MISSING_MESSAGE,
            PLUGIN_ENABLED,
            PLUGIN_DISABLED,
            PLUGIN_RELOADED,
            COMMAND_NOT_IMPLEMENTED,
            COMMAND_PLAYER_ONLY,
            COMMAND_NO_PERMISSION);
}
