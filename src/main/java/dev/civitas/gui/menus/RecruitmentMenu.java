package dev.civitas.gui.menus;

import java.util.Comparator;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * SPEC 34.4's recruitment board.
 *
 * <h2>Sorted smallest first, and that is the whole point</h2>
 *
 * <p>SPEC 34.4: "listing cities with {@code open_join} enabled, <b>sorted by member count
 * ascending</b>, so new players are steered toward small cities rather than the biggest one. This
 * directly serves pillar 1.3."
 *
 * <p>Pillar 1.3 is "a new player must be able to matter": "A player joining on day 90 must be able
 * to contribute meaningfully within their first session." A board sorted the other way would put
 * the twenty-member city at the top and hand it every newcomer, which is the outcome the pillar
 * exists to prevent — so the sort order here is a design decision rather than a display preference.
 */
public final class RecruitmentMenu extends Menu {

    private final CivitasServices services;

    public RecruitmentMenu(MenuManager manager, CivitasServices services, Player viewer) {
        super(manager, viewer);
        this.services = services;
    }

    @Override
    protected Component title() {
        return manager.lang().get("gui.recruitment.title");
    }

    /** Member counts change while the board is open, and an open-join city can close. */
    @Override
    protected boolean live() {
        return true;
    }

    @Override
    protected void build() {
        List<City> open = services.registry().cities().stream()
                .filter(City::isOpenJoin)
                .filter(city -> !city.isFrozen())
                .sorted(Comparator.comparingInt((City city) -> city.members().size())
                        .thenComparing(City::name))
                .limit(slots().length)
                .toList();

        set(4, Button.of(Material.PLAYER_HEAD, manager.lang().get("gui.recruitment.header"))
                .lore(manager.lang().get("gui.recruitment.header-lore",
                        Replies.p("count", String.valueOf(open.size()))))
                .build());

        if (open.isEmpty()) {
            set(22, Button.of(Material.BARRIER, manager.lang().get("gui.recruitment.none"))
                    .lore(manager.lang().get("gui.recruitment.none-lore"))
                    .build());
            return;
        }

        for (int i = 0; i < open.size(); i++) {
            City city = open.get(i);
            set(slots()[i], entry(city));
        }
    }

    private Button entry(City city) {
        boolean alreadyInACity = services.registry().cityOf(viewer.getUniqueId()).isPresent();

        return Button.of(Material.WHITE_BANNER,
                        manager.lang().get("gui.recruitment.entry",
                                Replies.p("city", city.name())))
                .lore(List.of(
                        manager.lang().get("gui.recruitment.entry-members",
                                Replies.p("count", String.valueOf(city.members().size()))),
                        manager.lang().get("gui.recruitment.entry-motd",
                                Replies.p("motd", city.motd() == null || city.motd().isBlank()
                                        ? manager.lang().plain("gui.recruitment.no-motd")
                                        : city.motd())),
                        manager.lang().get(alreadyInACity
                                ? "gui.recruitment.entry-already"
                                : "gui.recruitment.entry-join")))
                // The click closes and runs the command rather than joining directly. Joining has
                // preconditions — SPEC 5.2's ban list, member cap, and the 24-hour switch
                // cooldown — and a button that bypassed the service would be a second place those
                // rules live.
                .requires(player -> !alreadyInACity,
                        manager.lang().get("gui.recruitment.entry-already"))
                .onClick(context -> {
                    context.player().closeInventory();
                    context.player().performCommand("city join " + city.name());
                })
                .build();
    }

    /** The 21 slots inside the border, which is more open-join cities than a server will have. */
    private static int[] slots() {
        return new int[] {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34};
    }
}
