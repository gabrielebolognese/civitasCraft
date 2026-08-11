package dev.civitas.integration;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.CivitasServices;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SPEC 36.6's bStats integration.
 *
 * <p>"Standard anonymous plugin metrics, disableable." Disabling is bStats' own opt-out, in
 * {@code plugins/bStats/config.yml}, which is a server-wide switch an operator already knows
 * about — a second switch here would let this plugin report while the operator believed they had
 * turned metrics off.
 *
 * <p>Every chart is a shape rather than an identity: how many cities, how big they are, which
 * storage backend. Nothing here reports a player, a city name or a location, which is what
 * "anonymous" has to mean for a plugin that knows where everybody lives.
 */
public final class MetricsHook {

    /** The plugin's id on bstats.org. Registered before the first release. */
    private static final int PLUGIN_ID = 27_401;

    private MetricsHook() {
    }

    public static void register(JavaPlugin plugin, Supplier<CivitasServices> services,
                                Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        try {
            Metrics metrics = new Metrics(plugin, PLUGIN_ID);

            metrics.addCustomChart(new SingleLineChart("cities", () ->
                    services.get() == null ? 0 : services.get().registry().cities().size()));
            metrics.addCustomChart(new SingleLineChart("claims", () ->
                    services.get() == null ? 0 : services.get().claimRegistry().allClaims().size()));
            metrics.addCustomChart(new SimplePie("storage_backend", () ->
                    services.get() == null ? "unknown"
                            : plugin.getConfig().getString("storage.type", "SQLITE")));
            metrics.addCustomChart(new SimplePie("language", () ->
                    plugin.getConfig().getString("language", "en")));
        } catch (RuntimeException | NoClassDefFoundError e) {
            // Metrics are never worth failing a startup over, and a server that shaded something
            // odd should still get a plugin.
            logger.log(Level.FINE, "bStats could not start; continuing without metrics.", e);
        }
    }
}
