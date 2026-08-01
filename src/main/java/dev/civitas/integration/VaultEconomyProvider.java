package dev.civitas.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.core.economy.EconomyService;
import dev.civitas.core.economy.Money;
import dev.civitas.core.economy.TransactionType;
import dev.civitas.util.Result;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * An optional Vault economy provider, SPEC 20 decision 7.
 *
 * <p>Registered only when Vault is installed and {@code economy.vault.enabled} is on, so a
 * server already running another economy plugin is not hijacked.
 *
 * <p><strong>Vault's API is synchronous and this plugin's is not.</strong> Every Vault method
 * must return an answer immediately, but SPEC 2.1 forbids storage on the server thread.
 * Reads are therefore served from the balance cache, which is exactly what it is for.
 * Writes are dispatched asynchronously and reported as successful, because the alternative
 * is blocking the server thread on a database round trip inside another plugin's call. That
 * makes the plugin a slightly optimistic Vault citizen and a well-behaved Minecraft one,
 * which is the right way round; it is documented here because a shopkeeper plugin relying on
 * a synchronous failure would be surprised.
 */
@SuppressWarnings("deprecation") // Vault's interface still requires the name-based methods.
public final class VaultEconomyProvider implements Economy {


    private final Plugin plugin;
    private final EconomyService economy;
    private final ConfigManager configs;

    private VaultEconomyProvider(Plugin plugin, EconomyService economy, ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /**
     * Registers as Vault's economy provider, if the operator wants it.
     *
     * <p>The caller must have established that Vault is installed. This class implements a
     * Vault interface, so loading it at all on a server without Vault fails.
     *
     * @return true if registered
     */
    public static boolean register(Plugin plugin, EconomyService economy, ConfigManager configs) {
        if (!configs.get(ConfigFile.ECONOMY).getBoolean("vault.enabled", true)) {
            return false;
        }
        plugin.getServer().getServicesManager().register(Economy.class,
                new VaultEconomyProvider(plugin, economy, configs), plugin,
                ServicePriority.Normal);
        return true;
    }

    // ==================================================================================
    // Identity
    // ==================================================================================

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return plugin.getName();
    }

    @Override
    public int fractionalDigits() {
        return dev.civitas.storage.SqlDialect.MONEY_SCALE;
    }

    @Override
    public String format(double amount) {
        return Money.format(BigDecimal.valueOf(amount), configs);
    }

    @Override
    public String currencyNamePlural() {
        return Money.symbol(configs);
    }

    @Override
    public String currencyNameSingular() {
        return Money.symbol(configs);
    }

    // ==================================================================================
    // Balances, served from the cache
    // ==================================================================================

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return economy.cachedBalance(player.getUniqueId()).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return economy.balanceOrZero(player.getUniqueId()).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return economy.balanceOrZero(player.getUniqueId())
                .compareTo(BigDecimal.valueOf(amount)) >= 0;
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        BigDecimal charge = Money.floor(BigDecimal.valueOf(amount));
        UUID uuid = player.getUniqueId();

        if (charge.signum() <= 0) {
            return failure(uuid, "Amount must be positive");
        }
        if (!has(player, charge.doubleValue())) {
            return failure(uuid, "Insufficient funds");
        }

        economy.take(uuid, charge, TransactionType.PLAYER_PAY, null,
                "{\"via\":\"vault\"}").exceptionally(error -> {
                    plugin.getLogger().warning("Vault withdrawal failed for " + uuid + ": " + error);
                    return null;
                });

        // Reported against the cached balance minus the charge; the write is in flight.
        return new EconomyResponse(charge.doubleValue(),
                economy.balanceOrZero(uuid).subtract(charge).doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        BigDecimal credit = Money.floor(BigDecimal.valueOf(amount));
        UUID uuid = player.getUniqueId();

        if (credit.signum() <= 0) {
            return failure(uuid, "Amount must be positive");
        }

        economy.give(uuid, credit, TransactionType.PLAYER_PAY, null,
                "{\"via\":\"vault\"}").exceptionally(error -> {
                    plugin.getLogger().warning("Vault deposit failed for " + uuid + ": " + error);
                    return null;
                });

        return new EconomyResponse(credit.doubleValue(),
                economy.balanceOrZero(uuid).add(credit).doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        // Accounts are created when a player first joins; Vault cannot make one early.
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return createPlayerAccount(player);
    }

    // ==================================================================================
    // Banks, which this plugin does not offer
    // ==================================================================================

    /**
     * Vault's bank API is unimplemented on purpose.
     *
     * <p>SPEC has one shared pot per city, the treasury, and it is reached through
     * {@code /city deposit} with the SPEC 8.5 withdrawal cap attached. Exposing it as a Vault
     * bank would hand any plugin a way around that cap.
     */
    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankUnsupported();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    // ==================================================================================
    // Deprecated name-based methods, kept because the interface demands them
    // ==================================================================================

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName) != null && hasAccount(resolve(playerName));
    }

    @Override
    public boolean hasAccount(String playerName, String world) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = resolve(playerName);
        return player == null ? 0.0 : getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(String playerName, double amount) {
        OfflinePlayer player = resolve(playerName);
        return player != null && has(player, amount);
    }

    @Override
    public boolean has(String playerName, String world, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = resolve(playerName);
        return player == null ? unknownPlayer() : withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String world, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = resolve(playerName);
        return player == null ? unknownPlayer() : depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String world, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer player = resolve(playerName);
        return player != null && createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String world) {
        return createPlayerAccount(playerName);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return bankUnsupported();
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private OfflinePlayer resolve(String playerName) {
        return playerName == null ? null : plugin.getServer().getOfflinePlayer(playerName);
    }

    private EconomyResponse failure(UUID player, String reason) {
        return new EconomyResponse(0.0, economy.balanceOrZero(player).doubleValue(),
                EconomyResponse.ResponseType.FAILURE, reason);
    }

    private static EconomyResponse unknownPlayer() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE,
                "Unknown player");
    }

    private static EconomyResponse bankUnsupported() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "CivitasCraft has city treasuries rather than Vault banks");
    }
}
