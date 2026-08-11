package dev.civitas.command.player;

import java.util.Objects;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.CivitasServices;
import dev.civitas.core.onboarding.GuideBook;
import dev.civitas.core.onboarding.StarterStep;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;

/**
 * {@code /guide}, SPEC 34.4.
 *
 * <p>"{@code /guide} reissues the book at any time." Opened rather than given, which is what M23's
 * rules book established: a virtual book cannot be lost, cannot be duplicated and takes no
 * inventory slot.
 *
 * <p>{@code /guide progress} is beyond SPEC and is recorded as such. SPEC 34.3 defines a five-step
 * chain and no way for a player to see where they are in it, and a chain whose next step is
 * invisible is a chain nobody finishes on purpose.
 */
public final class GuideCommand {

    private final Supplier<CivitasServices> services;
    private final GuideBook guide;
    private final LangManager lang;
    private final Scheduler scheduler;

    public GuideCommand(Supplier<CivitasServices> services, GuideBook guide, LangManager lang,
                        Scheduler scheduler) {
        this.services = Objects.requireNonNull(services, "services");
        this.guide = Objects.requireNonNull(guide, "guide");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("guide")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> {
                    open(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("progress")
                        .executes(context -> {
                            progress(context.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void open(Audience audience) {
        if (audience instanceof Player player) {
            player.openBook(guide.book());
            return;
        }
        // Console, or anything else with no book view. The same chapters, as lines.
        for (String key : GuideBook.PAGES) {
            lang.sendRaw(audience, key);
        }
    }

    private void progress(Audience audience) {
        if (!(audience instanceof Player player)) {
            lang.send(audience, dev.civitas.lang.Msg.COMMAND_PLAYER_ONLY);
            return;
        }
        CivitasServices current = services.get();
        if (current == null) {
            lang.send(player, "plugin.starting");
            return;
        }

        current.onboarding().completed(player.getUniqueId())
                .whenComplete((done, error) -> scheduler.runOnMain(() -> {
                    if (error != null || done == null) {
                        lang.send(player, "command.error");
                        return;
                    }
                    lang.send(player, "onboarding.progress-header",
                            LangManager.placeholder("done", String.valueOf(done.size())),
                            LangManager.placeholder("total",
                                    String.valueOf(StarterStep.values().length)));
                    for (StarterStep step : StarterStep.values()) {
                        lang.send(player, done.contains(step)
                                        ? "onboarding.progress-done"
                                        : "onboarding.progress-todo",
                                LangManager.placeholder("step",
                                        lang.plain(step.nameKey())));
                    }
                }));
    }
}
