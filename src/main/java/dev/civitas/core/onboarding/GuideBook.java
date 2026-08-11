package dev.civitas.core.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.civitas.lang.LangManager;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;

/**
 * SPEC 34.2 step 4's Guide Book.
 *
 * <p>"A written book, not a wall of chat. Chapters: Money, Cities, Land, War, Contests, Commands.
 * Every chapter has clickable commands. Re-obtainable with {@code /guide}."
 *
 * <p>Opened virtually rather than given as an item, which is what M23's rules book established and
 * for the same reasons: it cannot be lost, cannot be duplicated, and takes no inventory slot. SPEC
 * 34.2 does put a copy in slot 8 on first join, and that copy is a real item — the difference is
 * that losing it costs nothing, because {@code /guide} opens the same pages from nowhere.
 */
public final class GuideBook {

    /** SPEC 34.2's six chapters, in the order SPEC lists them. */
    public static final List<String> PAGES = List.of(
            "guide.page-welcome",
            "guide.page-money",
            "guide.page-cities",
            "guide.page-land",
            "guide.page-war",
            "guide.page-contests",
            "guide.page-commands");

    private final LangManager lang;

    public GuideBook(LangManager lang) {
        this.lang = Objects.requireNonNull(lang, "lang");
    }

    public Book book() {
        List<Component> pages = new ArrayList<>(PAGES.size());
        for (String key : PAGES) {
            pages.add(lang.get(key));
        }
        return Book.book(lang.get("guide.title"), lang.get("guide.author"), pages);
    }

    /** The item SPEC 34.2 puts in slot 8 on first join. */
    public org.bukkit.inventory.ItemStack item() {
        org.bukkit.inventory.ItemStack stack =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta meta =
                (org.bukkit.inventory.meta.BookMeta) stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.title(lang.get("guide.title"));
        meta.author(lang.get("guide.author"));
        List<Component> pages = new ArrayList<>(PAGES.size());
        for (String key : PAGES) {
            pages.add(lang.get(key));
        }
        meta.pages(pages);
        stack.setItemMeta(meta);
        return stack;
    }
}
