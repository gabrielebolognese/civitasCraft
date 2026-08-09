package dev.civitas.gui.menus;

import dev.civitas.CivitasServices;
import dev.civitas.core.city.City;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.MenuLayout;
import dev.civitas.gui.framework.MenuManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The City Hall hub, SPEC 8.3.
 *
 * <p>Thirteen buttons. Seven of them opened nothing when M8 built this screen: they kept their
 * SPEC slots and icons and refused the click, so the hub was the shape SPEC describes from the
 * first day and each later milestone replaced one line here rather than rearranging the screen
 * around a new button. Wars was the last of them, and every button now leads somewhere.
 */
public final class MainMenu extends CityMenu {

    /** The layout file, SPEC 8.3. */
    public static final String LAYOUT = "main.yml";

    private final MenuLayout layout;

    public MainMenu(MenuManager manager, CivitasServices services, Player viewer, City city) {
        super(manager, services, viewer, city);
        this.layout = services.layouts().load(LAYOUT, "gui.main.title", 54);
    }

    @Override
    protected Component title() {
        return text(layout.titleKey(), "city", city().name());
    }

    @Override
    protected boolean live() {
        // The treasury and the online-member count are on this screen, SPEC 8.2.
        return true;
    }

    @Override
    protected boolean bordered() {
        return layout.bordered();
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();

        put("overview", 10, Material.BEACON, "gui.main.overview", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.overview-lore",
                                "members", String.valueOf(city.memberCount()),
                                "claims", String.valueOf(claimCount(city))))
                        .onClick(context -> new OverviewMenu(manager, services, viewer, city, this)
                                .open())
                        .build());

        put("claims", 12, Material.GRASS_BLOCK, "gui.main.claims", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.claims-lore",
                                "claims", String.valueOf(claimCount(city))))
                        .onClick(context -> new ClaimsMenu(manager, services, viewer, city, this)
                                .open())
                        .build());

        put("treasury", 14, Material.GOLD_INGOT, "gui.main.treasury", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.treasury-lore",
                                "balance", money(city.treasury()),
                                "upkeep", money(services.upkeepTask().amountFor(city))))
                        .onClick(context -> new TreasuryMenu(manager, services, viewer, city, this)
                                .open())
                        .build());

        put("members", 16, Material.PLAYER_HEAD, "gui.main.members", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.members-lore",
                                "online", String.valueOf(onlineMembers(city)),
                                "total", String.valueOf(city.memberCount())))
                        .onClick(context -> new MembersMenu(manager, services, viewer, city, this)
                                .open())
                        .build());

        // SPEC 8.3 slots 20 to 34, the systems later milestones build.
        put("defense", 20, Material.IRON_GOLEM_SPAWN_EGG, "gui.main.defense", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.defense-lore",
                                "used", String.valueOf(services.defense()
                                        .pointsSpent(city.id())),
                                "total", String.valueOf(services.defense().capacity(city)),
                                "upkeep", money(services.defense().registry()
                                        .dailyUpkeep(city.id()))))
                        .onClick(context -> new DefenseMenu(manager, services, viewer, city,
                                this).open())
                        .build());
        put("wars", 22, Material.NETHERITE_SWORD, "gui.main.wars", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(warsLore(city))
                        .onClick(context -> new WarsMenu(manager, services, viewer, city, this)
                                .open())
                        .build());
        put("diplomacy", 24, Material.WRITTEN_BOOK, "gui.main.diplomacy", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.diplomacy-lore",
                                "allies", String.valueOf(services.diplomacy().registry()
                                        .allyCount(city.id())),
                                "truces", String.valueOf(services.diplomacy().registry()
                                        .trucesOf(city.id(), System.currentTimeMillis()).size())))
                        .onClick(context -> new DiplomacyMenu(manager, services, viewer, city,
                                this).open())
                        .build());
        put("upgrades", 28, Material.ANVIL, "gui.main.upgrades", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.upgrades-lore",
                                "bought", String.valueOf(services.upgrades()
                                        .totalLevels(city.id())),
                                "total", String.valueOf(
                                        dev.civitas.core.upgrade.UpgradeType.values().length
                                                * dev.civitas.core.upgrade.UpgradeType.MAX_LEVEL)))
                        .onClick(context -> new UpgradesMenu(manager, services, viewer, city,
                                this).open())
                        .build());
        put("vault", 30, Material.ENDER_CHEST, "gui.main.vault", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.vault-lore", "pages",
                                String.valueOf(services.vaults().pagesOf(city))))
                        .onClick(context -> {
                            var allowed = services.vaults().checkAccess(
                                    context.player().getUniqueId(), city(), 0);
                            if (allowed instanceof dev.civitas.util.Result.Failure<Integer> f) {
                                dev.civitas.command.Replies.sendFailure(context.player(),
                                        manager.lang(), f);
                                return;
                            }
                            services.vaultView().open(context.player(), city(), 0);
                        })
                        .build());
        put("outposts", 32, Material.FILLED_MAP, "gui.main.outposts", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.outposts-lore",
                                "used", String.valueOf(services.outposts().registry()
                                        .countOf(city.id())),
                                "max", String.valueOf(services.outposts().maxOutposts(city))))
                        .onClick(context -> new OutpostsMenu(manager, services, viewer, city,
                                this).open())
                        .build());
        put("contests", 34, Material.PAINTING, "gui.main.contests", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.contests-lore"))
                        .onClick(context -> new ContestsMenu(manager, services, viewer, city,
                                this).open())
                        .build());

        put("spawn", 40, Material.ENDER_PEARL, "gui.main.spawn", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .lore(text("gui.main.spawn-lore"))
                        .onClick(context -> {
                            context.player().closeInventory();
                            var result = services.spawns().requestTeleport(context.player());
                            if (result instanceof dev.civitas.util.Result.Failure<Long> failure) {
                                dev.civitas.command.Replies.sendFailure(context.player(),
                                        manager.lang(), failure);
                            }
                        })
                        .build());

        put("settings", 42, Material.COMPARATOR, "gui.main.settings", entry ->
                Button.of(entry.material(), text(entry.labelKey()))
                        .onClick(context -> new SettingsMenu(manager, services, viewer, city, this)
                                .open())
                        .build());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    /**
     * Places a button wherever the layout file puts it.
     *
     * @param key      the layout key
     * @param slot     the SPEC 8.3 slot, used when the file does not define this button
     * @param material likewise the SPEC 8.3 icon
     */
    private void put(String key, int slot, Material material, String labelKey,
                     java.util.function.Function<MenuLayout.Entry, Button> factory) {
        MenuLayout.Entry entry = layout.entryOr(key, slot, material, labelKey);
        if (entry.slot() < size()) {
            set(entry.slot(), factory.apply(entry));
        }
    }

    /** SPEC 8.3's Wars lore: "Active war status or 'At peace'". */
    private net.kyori.adventure.text.Component warsLore(City city) {
        return services.wars().registry().engagedWarOf(city.id())
                .map(war -> text("gui.main.wars-lore.at-war",
                        "state", plain(war.state().messageKey())))
                .orElseGet(() -> text("gui.main.wars-lore.at-peace"));
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(manager.text(key));
    }

    private int claimCount(City city) {
        return services.claimRegistry().claimsOf(city.id()).size();
    }

    private long onlineMembers(City city) {
        return city.members().stream()
                .map(member -> org.bukkit.Bukkit.getPlayer(member.uuid()))
                .filter(java.util.Objects::nonNull)
                .count();
    }
}
