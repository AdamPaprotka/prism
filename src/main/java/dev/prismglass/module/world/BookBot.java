package dev.prismglass.module.world;

import dev.prismglass.module.Category;
import dev.prismglass.module.Module;
import dev.prismglass.setting.*;
import dev.prismglass.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * Fills every empty book and quill in the inventory. The edit packet works on any hotbar slot (or the
 * offhand), so books are written without selecting them; books in the main inventory are swapped into
 * a free hotbar slot first. Lengths stay inside the vanilla packet limits (100 pages of 1024 chars,
 * title 32), which the server enforces anyway.
 */
public class BookBot extends Module {
    public final ModeSetting mode = mode("Mode", "Letter", "Letter: one character repeated. Word: a word repeated with spaces. Text: your text repeated. Random: random letters and digits.", "Letter", "Word", "Text", "Random");
    public final TextSetting text = text("Text", "a", "The letter, word or text to write.");
    public final NumberSetting pages = num("Pages", 50, 1, WritableBookContent.MAX_PAGES, 1, "Pages per book.");
    public final NumberSetting pageChars = num("PageChars", 256, 1, WritableBookContent.PAGE_EDIT_LENGTH, 1, "Characters per page (vanilla's editor fits about 256 short characters).");
    public final BoolSetting sign = bool("Sign", true, "Sign the books (book and quill -> written book).");
    public final TextSetting title = text("Title", "Prism", "Title of signed books (max 32 characters).");
    public final BoolSetting number = bool("Number", true, "Add #1, #2... to the title.");
    public final NumberSetting delay = num("Delay", 5, 1, 40, 1, "Ticks between books.");
    public final NumberSetting count = num("Count", 0, 0, 256, 1, "Stop after this many books (0 = every book you have).");

    private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final Random random = new Random();
    private int written, wait, pendingSlot = -1, pendingTicks;

    public BookBot() { super("BookBot", "Writes books full of a letter, word or text.", Category.WORLD); }

    @Override public void onEnable() { written = 0; wait = 0; pendingSlot = -1; }

    @Override public String getInfo() { return String.valueOf(written); }

    @Override
    public void onTick() {
        if (wait > 0) { wait--; return; }
        if (count.getInt() > 0 && written >= count.getInt()) { done(); return; }
        // the last book hasn't synced back yet: don't write it twice
        if (pendingSlot != -1) {
            if (isBlank(slotStack(pendingSlot)) && ++pendingTicks < 40) return;
            pendingSlot = -1;
        }
        int slot = blankBookSlot();
        if (slot == -1) {
            int inv = InvUtil.findInventory(s -> s.is(Items.WRITABLE_BOOK) && isBlank(s));
            if (inv == -1) { done(); return; }
            int free = freeHotbar();
            if (free == -1) { ChatUtil.error("BookBot: no free hotbar slot"); toggle(); return; }
            if (!InvUtil.canClick()) return;
            InvUtil.swapWithHotbar(inv, free);
            wait = 1;
            return;
        }
        List<String> content = new ArrayList<>();
        for (int i = 0; i < pages.getInt(); i++) content.add(page());
        Optional<String> t = Optional.empty();
        if (sign.get()) {
            String base = title.get().isBlank() ? "Book" : title.get();
            String suffix = number.get() ? " #" + (written + 1) : "";
            t = Optional.of(cut(base, 32 - suffix.length()) + suffix);
        }
        mc.player.connection.send(new ServerboundEditBookPacket(slot, content, t));
        written++;
        pendingSlot = slot;
        pendingTicks = 0;
        wait = delay.getInt();
    }

    private String page() {
        int len = pageChars.getInt();
        String src = text.get().isEmpty() ? "a" : text.get();
        StringBuilder sb = new StringBuilder(len);
        switch (mode.get()) {
            case "Letter" -> {
                int cp = src.codePointAt(0);
                while (sb.length() + Character.charCount(cp) <= len) sb.appendCodePoint(cp);
            }
            case "Word" -> {
                String word = src.trim().isEmpty() ? "a" : src.trim();
                while (sb.length() + word.length() <= len) {
                    sb.append(word);
                    if (sb.length() < len) sb.append(' ');
                }
                if (sb.isEmpty()) sb.append(cut(word, len));
            }
            case "Text" -> {
                while (sb.length() < len) sb.append(src);
                return cut(sb.toString(), len);
            }
            default -> {
                for (int i = 0; i < len; i++) sb.append(RANDOM_CHARS.charAt(random.nextInt(RANDOM_CHARS.length())));
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Cuts to at most {@code max} UTF-16 chars without splitting a surrogate pair. */
    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    private void done() {
        ChatUtil.good("BookBot: wrote " + written + " book" + (written == 1 ? "" : "s"));
        toggle();
    }

    private static boolean isBlank(ItemStack s) {
        if (!s.is(Items.WRITABLE_BOOK)) return false;
        WritableBookContent c = s.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return c == null || c.pages().stream().allMatch(p -> p.raw().isEmpty());
    }

    private static ItemStack slotStack(int slot) {
        return slot == 40 ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    private int blankBookSlot() {
        for (int i = 0; i < 9; i++) if (i != pendingSlot && isBlank(slotStack(i))) return i;
        return pendingSlot != 40 && isBlank(slotStack(40)) ? 40 : -1;
    }

    private static int freeHotbar() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        return -1;
    }
}
