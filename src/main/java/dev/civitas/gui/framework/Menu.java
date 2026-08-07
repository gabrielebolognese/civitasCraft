package dev.civitas.gui.framework;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.civitas.config.ConfigFile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A screen.
 *
 * <p>Subclasses describe what is on it by filling {@link #build()} with {@link #set} calls,
 * and the base class owns everything else: the inventory, the SPEC 8.2 furniture, the
 * dispatch from a raw slot number to a button, and the rule that a click is only ever
 * executed after the button's own permission test has been asked again.
 *
 * <h2>Snapshot versus live data</h2>
 * SPEC 17.5 case 64 draws the distinction this class is built around: what is <em>drawn</em>
 * is a snapshot, and may be stale by the time a player clicks it. What is <em>done</em> must
 * be validated against live state at the moment of the click. A subclass may therefore read
 * a cached figure while building, but its click handler must not: it re-reads, and the
 * service it calls validates again underneath that.
 */
public abstract class Menu {

    protected final MenuManager manager;
    protected final Player viewer;

    private final Map<Integer, Button> buttons = new HashMap<>();
    private final Menu parent;

    private Inventory inventory;
    private MenuHolder holder;

    protected Menu(MenuManager manager, Player viewer) {
        this(manager, viewer, null);
    }

    protected Menu(MenuManager manager, Player viewer, Menu parent) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.parent = parent;
    }

    // ==================================================================================
    // What a subclass provides
    // ==================================================================================

    /** The window title. Already resolved through the language files by the subclass. */
    protected abstract Component title();

    /** Fills the menu. Called on open and on every refresh; must be safe to run repeatedly. */
    protected abstract void build();

    /**
     * Whether this menu shows data that changes underneath it, SPEC 8.2.
     *
     * <p>A treasury balance or an online-member list does; a static list of permissions does
     * not, and refreshing it would only make the icons flicker for no gain.
     */
    protected boolean live() {
        return false;
    }

    /** Rows times nine. SPEC 8.2 fixes 54 for the menus in Section 8. */
    protected int size() {
        return manager.configs().get(ConfigFile.GUI).getInt("size", 54);
    }

    /** Whether the gray-pane border is drawn. */
    protected boolean bordered() {
        return manager.configs().get(ConfigFile.GUI).getBoolean("border.enabled", true);
    }

    /** Whether the SPEC 8.2 Back and Close buttons are added. */
    protected boolean navigable() {
        return true;
    }

    // ==================================================================================
    // Building
    // ==================================================================================

    /** Places a button. Called from {@link #build()}. */
    protected final void set(int slot, Button button) {
        if (slot < 0 || slot >= size()) {
            throw new IllegalArgumentException(
                    "Slot " + slot + " is outside a " + size() + "-slot menu");
        }
        buttons.put(slot, button);
    }

    protected final void clear() {
        buttons.clear();
    }

    public final Optional<Button> buttonAt(int slot) {
        return Optional.ofNullable(buttons.get(slot));
    }

    public final Optional<Menu> parent() {
        return Optional.ofNullable(parent);
    }

    public final Player viewer() {
        return viewer;
    }

    public final Inventory inventory() {
        return inventory;
    }

    // ==================================================================================
    // Opening, drawing, closing
    // ==================================================================================

    /** Builds the menu and shows it to its viewer. */
    public final void open() {
        // SPEC 9.4.6's "GUI open time". The whole call, not just the draw: what an operator
        // is diagnosing is the pause between the click and the window, and building the
        // inventory and sending it to the client are both inside that.
        long started = manager.timings().start(dev.civitas.util.Timings.Metric.GUI_OPEN);
        try {
            holder = new MenuHolder(this);
            inventory = Bukkit.createInventory(holder, size(), title());
            holder.attach(inventory);

            draw();
            manager.register(this);
            viewer.openInventory(inventory);
        } finally {
            manager.timings().stop(dev.civitas.util.Timings.Metric.GUI_OPEN, started);
        }
    }

    /**
     * Rebuilds and redraws without reopening.
     *
     * <p>Reopening would close and reopen the window, which steals the cursor and makes a
     * live menu unusable; drawing into the same inventory does not.
     */
    public final void refresh() {
        if (inventory == null) {
            return;
        }
        draw();
    }

    private void draw() {
        buttons.clear();
        inventory.clear();

        if (bordered()) {
            drawBorder();
        }
        build();
        if (navigable()) {
            addNavigation();
        }

        for (Map.Entry<Integer, Button> entry : buttons.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().icon(viewer, manager.icons()));
        }
    }

    /** The outer ring of panes, SPEC 8.2. Buttons drawn afterwards paint over it. */
    private void drawBorder() {
        ItemStack pane = manager.icons().border();
        int rows = size() / 9;
        for (int slot = 0; slot < size(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private void addNavigation() {
        var gui = manager.configs().get(ConfigFile.GUI);
        int closeSlot = gui.getInt("navigation.close-slot", 49);
        int backSlot = gui.getInt("navigation.back-slot", 45);

        if (parent != null && !buttons.containsKey(backSlot)) {
            set(backSlot, Button.of(
                            manager.icons().material("navigation.back-material",
                                    org.bukkit.Material.ARROW),
                            manager.text("gui.back"))
                    .onClick(context -> parent.open())
                    .build());
        }
        if (!buttons.containsKey(closeSlot)) {
            set(closeSlot, Button.of(
                            manager.icons().material("navigation.close-material",
                                    org.bukkit.Material.BARRIER),
                            manager.text("gui.close"))
                    .onClick(context -> context.player().closeInventory())
                    .build());
        }
    }

    // ==================================================================================
    // Clicking
    // ==================================================================================

    /**
     * Handles a click the listener has already cancelled.
     *
     * <p>Every guard that matters is here rather than in the listener, so there is one place
     * to read when asking "can this click do anything".
     */
    final void click(Player player, int slot, ClickType type) {
        // Not the viewer: a menu belongs to the player it was opened for, and nobody else's
        // click may drive it, whatever the server hands us.
        if (!player.getUniqueId().equals(viewer.getUniqueId())) {
            return;
        }

        Button button = buttons.get(slot);
        if (button == null || !button.hasAction()) {
            return;
        }

        // SPEC 8.2 and SPEC 17.5 case 59: asked again now, not when the menu was drawn.
        if (!button.usableBy(player)) {
            manager.playDenied(player);
            refresh();
            return;
        }

        button.run(new Button.ClickContext(player, this, slot, type));
    }

    /** Called when the window closes, for whatever reason. */
    protected void onClose() {
        // Subclasses with pending state override this. See ConfirmationMenu.
    }

    final void closed() {
        onClose();
    }
}
