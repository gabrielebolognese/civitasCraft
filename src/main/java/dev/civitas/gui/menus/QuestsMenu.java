package dev.civitas.gui.menus;

import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.core.income.QuestDefinition;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.lang.LangManager;
import dev.civitas.storage.row.PlayerQuestRow;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Today's three quests, SPEC 9.1 {@code /quests}.
 *
 * <p>Read-only. SPEC 13.1 pays a quest the moment its target is met rather than when a player
 * remembers to collect it, so there is nothing here to click: this screen exists to answer
 * "what am I supposed to be doing" and "how far along am I".
 */
public final class QuestsMenu extends Menu {

    private static final int[] SLOTS = {20, 22, 24};

    private final CivitasServices services;

    public QuestsMenu(MenuManager manager, CivitasServices services, Player viewer) {
        super(manager, viewer);
        this.services = services;
    }

    @Override
    protected Component title() {
        return manager.text("gui.quests.title");
    }

    @Override
    protected boolean live() {
        // Progress moves while the screen is open, which is half the point of opening it.
        return true;
    }

    @Override
    protected void build() {
        List<PlayerQuestRow> quests = services.quests().cached(viewer.getUniqueId());
        if (quests.isEmpty()) {
            set(22, Button.of(Material.PAPER, manager.text("gui.quests.none")).build());
            return;
        }

        for (int index = 0; index < quests.size() && index < SLOTS.length; index++) {
            set(SLOTS[index], questButton(quests.get(index)));
        }
    }

    private Button questButton(PlayerQuestRow quest) {
        QuestDefinition definition = services.quests().pool().byId(quest.questId()).orElse(null);
        boolean done = quest.isClaimed() || quest.isComplete();

        Component label = definition == null
                ? Component.text(quest.questId())
                : manager.text(definition.messageKey(),
                        LangManager.placeholder("target", String.valueOf(quest.target())));

        return Button.of(done ? Material.LIME_DYE : Material.WRITABLE_BOOK, label)
                .lore(manager.text("gui.quests.progress",
                        LangManager.placeholder("progress", String.valueOf(quest.progress())),
                        LangManager.placeholder("target", String.valueOf(quest.target()))))
                .lore(manager.text("gui.quests.reward",
                        LangManager.placeholder("amount",
                                dev.civitas.core.economy.Money.format(quest.reward(),
                                        services.economy().configs()))))
                .lore(done ? manager.text("gui.quests.done")
                        : manager.text("gui.quests.in-progress"))
                .glowing(done)
                .build();
    }
}
