package dev.civitas.gui.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.civitas.config.ConfigFile;
import dev.civitas.lang.LangManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * A menu whose contents do not fit on one screen, SPEC 8.2.
 *
 * <p>Previous on slot 48 and next on slot 50, both Spectral Arrows, both hidden when there is
 * nowhere to go rather than shown greyed out: an arrow that does nothing is a worse answer
 * than no arrow.
 *
 * <p>The page is clamped on every draw rather than only when it changes. A list can shrink
 * underneath an open menu, and a viewer left on page 4 of a list that now has two pages must
 * land somewhere real (SPEC 17.5 case 64).
 *
 * @param <T> what is being listed
 */
public abstract class PaginatedMenu<T> extends Menu {

    /** The 28 interior slots of a bordered 54-slot menu: rows 2 to 5, columns 2 to 8. */
    private static final int[] INTERIOR = interior();

    private int page;

    protected PaginatedMenu(MenuManager manager, Player viewer) {
        super(manager, viewer);
    }

    protected PaginatedMenu(MenuManager manager, Player viewer, Menu parent) {
        super(manager, viewer, parent);
    }

    /** The whole list, re-read on every draw so the menu follows the data. */
    protected abstract List<T> contents();

    /** One entry as a button. */
    protected abstract Button entryButton(T entry, int index);

    /** Which slots entries go in. Override to reshape a screen. */
    protected int[] contentSlots() {
        return INTERIOR;
    }

    public final int page() {
        return page;
    }

    public final int pageCount() {
        return Math.max(1, (contents().size() + perPage() - 1) / perPage());
    }

    private int perPage() {
        return Math.max(1, contentSlots().length);
    }

    /** Moves by {@code delta} pages, clamped. Returns whether the page actually changed. */
    public final boolean turn(int delta) {
        int wanted = clamp(page + delta);
        if (wanted == page) {
            return false;
        }
        page = wanted;
        refresh();
        return true;
    }

    private int clamp(int wanted) {
        return Math.max(0, Math.min(wanted, pageCount() - 1));
    }

    @Override
    protected void build() {
        // A list can shrink while the menu is open; land somewhere real before drawing.
        page = clamp(page);

        List<T> all = contents();
        int[] slots = contentSlots();
        int from = page * perPage();

        for (int offset = 0; offset < slots.length; offset++) {
            int index = from + offset;
            if (index >= all.size()) {
                break;
            }
            set(slots[offset], entryButton(all.get(index), index));
        }

        addPageButtons();
        decorate();
    }

    /** Anything a subclass wants alongside the list. Runs after the page buttons. */
    protected void decorate() {
        // Optional.
    }

    private void addPageButtons() {
        var gui = manager.configs().get(ConfigFile.GUI);
        int previousSlot = gui.getInt("pagination.previous-slot", 48);
        int nextSlot = gui.getInt("pagination.next-slot", 50);
        Material arrow = manager.icons()
                .material("pagination.arrow-material", Material.SPECTRAL_ARROW);

        int pages = pageCount();
        if (page > 0) {
            set(previousSlot, Button.of(arrow, manager.text("gui.previous-page",
                            LangManager.placeholder("page", String.valueOf(page))))
                    .onClick(context -> turn(-1))
                    .build());
        }
        if (page < pages - 1) {
            set(nextSlot, Button.of(arrow, manager.text("gui.next-page",
                            LangManager.placeholder("page", String.valueOf(page + 2))))
                    .onClick(context -> turn(1))
                    .build());
        }
    }

    private static int[] interior() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots.add(row * 9 + column);
            }
        }
        int[] array = new int[slots.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = slots.get(i);
        }
        return array;
    }

    /** For subclasses that want the shared interior layout without recomputing it. */
    protected static int[] interiorSlots() {
        return Objects.requireNonNull(INTERIOR).clone();
    }
}
