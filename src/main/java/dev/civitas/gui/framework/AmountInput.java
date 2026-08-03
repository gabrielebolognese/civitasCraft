package dev.civitas.gui.framework;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.civitas.config.ConfigFile;
import dev.civitas.core.economy.Money;
import dev.civitas.lang.LangManager;
import dev.civitas.util.Result;
import dev.civitas.util.Scheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * "Type an amount", SPEC 8.5.
 *
 * <p>SPEC 8.5 asks for a sign or anvil prompt for a custom deposit or withdrawal. This asks
 * in chat instead: it needs no client mod, no fake anvil inventory, and no second window to
 * desynchronise. The trade is one line of typing rather than a text field, and the answers
 * to SPEC 17.5 cases 67 and 68 are identical either way, because they are questions about
 * parsing rather than about the widget.
 *
 * <p>{@link #askText} is the same prompt without the parsing, for the places SPEC 8 asks a
 * player to type a name or a sentence rather than a number: a name is not a number and must
 * not be pushed through a money parser to find that out.
 *
 * <h2>What gets refused</h2>
 * Everything that is not a plain positive decimal, through {@link Money#parse}: letters,
 * empty input, negatives, and scientific notation, which {@link BigDecimal} would otherwise
 * read as a very large number. A refusal reopens the prompt rather than dropping the player
 * back to nothing, so a typo costs one retry.
 */
public final class AmountInput implements Listener {

    private final MenuManager menus;
    private final LangManager lang;
    private final Scheduler scheduler;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public AmountInput(MenuManager menus, LangManager lang, Scheduler scheduler) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Asks with the standard prompt. */
    public void ask(Player player, Consumer<BigDecimal> onAmount, Runnable onCancel) {
        ask(player, "gui.input.amount", onAmount, onCancel);
    }

    /**
     * Closes the player's menu and asks them to type a number.
     *
     * @param promptKey what to ask, a language key
     * @param onAmount  run on the server thread with the parsed amount
     * @param onCancel  run on the server thread if they give up or time out; typically
     *                  reopens the menu they came from
     */
    public void ask(Player player, String promptKey, Consumer<BigDecimal> onAmount,
                    Runnable onCancel) {
        Objects.requireNonNull(onAmount, "onAmount");

        menus.close(player);
        pending.put(player.getUniqueId(),
                new Pending(promptKey, onAmount, null, onCancel, System.currentTimeMillis()));

        lang.send(player, promptKey);
        lang.send(player, "gui.input.how-to-cancel",
                LangManager.placeholder("word", cancelWord()));
    }

    /**
     * Asks for a line of text rather than an amount.
     *
     * <p>Used for a player name, a MOTD or a city name. The text is trimmed and handed over
     * as typed; whatever validates it is the service that receives it, which already has to
     * validate the same string arriving from a command.
     */
    public void askText(Player player, String promptKey, Consumer<String> onText,
                        Runnable onCancel) {
        Objects.requireNonNull(onText, "onText");

        menus.close(player);
        pending.put(player.getUniqueId(),
                new Pending(promptKey, null, onText, onCancel, System.currentTimeMillis()));

        lang.send(player, promptKey);
        lang.send(player, "gui.input.how-to-cancel",
                LangManager.placeholder("word", cancelWord()));
    }

    /** Whether this player is mid-prompt, so other chat handling can leave them alone. */
    public boolean isAwaiting(Player player) {
        return pending.containsKey(player.getUniqueId()) && !expired(player.getUniqueId());
    }

    /** Abandons a prompt without running either callback. */
    public void forget(Player player) {
        pending.remove(player.getUniqueId());
    }

    // ==================================================================================
    // Capture
    // ==================================================================================

    /**
     * Takes the next thing the player says.
     *
     * <p>{@link EventPriority#LOWEST} and cancelled, so an answer never reaches public chat.
     * The event is async, so every callback is bounced onto the server thread: a menu may
     * not be opened, and a service may not be called, from a chat thread.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending waiting = pending.get(player.getUniqueId());
        if (waiting == null) {
            return;
        }
        event.setCancelled(true);

        if (expired(player.getUniqueId())) {
            pending.remove(player.getUniqueId());
            scheduler.runOnMain(() -> {
                lang.send(player, "gui.input.timed-out");
                waiting.runCancel();
            });
            return;
        }

        String typed = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();

        if (typed.equalsIgnoreCase(cancelWord())) {
            pending.remove(player.getUniqueId());
            scheduler.runOnMain(() -> {
                lang.send(player, "gui.input.cancelled");
                waiting.runCancel();
            });
            return;
        }

        if (waiting.onText() != null) {
            // A free-text prompt: nothing to parse, so nothing to refuse.
            pending.remove(player.getUniqueId());
            String answer = typed;
            scheduler.runOnMain(() -> waiting.onText().accept(answer));
            return;
        }

        Result<BigDecimal> parsed = Money.parse(typed);
        if (parsed instanceof Result.Failure<BigDecimal> failure) {
            // SPEC 17.5 cases 67 and 68: refused, with the prompt repeated rather than the
            // player dropped back to nothing.
            scheduler.runOnMain(() -> {
                lang.send(player, failure.messageKey(),
                        LangManager.placeholders(failure.placeholders()));
                lang.send(player, waiting.promptKey());
            });
            return;
        }

        pending.remove(player.getUniqueId());
        BigDecimal amount = parsed.orElseThrow();
        scheduler.runOnMain(() -> waiting.onAmount().accept(amount));
    }

    /** A player who leaves mid-prompt is not still being asked when they come back. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    // ==================================================================================
    // Helpers
    // ==================================================================================

    private boolean expired(UUID player) {
        Pending waiting = pending.get(player);
        if (waiting == null) {
            return false;
        }
        long timeout = menus.configs().get(ConfigFile.GUI)
                .getLong("input.timeout-seconds", 30) * 1000L;
        // Greater-or-equal, so a timeout of zero means "already expired" rather than
        // "expires as soon as the clock ticks", which is what an operator turning it off
        // would expect and what a test setting it to zero is asking for.
        return System.currentTimeMillis() - waiting.askedAt() >= timeout;
    }

    private String cancelWord() {
        return menus.configs().get(ConfigFile.GUI).getString("input.cancel-word", "cancel");
    }

    private record Pending(String promptKey, Consumer<BigDecimal> onAmount,
                           Consumer<String> onText, Runnable onCancel, long askedAt) {

        void runCancel() {
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }
}
