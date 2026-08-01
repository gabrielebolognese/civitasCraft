package dev.civitas.integration;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.CivitasServices;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityRank;
import dev.civitas.core.economy.Money;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI placeholders, SPEC 20 decision 7.
 *
 * <p>Registered only when PlaceholderAPI is actually installed; the class is never loaded
 * otherwise, which is why {@link #registerIfPresent} does the check rather than a static
 * initialiser here.
 *
 * <p>Every placeholder reads the in-memory caches, because PlaceholderAPI resolves these on
 * the server thread, often several times a second for a scoreboard.
 */
public final class PlaceholderApiHook extends PlaceholderExpansion {

    private final Plugin plugin;
    private final java.util.function.Supplier<CivitasServices> services;
    private final ConfigManager configs;

    private PlaceholderApiHook(Plugin plugin,
                               java.util.function.Supplier<CivitasServices> services,
                               ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.services = Objects.requireNonNull(services, "services");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * Registers the expansion.
     *
     * <p>The caller must have established that PlaceholderAPI is installed. This class
     * extends a PlaceholderAPI type, so loading it at all on a server without PlaceholderAPI
     * fails before any check inside it could run.
     *
     * @return true if it was registered
     */
    public static boolean register(Plugin plugin,
                                   java.util.function.Supplier<CivitasServices> services,
                                   ConfigManager configs) {
        return new PlaceholderApiHook(plugin, services, configs).register();
    }

    @Override
    public String getIdentifier() {
        return "civitas";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // Survives a PlaceholderAPI reload, since this expansion is not loaded from its
        // own jar and would not be found again.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        CivitasServices current = services.get();
        if (current == null || player == null) {
            return "";
        }

        Optional<City> city = current.registry().cityOf(player.getUniqueId());

        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "balance" -> current.economy().balanceOrZero(player.getUniqueId()).toPlainString();
            case "balance_formatted" ->
                    Money.format(current.economy().balanceOrZero(player.getUniqueId()), configs);
            case "city" -> city.map(City::name).orElse("");
            case "city_display" -> city.map(City::displayName).orElse("");
            case "city_tag" -> city.map(City::tag).orElse("");
            case "city_treasury" ->
                    city.map(c -> c.treasury().toPlainString()).orElse("");
            case "city_members" ->
                    city.map(c -> String.valueOf(c.memberCount())).orElse("");
            case "city_claims" -> city
                    .map(c -> String.valueOf(current.claimRegistry().countOf(c.id())))
                    .orElse("");
            case "rank" -> city.flatMap(c -> c.rankOf(player.getUniqueId()))
                    .map(CityRank::name).orElse("");
            case "is_mayor" -> String.valueOf(
                    city.map(c -> c.isMayor(player.getUniqueId())).orElse(false));
            default -> null;
        };
    }
}
