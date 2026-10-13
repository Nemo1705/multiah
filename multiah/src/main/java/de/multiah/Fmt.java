package de.multiah;

import java.util.Locale;

/** Einheitliche Textformate: Geld und Mengen (Item/Items, Stack/Stacks). */
public final class Fmt {
    private Fmt() {}

    public static String money(long v) {
        return String.format(Locale.GERMANY, "%,d", v) + "$";
    }

    public static String amount(long n, boolean stacks) {
        String unit = stacks ? (n == 1 ? "Stack" : "Stacks") : (n == 1 ? "Item" : "Items");
        return n + " " + unit;
    }
}
