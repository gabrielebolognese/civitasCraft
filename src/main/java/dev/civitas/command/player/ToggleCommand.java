package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.command.Replies;
import dev.civitas.lang.LangManager;
import dev.civitas.lang.Msg;
import dev.civitas.msg.ToggleCategory;
import dev.civitas.msg.TogglePreferences;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /toggle}, SPEC 22.6.
 *
 * <p>SPEC 22.1 rates this a <b>High</b> severity omission from Part I: "Section 23 adds many
 * messages. Without a toggle, chat becomes unusable." A preference store nobody can drive would
 * be the same omission wearing a different hat, which is why this ships with the store rather
 * than waiting for the command-completeness pass.
 *
 * <p>The four SPEC 23.6 locks are refused by {@link TogglePreferences#set}, not here. A guard in
 * a command is a guard one GUI away from being bypassed, and one of the four is the plugin's
 * primary anti-fraud mechanism.
 */
public final class ToggleCommand {

    private final Supplier<CivitasServices> services;
    private final LangManager lang;
    private final Scheduler scheduler;

    public ToggleCommand(Supplier<CivitasServices> services, LangManager lang,
                         Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("toggle")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    lang.send(context.getSource().getSender(), "toggle.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("list")
                        .executes(context -> withPlayer(context, this::list)))
                .then(Commands.argument("category", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            // Only the categories a player can actually change. Suggesting a
                            // locked one would be offering something that always refuses.
                            ToggleCategory.mutable().stream()
                                    .map(ToggleCategory::key)
                                    .filter(key -> key.startsWith(
                                            builder.getRemaining().toLowerCase(
                                                    java.util.Locale.ROOT)))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(context -> withPlayer(context, player ->
                                flip(player, StringArgumentType.getString(context, "category"))))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    builder.suggest("on");
                                    builder.suggest("off");
                                    return builder.buildFuture();
                                })
                                .executes(context -> withPlayer(context, player -> set(player,
                                        StringArgumentType.getString(context, "category"),
                                        StringArgumentType.getString(context, "state"))))))
                .build();
    }

    // ==================================================================================
    // Actions
    // ==================================================================================

    private void list(Player player) {
        TogglePreferences toggles = services.get().toggles();
        lang.sendRaw(player, "toggle.header");
        toggles.all(player.getUniqueId()).forEach((category, enabled) -> {
            if (category.locked()) {
                lang.sendRaw(player, "toggle.entry-locked",
                        Replies.p("category", lang.plain(category.messageKey())));
                return;
            }
            lang.sendRaw(player, "toggle.entry",
                    Replies.p("category", lang.plain(category.messageKey())),
                    LangManager.component("state",
                            lang.get(enabled ? "toggle.state-on" : "toggle.state-off")));
        });
    }

    private void flip(Player player, String typed) {
        resolve(player, typed).ifPresent(category ->
                apply(player, category, services.get().toggles()
                        .toggle(player.getUniqueId(), category)));
    }

    private void set(Player player, String typed, String state) {
        resolve(player, typed).ifPresent(category -> {
            boolean enabled = state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true");
            apply(player, category, services.get().toggles()
                    .set(player.getUniqueId(), category, enabled));
        });
    }

    private void apply(Player player, ToggleCategory category,
                       java.util.concurrent.CompletableFuture<Result<Boolean>> pending) {
        pending.whenComplete((result, error) -> scheduler.runOnMain(() -> {
            if (error != null) {
                lang.send(player, "command.error");
                return;
            }
            if (result instanceof Result.Failure<Boolean> failure) {
                Replies.sendFailure(player, lang, failure);
                return;
            }
            lang.send(player, result.orElseThrow() ? "toggle.set-on" : "toggle.set-off",
                    Replies.p("category", lang.plain(category.messageKey())));
        }));
    }

    // ==================================================================================
    // Plumbing
    // ==================================================================================

    private java.util.Optional<ToggleCategory> resolve(Player player, String typed) {
        java.util.Optional<ToggleCategory> category = ToggleCategory.byKey(typed);
        if (category.isEmpty()) {
            lang.send(player, "toggle.unknown", Replies.p("category", typed));
        }
        return category;
    }

    private int withPlayer(CommandContext<CommandSourceStack> context,
                           java.util.function.Consumer<Player> action) {
        Audience audience = context.getSource().getSender();
        if (services.get() == null) {
            lang.send(audience, "plugin.starting");
            return Command.SINGLE_SUCCESS;
        }
        if (!(context.getSource().getSender() instanceof Player player)) {
            lang.send(audience, Msg.COMMAND_PLAYER_ONLY);
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
