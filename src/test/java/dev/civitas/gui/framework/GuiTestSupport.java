package dev.civitas.gui.framework;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.config.ConfigManager;
import dev.civitas.config.PluginResources;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A menu stack with no plugin behind it, for the SPEC 17.5 hardening tests.
 *
 * <p>The menus here are deliberately minimal and live only in the test sources: M7 ships the
 * framework and no screens, and a screen invented to exercise it would be a screen SPEC 8
 * never asked for.
 */
final class GuiTestSupport {

    private GuiTestSupport() {
    }

    static ConfigManager configs(File directory) {
        ConfigManager configs = new ConfigManager(PluginResources.ofClasspath(directory, quiet()));
        configs.loadAll();
        return configs;
    }

    static LangManager lang(File directory, ConfigManager configs) {
        LangManager lang = new LangManager(PluginResources.ofClasspath(directory, quiet()), configs);
        lang.load();
        return lang;
    }

    static Logger quiet() {
        Logger logger = Logger.getLogger("civitas-gui-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    /** A menu with one button, whose permission and clicks the test drives. */
    static final class TestMenu extends Menu {

        final List<Integer> clicked = new ArrayList<>();
        Predicate<Player> permission = player -> true;
        boolean liveData;
        int builds;

        TestMenu(MenuManager manager, Player viewer) {
            super(manager, viewer);
        }

        TestMenu(MenuManager manager, Player viewer, Menu parent) {
            super(manager, viewer, parent);
        }

        @Override
        protected Component title() {
            return Component.text("Test");
        }

        @Override
        protected boolean live() {
            return liveData;
        }

        @Override
        protected void build() {
            builds++;
            set(22, Button.of(Material.DIAMOND, Component.text("Action"))
                    .requires(player -> permission.test(player), Component.text("Not allowed"))
                    .onClick(context -> clicked.add(context.slot()))
                    .build());
        }
    }

    /** A paginated menu over a list the test controls. */
    static final class TestList extends PaginatedMenu<String> {

        final List<String> items = new ArrayList<>();
        final List<String> clicked = new ArrayList<>();

        TestList(MenuManager manager, Player viewer) {
            super(manager, viewer);
        }

        @Override
        protected Component title() {
            return Component.text("List");
        }

        @Override
        protected List<String> contents() {
            return List.copyOf(items);
        }

        @Override
        protected Button entryButton(String entry, int index) {
            return Button.of(Material.PAPER, Component.text(entry))
                    .onClick(context -> clicked.add(entry))
                    .build();
        }
    }
}
