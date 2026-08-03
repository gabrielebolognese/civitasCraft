package dev.civitas.core.vault;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.civitas.core.city.City;
import dev.civitas.lang.LangManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Open vault pages, and the rules about sharing them.
 *
 * <h2>One inventory per page, not per viewer</h2>
 * Two members opening page 1 must see the same inventory object. If each got their own copy
 * of the contents, the second to close would overwrite the first, and an item moved by one
 * would reappear for the other: a duplication bug rather than a display bug. So a page is
 * opened once, shared by everyone looking at it, and written back when the last of them
 * closes it.
 */
public final class VaultView {

    private final org.bukkit.plugin.Plugin plugin;
    private final VaultService vaults;
    private final LangManager lang;
    private final Logger logger;

    /** Open pages, by city and page. */
    private final Map<String, Open> open = new ConcurrentHashMap<>();

    public VaultView(org.bukkit.plugin.Plugin plugin, VaultService vaults, LangManager lang,
                     Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.vaults = Objects.requireNonNull(vaults, "vaults");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ==================================================================================
    // Opening
    // ==================================================================================

    /**
     * Shows a page to a player.
     *
     * <p>Loads it if nobody has it open, and shares the existing inventory if somebody does.
     * The load is async; the open happens on the server thread when it lands.
     */
    public void open(Player player, City city, int page) {
        String key = key(city.id(), page);
        Open existing = open.get(key);
        if (existing != null) {
            player.openInventory(existing.inventory());
            return;
        }

        vaults.load(city.id(), page).whenComplete((contents, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Could not read vault page " + page
                                + " for city " + city.id(), error);
                        lang.send(player, "vault.read-failed");
                        return;
                    }
                    // Somebody may have opened it while the read was in flight.
                    Open now = open.get(key);
                    if (now != null) {
                        player.openInventory(now.inventory());
                        return;
                    }
                    Open created = create(city, page, contents);
                    open.put(key, created);
                    player.openInventory(created.inventory());
                }));
    }

    private Open create(City city, int page, ItemStack[] contents) {
        VaultHolder holder = new VaultHolder(city.id(), page);
        Component title = lang.get("vault.title",
                LangManager.placeholder("city", city.name()),
                LangManager.placeholder("page", String.valueOf(page + 1)),
                LangManager.placeholder("pages", String.valueOf(vaults.pagesOf(city))));

        Inventory inventory = Bukkit.createInventory(holder, vaults.pageSize(), title);
        holder.attach(inventory);

        for (int slot = 0; slot < Math.min(contents.length, inventory.getSize()); slot++) {
            inventory.setItem(slot, contents[slot]);
        }
        return new Open(holder, inventory);
    }

    // ==================================================================================
    // Closing
    // ==================================================================================

    /**
     * Called when somebody closes a page.
     *
     * <p>Saves only when the last viewer leaves. Saving on every close would be a database
     * write per player per glance, and would race two viewers into writing each other's
     * intermediate states.
     */
    public void onClosed(VaultHolder holder, Inventory inventory) {
        if (!inventory.getViewers().isEmpty()) {
            // Somebody is still looking at it; whoever closes last will write it.
            return;
        }
        String key = key(holder.cityId(), holder.page());
        open.remove(key);
        persist(holder, inventory);
    }

    /** Writes a page back without closing it, for the periodic flush and for shutdown. */
    public void persist(VaultHolder holder, Inventory inventory) {
        vaults.save(holder.cityId(), holder.page(), inventory.getContents())
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Could not save vault page " + holder.page()
                            + " for city " + holder.cityId(), error);
                    return 0;
                });
    }

    /**
     * Writes every open page and closes it, on plugin disable.
     *
     * <p>Blocking, deliberately: an unsaved vault page on shutdown is a city's valuables
     * gone, and SPEC 17.7 case 84 already establishes that a disable waits for its writes.
     */
    public void saveAndCloseAll() {
        for (Open page : java.util.List.copyOf(open.values())) {
            try {
                vaults.save(page.holder().cityId(), page.holder().page(),
                        page.inventory().getContents()).join();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Could not save a vault page on shutdown", e);
            }
            java.util.List.copyOf(page.inventory().getViewers())
                    .forEach(viewer -> viewer.closeInventory());
        }
        open.clear();
    }

    /** How many pages are open right now, for diagnostics and tests. */
    public int openPages() {
        return open.size();
    }

    /** Whether a particular page is open, so a war or a disband can find out. */
    public boolean isOpen(int cityId, int page) {
        return open.containsKey(key(cityId, page));
    }

    private static String key(int cityId, int page) {
        return cityId + ":" + page;
    }

    private record Open(VaultHolder holder, Inventory inventory) { }
}
