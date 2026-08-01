package dev.civitas.gui.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One clickable slot.
 *
 * <h2>The permission is a function, not a flag</h2>
 * SPEC 8.2 requires that a button be re-checked <em>at execution time</em>, because a player
 * can spoof a click and because a permission can be revoked while the menu sits open (SPEC
 * 17.5 case 59). A boolean captured when the menu was built cannot express that, so a button
 * carries the test itself and {@link #usableBy} is asked again on every click.
 *
 * <p>A button the viewer may not use still renders, as a barrier carrying the reason, so the
 * menu does not silently change shape depending on rank.
 */
public final class Button {

    private final Material material;
    private final Component label;
    private final List<Component> lore;
    private final Predicate<Player> permission;
    private final Component deniedReason;
    private final Consumer<ClickContext> action;
    private final boolean glowing;

    private Button(Builder builder) {
        this.material = Objects.requireNonNull(builder.material, "material");
        this.label = Objects.requireNonNull(builder.label, "label");
        this.lore = List.copyOf(builder.lore);
        this.permission = builder.permission;
        this.deniedReason = builder.deniedReason;
        this.action = builder.action;
        this.glowing = builder.glowing;
    }

    public static Builder of(Material material, Component label) {
        return new Builder(material, label);
    }

    /** Whether this viewer may use the button, asked afresh every time it matters. */
    public boolean usableBy(Player player) {
        return permission == null || permission.test(player);
    }

    public boolean hasAction() {
        return action != null;
    }

    /**
     * Runs the click.
     *
     * <p>Callers must have checked {@link #usableBy} first; this checks again anyway, because
     * a guard that is only in one place is a guard that a later caller will forget.
     */
    void run(ClickContext context) {
        if (action == null || !usableBy(context.player())) {
            return;
        }
        action.accept(context);
    }

    /** The icon as this viewer should see it. */
    public ItemStack icon(Player viewer, Icons icons) {
        if (!usableBy(viewer)) {
            return icons.denied(label, deniedReason);
        }
        return icons.build(material, label, lore, glowing);
    }

    public Component label() {
        return label;
    }

    public List<Component> lore() {
        return lore;
    }

    public Material material() {
        return material;
    }

    /** What a click carries. Everything a handler needs, and nothing it should trust blindly. */
    public record ClickContext(Player player, Menu menu, int slot,
                               org.bukkit.event.inventory.ClickType click) {

        public boolean isRightClick() {
            return click == org.bukkit.event.inventory.ClickType.RIGHT
                    || click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT;
        }

        public boolean isShiftClick() {
            return click.isShiftClick();
        }
    }

    public static final class Builder {

        private final Material material;
        private final Component label;
        private final List<Component> lore = new ArrayList<>();
        private Predicate<Player> permission;
        private Component deniedReason;
        private Consumer<ClickContext> action;
        private boolean glowing;

        private Builder(Material material, Component label) {
            this.material = material;
            this.label = label;
        }

        public Builder lore(Component line) {
            lore.add(line);
            return this;
        }

        public Builder lore(List<Component> lines) {
            lore.addAll(lines);
            return this;
        }

        /**
         * Gates the button.
         *
         * @param test   asked again at click time, never cached
         * @param reason the lore shown on the barrier when the test fails
         */
        public Builder requires(Predicate<Player> test, Component reason) {
            this.permission = test;
            this.deniedReason = reason;
            return this;
        }

        public Builder onClick(Consumer<ClickContext> handler) {
            this.action = handler;
            return this;
        }

        /** An enchant glint, for a toggle that is currently on (SPEC 8.4). */
        public Builder glowing(boolean value) {
            this.glowing = value;
            return this;
        }

        public Button build() {
            return new Button(this);
        }
    }
}
