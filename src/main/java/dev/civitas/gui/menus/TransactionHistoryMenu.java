package dev.civitas.gui.menus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.gui.framework.PaginatedMenu;
import dev.civitas.storage.row.LedgerRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The city's last hundred transactions, SPEC 8.5 slot 31.
 *
 * <p>This is the SPEC 1.5 audit trail as a player sees it. It reads the ledger once when the
 * screen opens rather than on every draw: a hundred rows is a database query, and paging
 * through history must not put one on the server thread every twenty ticks.
 */
public final class TransactionHistoryMenu extends PaginatedMenu<LedgerRow> {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final CivitasServices services;
    private final City city;
    private final List<LedgerRow> rows = new ArrayList<>();

    private volatile boolean loading = true;

    /**
     * Reads the history once, here rather than on every draw.
     *
     * <p>SPEC 8.5 asks for the last hundred transactions, which is a database query. Paging
     * through them must not put one on the server thread every twenty ticks, so the fetch
     * happens once and the screen redraws when it lands.
     */
    public TransactionHistoryMenu(MenuManager manager, CivitasServices services, Player viewer,
                                  City city, Menu parent) {
        super(manager, viewer, parent);
        this.services = services;
        this.city = city;

        int limit = manager.configs().get(dev.civitas.config.ConfigFile.GUI)
                .getInt("history.entries", 100);
        services.treasury().history(city.id(), limit)
                .whenComplete((found, error) -> services.scheduler().runOnMain(() -> {
                    loading = false;
                    if (error == null && found != null) {
                        rows.clear();
                        rows.addAll(found);
                    }
                    refresh();
                }));
    }

    @Override
    protected Component title() {
        return manager.text("gui.history.title");
    }

    @Override
    protected List<LedgerRow> contents() {
        return List.copyOf(rows);
    }

    @Override
    protected Button entryButton(LedgerRow row, int index) {
        boolean incoming = row.amount().signum() >= 0;
        return Button.of(incoming ? Material.LIME_DYE : Material.RED_DYE,
                        manager.text("gui.history.entry",
                                dev.civitas.lang.LangManager.placeholder("type", row.type())))
                .lore(manager.text("gui.history.amount",
                        dev.civitas.lang.LangManager.placeholder("amount",
                                dev.civitas.core.economy.Money.format(row.amount(),
                                        services.economy().configs()))))
                .lore(manager.text("gui.history.when",
                        dev.civitas.lang.LangManager.placeholder("when",
                                WHEN.format(Instant.ofEpochMilli(row.timestamp())))))
                .lore(manager.text("gui.history.actor",
                        dev.civitas.lang.LangManager.placeholder("actor", actorName(row))))
                .build();
    }

    @Override
    protected void decorate() {
        if (loading) {
            set(22, Button.of(Material.CLOCK, manager.text("gui.history.loading")).build());
        } else if (rows.isEmpty()) {
            set(22, Button.of(Material.PAPER, manager.text("gui.history.empty")).build());
        }
    }

    private String actorName(LedgerRow row) {
        if (row.actorUuid() == null) {
            return manager.lang().get("gui.history.system").toString();
        }
        String name = org.bukkit.Bukkit.getOfflinePlayer(row.actorUuid()).getName();
        return name == null ? row.actorUuid().toString().substring(0, 8) : name;
    }
}
