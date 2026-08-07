package dev.civitas.command.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.civitas.lang.LangManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /civitas} and {@code /civitas rules}, SPEC 9.1.
 *
 * <h2>The rules book is not decoration</h2>
 *
 * <p>Two rules in this plugin are surprising enough that SPEC requires them to be written
 * down, and names this book as the place:
 *
 * <ul>
 *   <li><b>SPEC 17.2 case 16.</b> Claiming a chunk somebody else built in is allowed —
 *       "Builds do not confer ownership. Documented in the rules book." A player who loses a
 *       build this way and was never told will read it as theft.</li>
 *   <li><b>SPEC 11.7 and SPEC 17.4 case 44.</b> War rolls every block back, but items taken
 *       out of a chest by hand are gone for good. SPEC calls this "a deliberate, explicit
 *       exception" that "must be communicated clearly to players", and spells out the line it
 *       wants them to leave with: <i>destroying storage is pointless, looting it is not.</i></li>
 * </ul>
 *
 * <p>{@code RulesBookTest} asserts both are present, so neither can be edited out of the
 * language files by accident.
 *
 * <h2>Why a real book</h2>
 *
 * <p>Opened as an Adventure {@link Book} rather than printed to chat, and <b>not</b> given as
 * an item: a virtual book cannot be lost, cannot be duplicated, and does not need an
 * inventory slot. A console sender has no book view, so it falls back to writing the same
 * pages as lines — which is also what makes the content testable without a client.
 */
public final class CivitasCommand {

    /**
     * The pages, in order. Each is one whole message key.
     *
     * <p>Declared here rather than discovered by scanning {@code rules.page-*} in the language
     * file, so a page added to {@code en.yml} and forgotten in {@code it.yml} fails
     * {@code LangKeysTest} instead of silently shortening the Italian book.
     */
    private static final List<String> PAGES = List.of(
            "rules.page-welcome",
            "rules.page-cities",
            "rules.page-land",
            "rules.page-money",
            "rules.page-war",
            "rules.page-loot",
            "rules.page-conduct");

    private final LangManager lang;
    private final Plugin plugin;

    public CivitasCommand(Plugin plugin, LangManager lang) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    /** The declared page keys, exposed so the content test can read them all. */
    public static List<String> pageKeys() {
        return PAGES;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("civitas")
                .requires(source -> source.getSender().hasPermission("civitas.use"))
                .executes(context -> about(context.getSource().getSender()))
                .then(Commands.literal("rules")
                        .executes(context -> rules(context.getSource().getSender())))
                .build();
    }

    private int about(Audience audience) {
        lang.sendRaw(audience, "civitas.about-header");
        lang.sendRaw(audience, "civitas.about-version",
                LangManager.placeholder("version", plugin.getPluginMeta().getVersion()));
        lang.sendRaw(audience, "civitas.about-help");
        lang.sendRaw(audience, "civitas.about-rules");
        return Command.SINGLE_SUCCESS;
    }

    private int rules(Audience audience) {
        if (audience instanceof Player player) {
            player.openBook(book());
            return Command.SINGLE_SUCCESS;
        }
        // Console, or anything else with no book view. The same pages, as lines.
        for (String key : PAGES) {
            lang.sendRaw(audience, key);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The rules as an Adventure book. Package-visible so the content test can read it. */
    public Book book() {
        List<Component> pages = new ArrayList<>(PAGES.size());
        for (String key : PAGES) {
            pages.add(lang.get(key));
        }
        return Book.book(lang.get("rules.title"), lang.get("rules.author"), pages);
    }
}
