package net.kaloyan.smpworth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Keeps exactly one "Worth" line at the end of an item's lore, replacing the old one when the price changes. Pure logic. */
public final class LoreLine {

    private LoreLine() {
    }

    /** @return the text of a component without any formatting */
    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * @param price a worth
     * @return the number the way players read it: {@code 1,250}, {@code 1.5}, {@code 0.6}; never more than two decimals
     */
    public static String price(double price) {
        String s = String.format(Locale.US, "%,.2f", price);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /**
     * Builds the lore after the update.
     *
     * @param lore          the item's current lore, or {@code null}
     * @param previousPlain the plain text of the worth line this plugin added last time ({@code null} or empty if none)
     * @param line          the new worth line, or {@code null} for no worth line at all
     * @return the new lore; the old worth line is removed (the last line with exactly the remembered text) and the new one is
     *         added at the end; every other line is untouched
     */
    public static List<Component> update(List<Component> lore, String previousPlain, Component line) {
        List<Component> out = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
        if (previousPlain != null && !previousPlain.isEmpty()) {
            for (int i = out.size() - 1; i >= 0; i--) {
                if (plain(out.get(i)).equals(previousPlain)) {
                    out.remove(i);
                    break;
                }
            }
        }
        if (line != null) {
            out.add(line);
        }
        return out;
    }

    /**
     * @return {@code true} if the item already shows exactly this worth line, so nothing needs to be written
     */
    public static boolean upToDate(List<Component> lore, String previousPlain, Component line) {
        if (line == null) {
            return previousPlain == null || previousPlain.isEmpty();
        }
        String wanted = plain(line);
        return wanted.equals(previousPlain) && lore != null && !lore.isEmpty() && plain(lore.get(lore.size() - 1)).equals(wanted);
    }
}
