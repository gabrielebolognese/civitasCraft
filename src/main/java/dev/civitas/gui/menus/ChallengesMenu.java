package dev.civitas.gui.menus;

import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.CityChallengeRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * This week's city challenges, SPEC 9.1 {@code /challenges}.
 *
 * <p>Also read-only, and for the same reason as {@link QuestsMenu}. The progress figure is
 * what matters here: SPEC 13.2 pools it across the whole city, so one member's own share is
 * deliberately not broken out. The number on the screen belongs to the city.
 */
public final class ChallengesMenu extends CityMenu {

    private static final int[] SLOTS = {21, 23};

    public ChallengesMenu(MenuManager manager, CivitasServices services, Player viewer,
                          City city) {
        super(manager, services, viewer, city);
    }

    @Override
    protected Component title() {
        return text("gui.challenges.title", "city", city().name());
    }

    @Override
    protected boolean live() {
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        List<CityChallengeRow> rows = services.challenges().cached(city().id());
        if (rows.isEmpty()) {
            set(22, Button.of(Material.PAPER, text("gui.challenges.none")).build());
            return;
        }

        for (int index = 0; index < rows.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], challengeButton(rows.get(index)));
        }
    }

    private Button challengeButton(CityChallengeRow row) {
        boolean done = row.isComplete();

        return Button.of(done ? Material.LIME_DYE : Material.WRITTEN_BOOK,
                        manager.text("challenge." + row.challengeId(),
                                LangManager.placeholder("target", String.valueOf(row.target()))))
                .lore(text("gui.challenges.progress",
                        "progress", String.valueOf(row.progress()),
                        "target", String.valueOf(row.target())))
                .lore(text("gui.challenges.reward", "amount", money(row.reward())))
                .lore(done ? text("gui.challenges.done") : text("gui.challenges.in-progress"))
                .glowing(done)
                .build();
    }
}
