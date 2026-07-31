package dev.civitas;

import dev.civitas.command.CommandRegistry;
import dev.civitas.config.ConfigFile;
import dev.civitas.config.ConfigManager;
import dev.civitas.lang.LangManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point and lifecycle owner.
 *
 * <p>M0 wires up configuration, language and the command tree, and nothing else. Services,
 * storage and listeners are added by later milestones and are constructed here in
 * dependency order.
 */
public final class CivitasPlugin extends JavaPlugin {

    private ConfigManager configs;
    private LangManager lang;

    @Override
    public void onEnable() {
        configs = new ConfigManager(this);
        configs.loadAll();

        lang = new LangManager(this, configs);
        lang.load();

        new CommandRegistry(this, lang).registerAll();

        warnIfRollbackDisabled();

        getLogger().info(() -> "Enabled version " + getPluginMeta().getVersion()
                + ", language " + lang.activeLanguage() + ".");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }

    public ConfigManager configs() {
        return configs;
    }

    public LangManager lang() {
        return lang;
    }

    /**
     * Rollback is the plugin's core promise (SPEC 1.2 and 11.1). Turning it off means war
     * destroys builds permanently, so an operator who does it is told loudly rather than
     * discovering it after the first war.
     */
    private void warnIfRollbackDisabled() {
        if (!configs.get(ConfigFile.WAR).getBoolean("rollback.enabled", true)) {
            getLogger().severe("war.yml has rollback.enabled: false.");
            getLogger().severe("War damage will NOT be restored. This is never correct on a live server.");
        }
    }
}
