package dev.civitas.gui.framework;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.civitas.config.ConfigFile;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * "Are you sure", SPEC 8.2.
 *
 * <p>Lime Concrete Confirm at slot 29, Red Concrete Cancel at slot 33, and one rule that
 * matters more than either: <b>closing the window is a cancel</b> (SPEC 17.5 case 66).
 * Nothing about a dialog that vanished should be able to run afterwards, so the decision is
 * recorded the moment either button is pressed and the close handler can only act on a
 * dialog nobody decided.
 *
 * <p>The action itself is a callback rather than something this class performs, so a
 * confirmation dialog never knows what it is confirming and cannot get it half right.
 */
public final class ConfirmationMenu extends Menu {

    private final Component title;
    private final Component question;
    private final List<Component> details;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    /** One decision per dialog, whichever way it goes and whichever path reaches it. */
    private final AtomicBoolean decided = new AtomicBoolean();

    private ConfirmationMenu(Builder builder) {
        super(builder.manager, builder.viewer, builder.parent);
        this.title = Objects.requireNonNull(builder.title, "title");
        this.question = Objects.requireNonNull(builder.question, "question");
        this.details = List.copyOf(builder.details);
        this.onConfirm = Objects.requireNonNull(builder.onConfirm, "onConfirm");
        this.onCancel = builder.onCancel;
    }

    public static Builder builder(MenuManager manager, Player viewer) {
        return new Builder(manager, viewer);
    }

    @Override
    protected Component title() {
        return title;
    }

    @Override
    protected void build() {
        var gui = manager.configs().get(ConfigFile.GUI);

        set(gui.getInt("confirmation.confirm-slot", 29), Button.of(
                        manager.icons().material("confirmation.confirm-material",
                                Material.LIME_CONCRETE),
                        manager.text("gui.confirm"))
                .lore(question)
                .lore(details)
                .onClick(context -> decide(true))
                .build());

        set(gui.getInt("confirmation.cancel-slot", 33), Button.of(
                        manager.icons().material("confirmation.cancel-material",
                                Material.RED_CONCRETE),
                        manager.text("gui.cancel"))
                .onClick(context -> decide(false))
                .build());
    }

    private void decide(boolean confirmed) {
        if (!decided.compareAndSet(false, true)) {
            return;
        }
        // Close first: the action may open another menu, and a dialog left underneath it
        // would be reachable again with the Back button.
        viewer.closeInventory();

        if (confirmed) {
            onConfirm.run();
        } else if (onCancel != null) {
            onCancel.run();
        }
    }

    /** SPEC 17.5 case 66: a dialog closed without an answer is a cancel. */
    @Override
    protected void onClose() {
        boolean closeIsCancel = manager.configs().get(ConfigFile.GUI)
                .getBoolean("confirmation.close-is-cancel", true);
        if (!closeIsCancel || !decided.compareAndSet(false, true)) {
            return;
        }
        if (onCancel != null) {
            onCancel.run();
        }
    }

    /** Whether this dialog has already been answered, for tests and for assertions. */
    public boolean decided() {
        return decided.get();
    }

    public static final class Builder {

        private final MenuManager manager;
        private final Player viewer;
        private Component title;
        private Component question;
        private final List<Component> details = new java.util.ArrayList<>();
        private Runnable onConfirm;
        private Runnable onCancel;
        private Menu parent;

        private Builder(MenuManager manager, Player viewer) {
            this.manager = Objects.requireNonNull(manager, "manager");
            this.viewer = Objects.requireNonNull(viewer, "viewer");
        }

        public Builder title(Component value) {
            this.title = value;
            return this;
        }

        /** The one-line "you are about to ..." shown on the Confirm button. */
        public Builder question(Component value) {
            this.question = value;
            return this;
        }

        /** Extra lines: what it costs, what it cannot be undone from. */
        public Builder detail(Component line) {
            this.details.add(line);
            return this;
        }

        public Builder onConfirm(Runnable action) {
            this.onConfirm = action;
            return this;
        }

        public Builder onCancel(Runnable action) {
            this.onCancel = action;
            return this;
        }

        public Builder parent(Menu value) {
            this.parent = value;
            return this;
        }

        public ConfirmationMenu build() {
            return new ConfirmationMenu(this);
        }
    }
}
