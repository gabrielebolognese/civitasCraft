package dev.civitas.gui.framework;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;

/**
 * Where a screen's buttons sit and what they look like, read from YAML.
 *
 * <p>SPEC 8 requires every menu to be defined in a file under {@code resources/gui/} "so
 * layouts can be changed without recompiling". The split that makes that work: <b>YAML owns
 * appearance and position, Java owns behaviour</b>. A layout entry says slot 10 is a Beacon
 * labelled {@code gui.main.overview}; the menu class says clicking it opens the Overview
 * screen. An operator can move it to slot 12 or make it a Diamond; they cannot make it
 * disband the city, because nothing in the file names an action.
 *
 * <p>Entries are looked up by key rather than by slot, so moving a button in the file does
 * not move it in the code.
 */
public final class MenuLayout {

    private final String titleKey;
    private final int size;
    private final boolean bordered;
    private final Map<String, Entry> entries;

    MenuLayout(String titleKey, int size, boolean bordered, Map<String, Entry> entries) {
        this.titleKey = Objects.requireNonNull(titleKey, "titleKey");
        this.size = size;
        this.bordered = bordered;
        this.entries = Map.copyOf(entries);
    }

    /** An empty layout, so a screen whose file is missing still opens with its defaults. */
    public static MenuLayout empty(String titleKey, int size) {
        return new MenuLayout(titleKey, size, true, new LinkedHashMap<>());
    }

    public String titleKey() {
        return titleKey;
    }

    public int size() {
        return size;
    }

    public boolean bordered() {
        return bordered;
    }

    public Optional<Entry> entry(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * An entry, or a stated fallback if the file does not define it.
     *
     * <p>A screen therefore still works against a layout file an operator has trimmed, which
     * matters because the file is theirs to edit and half of them will delete a line.
     */
    public Entry entryOr(String key, int slot, Material material, String labelKey) {
        return entry(key).orElseGet(() -> new Entry(key, slot, material, labelKey, List.of()));
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * One button's appearance.
     *
     * @param key      the name the menu class looks it up by
     * @param slot     where it sits
     * @param material what it looks like
     * @param labelKey the language key for its name
     * @param loreKeys language keys for its lore, in order
     */
    public record Entry(String key, int slot, Material material, String labelKey,
                        List<String> loreKeys) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(labelKey, "labelKey");
            loreKeys = List.copyOf(loreKeys);
        }
    }
}
