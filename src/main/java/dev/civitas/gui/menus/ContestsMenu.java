package dev.civitas.gui.menus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.core.contest.Contest;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.storage.row.ContestEntryRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The contest screen, SPEC 8.3 slot 34: "Current theme, time remaining".
 *
 * <p>Information only, and deliberately so. SPEC 8 describes no contest screen beyond that
 * line, and scoring an entry means three numbers from one to ten, which is thirty buttons of
 * guessing at a layout SPEC never asked for. Marking, submitting, visiting and voting are all
 * on {@code /contest}, and this screen tells a player what the contest is and what their city
 * has done about it.
 */
public final class ContestsMenu extends CityMenu {

    private static final int[] ENTRY_SLOTS = {28, 29, 30, 31, 32, 33, 34};

    public ContestsMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
    }

    @Override
    protected Component title() {
        return text("gui.contests.title");
    }

    @Override
    protected boolean live() {
        // The countdown is the point of the screen.
        return true;
    }

    /** The entries as of the last refresh. Read on the server thread, never queried here. */
    private List<ContestEntryRow> entries = List.of();

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }

        Optional<Contest> running = services.contests().current();
        if (running.isEmpty()) {
            set(22, info(Material.BARRIER, text("gui.contests.none"),
                    text("gui.contests.none-lore")));
            return;
        }

        Contest contest = running.get();
        long now = System.currentTimeMillis();

        set(13, info(Material.PAINTING,
                text("gui.contests.theme", "theme", contest.theme()),
                text("gui.contests.phase", "phase", plain(contest.state().messageKey())),
                text("gui.contests.remaining", "remaining",
                        describe(contest.millisUntilNextPhase(now)))));

        set(11, info(Material.GOLDEN_AXE, text("gui.contests.how"),
                text("gui.contests.how-lore")));

        set(15, info(Material.WRITABLE_BOOK,
                text("gui.contests.entered", "count", String.valueOf(entries.size())),
                text("gui.contests.entered-lore")));

        for (int index = 0; index < entries.size() && index < ENTRY_SLOTS.length; index++) {
            ContestEntryRow entry = entries.get(index);
            String cityName = services.registry().city(entry.cityId())
                    .map(City::name)
                    .orElse("?");
            set(ENTRY_SLOTS[index], info(Material.PLAYER_HEAD,
                    text("gui.contests.entry", "city", cityName),
                    text("gui.contests.entry-lore", "index", String.valueOf(index + 1))));
        }

        // Refresh the list for the next redraw. The screen is live, so a query started now
        // shows up a tick later rather than blocking the draw on storage.
        services.contests().submittedEntries().thenAccept(loaded ->
                services.scheduler().runOnMain(() -> this.entries = loaded));
    }

    private String describe(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours >= 24) {
            return TimeUnit.MILLISECONDS.toDays(millis) + "d";
        }
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(millis)) + "m";
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(manager.lang().get(key));
    }
}
