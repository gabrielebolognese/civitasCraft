package dev.civitas.config;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

/**
 * The three things config and language loading actually need from a plugin: somewhere to
 * write files, a way to read the packaged defaults, and somewhere to complain.
 *
 * <p>Narrowing the dependency to this rather than {@link Plugin} means the config and
 * language layers can be exercised with nothing but a temporary directory and the classpath,
 * so the tests that assert SPEC defaults do not need a running server to prove it.
 */
public interface PluginResources {

    /** Where the operator's editable copies live. */
    File dataFolder();

    /**
     * Opens a packaged default.
     *
     * @param path a jar-relative path such as {@code lang/en.yml}
     * @return the stream, or null if the jar has no such resource
     */
    InputStream resource(String path);

    Logger logger();

    /** The real thing. */
    static PluginResources of(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new PluginResources() {
            @Override
            public File dataFolder() {
                return plugin.getDataFolder();
            }

            @Override
            public InputStream resource(String path) {
                return plugin.getResource(path);
            }

            @Override
            public Logger logger() {
                return plugin.getLogger();
            }
        };
    }

    /** Reads packaged defaults straight off the classpath. For tests and for tooling. */
    static PluginResources ofClasspath(File dataFolder, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(logger, "logger");
        return new PluginResources() {
            @Override
            public File dataFolder() {
                return dataFolder;
            }

            @Override
            public InputStream resource(String path) {
                return PluginResources.class.getClassLoader().getResourceAsStream(path);
            }

            @Override
            public Logger logger() {
                return logger;
            }
        };
    }
}
