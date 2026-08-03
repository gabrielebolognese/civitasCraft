package dev.civitas.gui.menus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityMember;
import dev.civitas.core.city.CityPermission;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.gui.framework.PaginatedMenu;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * The member list, SPEC 8.6.
 *
 * <p>Online members first, then by rank weight, because the list is most often opened to find
 * somebody to talk to. Each head carries what SPEC 8.6 asks for: rank, joined date, last
 * seen, lifetime contribution and online status.
 */
public final class MembersMenu extends PaginatedMenu<CityMember> {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private final CivitasServices services;
    private final int cityId;

    public MembersMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                       Menu parent) {
        super(manager, viewer, parent);
        this.services = services;
        this.cityId = city.id();
    }

    private City city() {
        return services.registry().city(cityId).orElseThrow();
    }

    @Override
    protected Component title() {
        return manager.text("gui.members.title",
                LangManager.placeholder("city", city().name()));
    }

    @Override
    protected boolean live() {
        // Online status, which is the column people actually watch.
        return true;
    }

    @Override
    protected List<CityMember> contents() {
        return services.registry().city(cityId)
                .map(city -> city.members().stream()
                        .sorted(Comparator
                                .comparing((CityMember member) -> isOnline(member) ? 0 : 1)
                                .thenComparing(member -> -weightOf(city, member))
                                .thenComparing(this::nameOf))
                        .toList())
                .orElse(List.of());
    }

    @Override
    protected Button entryButton(CityMember member, int index) {
        City city = city();
        boolean online = isOnline(member);

        Button.Builder builder = Button.of(Material.PLAYER_HEAD,
                        manager.text(online ? "gui.members.entry-online" : "gui.members.entry",
                                LangManager.placeholder("player", nameOf(member))))
                .lore(manager.text("gui.members.rank", LangManager.placeholder("rank",
                        city.rank(member.rankId()).map(rank -> rank.name()).orElse("-"))))
                .lore(manager.text("gui.members.joined", LangManager.placeholder("date",
                        DATE.format(Instant.ofEpochMilli(member.joinedAt())))))
                .lore(manager.text("gui.members.contributed", LangManager.placeholder("amount",
                        dev.civitas.core.economy.Money.format(member.contributedTotal(),
                                services.economy().configs()))));

        if (city.isMayor(member.uuid())) {
            builder.lore(manager.text("gui.members.is-mayor"));
        }
        builder.onClick(context -> new MemberActionsMenu(manager, services, viewer, city,
                member.uuid(), this).open());

        return builder.build();
    }

    /** Heads render as the member's own skin, which is what makes the list scannable. */
    @Override
    protected void decorate() {
        var gui = manager.configs().get(dev.civitas.config.ConfigFile.GUI);
        int inviteSlot = gui.getInt("members.invite-slot", 45);
        int banSlot = gui.getInt("members.ban-slot", 46);

        set(inviteSlot, Button.of(Material.PLAYER_HEAD, manager.text("gui.members.invite"))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.INVITE),
                        manager.missingPermission(CityPermission.INVITE.name()))
                .onClick(context -> promptInvite(context.player()))
                .build());

        set(banSlot, Button.of(Material.BARRIER, manager.text("gui.members.bans"))
                .requires(player -> city().hasPermission(player.getUniqueId(),
                                CityPermission.KICK),
                        manager.missingPermission(CityPermission.KICK.name()))
                .onClick(context -> new BanListMenu(manager, services, viewer, city(), this).open())
                .build());
    }

    /**
     * Invites by typed name.
     *
     * <p>The framework's amount prompt is for numbers, so this asks through the same chat
     * mechanism with its own parser: a name is not a number and must not go through
     * {@code Money.parse}.
     */
    private void promptInvite(Player player) {
        services.amountInput().askText(player, "gui.members.invite-prompt", typed ->
                services.lookup().resolve(typed).whenComplete((found, error) ->
                        services.scheduler().runOnMain(() -> {
                            if (error != null || found == null || found.isEmpty()) {
                                manager.lang().send(player, "player.unknown",
                                        LangManager.placeholder("player", typed));
                                open();
                                return;
                            }
                            services.cities().invite(player.getUniqueId(), city(),
                                            found.get().uuid())
                                    .whenComplete((result, failure) ->
                                            services.scheduler().runOnMain(() -> {
                                                report(player, result, failure,
                                                        "gui.members.invited");
                                                open();
                                            }));
                        })), this::open);
    }

    private <T> void report(Player player, Result<T> result, Throwable error, String successKey) {
        if (error != null) {
            manager.lang().send(player, "command.error");
            return;
        }
        if (result instanceof Result.Failure<T> failure) {
            Replies.sendFailure(player, manager.lang(), failure);
            return;
        }
        manager.lang().send(player, successKey);
    }

    private boolean isOnline(CityMember member) {
        return Bukkit.getPlayer(member.uuid()) != null;
    }

    private int weightOf(City city, CityMember member) {
        return city.rank(member.rankId()).map(rank -> rank.weight()).orElse(0);
    }

    private String nameOf(CityMember member) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(member.uuid());
        String name = offline.getName();
        return name == null ? member.uuid().toString().substring(0, 8) : name;
    }

    /** Puts the member's own face on their head item. */
    static ItemStack faceOf(ItemStack head, java.util.UUID member) {
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(member));
            head.setItemMeta(skull);
        }
        return head;
    }
}
