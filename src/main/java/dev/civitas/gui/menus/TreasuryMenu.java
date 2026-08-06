package dev.civitas.gui.menus;

import java.math.BigDecimal;

import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.core.city.City;
import dev.civitas.core.city.CityPermission;
import dev.civitas.gui.framework.Button;
import dev.civitas.gui.framework.Menu;
import dev.civitas.gui.framework.MenuManager;
import dev.civitas.util.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The treasury, SPEC 8.5.
 *
 * <p>Fixed amounts on the left and withdrawals on the right, exactly as SPEC 8.5 lays them
 * out, with the custom-amount prompt on each side. The withdraw buttons carry the SPEC 8.5
 * 25% allowance in their lore, because a member who finds out about the cap only when it
 * refuses them experiences it as a bug rather than a rule.
 */
public final class TreasuryMenu extends CityMenu {

    public static final String LAYOUT = "treasury.yml";

    private static final BigDecimal SMALL = new BigDecimal("1000");
    private static final BigDecimal MEDIUM = new BigDecimal("10000");
    private static final BigDecimal LARGE = new BigDecimal("100000");

    private final dev.civitas.gui.framework.MenuLayout layout;

    public TreasuryMenu(MenuManager manager, CivitasServices services, Player viewer, City city,
                        Menu parent) {
        super(manager, services, viewer, city, parent);
        this.layout = services.layouts().load(LAYOUT, "gui.treasury.title", 54);
    }

    @Override
    protected Component title() {
        return text(layout.titleKey());
    }

    @Override
    protected boolean live() {
        // SPEC 8.5 marks the balance live-updating; two members depositing at once should
        // not leave either of them reading a stale figure.
        return true;
    }

    @Override
    protected void build() {
        if (closeIfStale()) {
            return;
        }
        City city = city();

        set(4, info(Material.GOLD_BLOCK,
                text("gui.treasury.balance", "amount", money(city.treasury())),
                text("gui.treasury.your-wallet", "amount",
                        money(services.economy().balanceOrZero(viewer.getUniqueId())))));

        set(19, deposit(Material.EMERALD, SMALL));
        set(20, deposit(Material.EMERALD_BLOCK, MEDIUM));
        set(21, deposit(Material.DIAMOND, LARGE));
        set(22, customDeposit());

        set(23, withdraw(Material.REDSTONE, SMALL));
        set(24, withdraw(Material.REDSTONE_BLOCK, MEDIUM));
        set(25, customWithdraw());

        set(31, Button.of(Material.BOOK, text("gui.treasury.history"))
                .lore(text("gui.treasury.history-lore"))
                .onClick(context -> new TransactionHistoryMenu(manager, services, viewer, city,
                        this).open())
                .build());

        set(37, upkeepInfo(city));

        set(40, Button.of(Material.PLAYER_HEAD, text("gui.treasury.contributions"))
                .lore(text("gui.treasury.contributions-lore"))
                .onClick(context -> new ContributionMenu(manager, services, viewer, city, this)
                        .open())
                .build());
    }

    // ==================================================================================
    // Moving money
    // ==================================================================================

    private Button deposit(Material material, BigDecimal amount) {
        return gated(material, text("gui.treasury.deposit", "amount", money(amount)),
                CityPermission.DEPOSIT)
                .lore(text("gui.treasury.deposit-lore"))
                .onClick(context -> doDeposit(context.player(), amount))
                .build();
    }

    private Button customDeposit() {
        return gated(Material.CHEST, text("gui.treasury.deposit-custom"),
                CityPermission.DEPOSIT)
                .onClick(context -> services.amountInput().ask(context.player(),
                        "gui.treasury.prompt-deposit",
                        amount -> doDeposit(context.player(), amount),
                        this::open))
                .build();
    }

    private Button withdraw(Material material, BigDecimal amount) {
        Button.Builder builder = gated(material,
                text("gui.treasury.withdraw", "amount", money(amount)),
                CityPermission.WITHDRAW);

        if (!city().isMayor(viewer.getUniqueId())) {
            // SPEC 8.5's cap, shown before it bites rather than after.
            builder.lore(text("gui.treasury.allowance", "amount", money(allowance())));
        }
        return builder.onClick(context -> doWithdraw(context.player(), amount)).build();
    }

    private Button customWithdraw() {
        return gated(Material.NETHERITE_INGOT, text("gui.treasury.withdraw-custom"),
                CityPermission.WITHDRAW)
                .onClick(context -> services.amountInput().ask(context.player(),
                        "gui.treasury.prompt-withdraw",
                        amount -> doWithdraw(context.player(), amount),
                        this::open))
                .build();
    }

    private void doDeposit(Player player, BigDecimal amount) {
        services.treasury().deposit(player.getUniqueId(), city(), amount)
                .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                    report(player, result, error, "gui.treasury.deposited");
                    reopen(player);
                }));
    }

    private void doWithdraw(Player player, BigDecimal amount) {
        services.treasury().withdraw(player.getUniqueId(), city(), amount)
                .whenComplete((result, error) -> services.scheduler().runOnMain(() -> {
                    report(player, result, error, "gui.treasury.withdrew");
                    reopen(player);
                }));
    }

    /**
     * Puts the screen back after a prompt or an action.
     *
     * <p>Refreshing is not enough after a chat prompt, because the prompt closed the window;
     * reopening is not right while it is still open, because it would steal the cursor. So
     * the choice depends on what the player is looking at now.
     */
    private void reopen(Player player) {
        if (manager.openMenu(player).filter(open -> open == this).isPresent()) {
            refresh();
        } else {
            open();
        }
    }

    // ==================================================================================
    // Information
    // ==================================================================================

    private Button upkeepInfo(City city) {
        BigDecimal daily = services.upkeepTask().amountFor(city);
        long runway = services.upkeep().daysOfRunway(city.treasury(), daily);

        Button.Builder builder = Button.of(Material.CLOCK, text("gui.treasury.upkeep"))
                .lore(text("gui.treasury.upkeep-daily", "amount", money(daily)))
                .lore(text("gui.treasury.upkeep-next", "when", whenDue(city)));

        if (runway == Long.MAX_VALUE) {
            builder.lore(text("gui.treasury.upkeep-no-cost"));
        } else {
            builder.lore(text("gui.treasury.upkeep-runway", "days", String.valueOf(runway)));
        }
        if (city.isDelinquent()) {
            builder.lore(text("gui.treasury.upkeep-delinquent"));
        }
        return builder.build();
    }

    private String whenDue(City city) {
        long remaining = city.upkeepDue() - System.currentTimeMillis();
        if (remaining <= 0) {
            return manager.lang().get("gui.treasury.upkeep-due-now").toString();
        }
        long hours = remaining / 3_600_000L;
        return hours <= 0 ? "<1h" : hours + "h";
    }

    /**
     * The SPEC 8.5 allowance, as of the last time it was asked for.
     *
     * <p>The real figure comes from the ledger, which is a database read and may not be done
     * on the server thread. So the draw shows the last answer and asks for a fresh one; this
     * screen is live, so the new figure appears on the next refresh a moment later. Showing a
     * number one tick old is fine here because the cap is enforced by the service, not by
     * this label.
     */
    private BigDecimal allowance() {
        services.treasury().remainingAllowance(viewer.getUniqueId(), city())
                .thenAccept(fresh -> lastAllowance = fresh);
        return lastAllowance;
    }

    private volatile BigDecimal lastAllowance = BigDecimal.ZERO;

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
}
