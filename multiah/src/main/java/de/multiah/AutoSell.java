package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prüft alle 2 Sekunden das Inventar (kein Tick-für-Tick-Scan, keine Events)
 * und startet bei erreichter Mindestmenge einen Verkaufsauftrag.
 *
 *  - /sell:  ALLES von diesem Item kommt ins /sell-Fenster (egal wie viele Stacks oder Items)
 *  - /ah:    verkauft, bis nichts mehr davon im Inventar ist; die Meldung mit dem Gesamtwert
 *            kommt erst am Ende (oder wenn du stoppst)
 */
public final class AutoSell {
    private static final int CHECK_INTERVAL = 40;   // alle 2 Sekunden prüfen
    private static final int BACKOFF_TICKS = 1200;  // 1 Minute Pause nach einem Fehler
    private static final int MAX_ABORTS = 3;

    private static int timer = 0;
    private static int backoff = 0;
    private static int aborts = 0;

    private AutoSell() {}

    /** Nächster Check sofort (nach Einstellungs-Änderung). */
    public static void poke() {
        timer = 0;
    }

    /** Alles auf Anfang (nach "Neu laden"). */
    public static void reset() {
        timer = 0;
        backoff = 0;
        aborts = 0;
    }

    public static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** Darf dieser Stack für den Eintrag automatisch verkauft werden? */
    public static boolean eligible(Config.Entry e, ItemStack s) {
        if (s.isEmpty() || !id(s).equals(e.id)) return false;
        // keine verzauberten oder beschädigten Items
        if (s.isEnchanted() || s.isDamaged()) return false;
        // /sell nimmt alles; /ah mit Einheit "Stacks" nur volle Stacks
        return e.isSell() || !e.perStack || s.getCount() == s.getMaxStackSize();
    }

    public static long salePrice(Config.Entry e, ItemStack s) {
        if (!e.perStack) return Math.max(1L, e.price * s.getCount());
        int max = Math.max(1, s.getMaxStackSize());
        if (s.getCount() == max) return e.price;
        return Math.max(1L, Math.round((double) e.price * s.getCount() / max));
    }

    private static String command(Config.Entry e, ItemStack s) {
        return "ah sell " + salePrice(e, s);
    }

    /** Alle passenden Items im Inventar (einzelne Items gezählt). */
    public static long totalItems(Config.Entry e, LocalPlayer p) {
        long items = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (eligible(e, s)) items += s.getCount();
        }
        return items;
    }

    /** Verfügbare Menge in der Einheit des Eintrags (Stacks oder Items). */
    public static long available(Config.Entry e, LocalPlayer p) {
        long items = 0;
        long stacks = 0;
        int max = 64;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (eligible(e, s)) {
                items += s.getCount();
                stacks++;
                max = Math.max(1, s.getMaxStackSize());
            }
        }
        if (!e.perStack) return items;
        return e.isSell() ? items / max : stacks;
    }

    /** Aktueller Bestand im Inventar: {Menge in der Einheit des Eintrags, Wert in $}. */
    public static long[] holding(Config.Entry e, LocalPlayer p) {
        long amount = available(e, p);
        return new long[] {amount, Math.max(0, amount * e.price)};
    }

    public static void tick(Minecraft mc) {
        Config cfg = Config.get();
        if (!cfg.autoSellEnabled || Seller.running || Seller.guiOpen) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;
        if (backoff > 0) {
            backoff--;
            return;
        }
        if (timer-- > 0) return;
        timer = CHECK_INTERVAL;
        if (p.containerMenu != p.inventoryMenu || !p.containerMenu.getCarried().isEmpty()) return;

        List<Config.Entry> sorted = new ArrayList<>(cfg.entries);
        sorted.sort(Comparator.comparingInt((Config.Entry e) -> e.priority).reversed());
        for (Config.Entry e : sorted) {
            if (!e.enabled) continue;
            if (e.isSell()) {
                if (cfg.sellCommand == null || cfg.sellCommand.isBlank()) continue;
            } else if (e.price <= 0) {
                continue; // AH braucht einen Preis
            }
            long min = Math.max(1, e.minAmount);
            if (available(e, p) >= min) {
                startSale(e, true, false);
                return;
            }
        }
    }

    /** Knopf "Jetzt verkaufen": /sell = alles; /ah = genau die eingestellte Menge, einmalig. */
    public static void sellNow(Config.Entry e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (e.isSell()) {
            if (totalItems(e, p) == 0) {
                Seller.msg(p, "§cDu hast nichts davon im Inventar.");
                return;
            }
            startSale(e, false, true);
            return;
        }
        if (e.price <= 0) {
            Seller.msg(p, "§cKein Preis gesetzt.");
            return;
        }
        long min = Math.max(1, e.minAmount);
        long av = available(e, p);
        if (av < min) {
            Seller.msg(p, "§cZu wenig im Inventar: " + Fmt.amount(av, e.perStack)
                + " vorhanden, eingestellt sind " + Fmt.amount(min, e.perStack) + ".");
            return;
        }
        startSale(e, false, true);
    }

    /**
     * auto: von Auto-Sell gestartet (bei Stopp/Fehler wird Auto-Sell ausgeschaltet).
     * exact: nur die eingestellte Menge einmalig (nur /ah) statt alles bis nichts mehr da ist.
     */
    private static void startSale(Config.Entry e, boolean auto, boolean exact) {
        long min = Math.max(1, e.minAmount);
        Seller.Job job = new Seller.Job(s -> command(e, s), s -> e.price > 0 ? salePrice(e, s) : -1L);
        String verb = e.isSell() ? "verkauft" : "zum Verkauf eingestellt";
        Seller.Done done = !auto ? null : result -> {
            Config cfg = Config.get();
            if (result == Seller.Result.OK) {
                aborts = 0;
                return;
            }
            backoff = BACKOFF_TICKS;
            LocalPlayer p = Minecraft.getInstance().player;
            if (result == Seller.Result.CANCELLED) {
                // Gestoppt = Auto-Sell wirklich aus, es startet nichts von selbst neu
                cfg.autoSellEnabled = false;
                Config.save();
                if (p != null) Seller.msg(p, "§eAuto-Sell wurde gestoppt (AUS).");
            } else {
                aborts++;
                if (aborts >= MAX_ABORTS) {
                    cfg.autoSellEnabled = false;
                    Config.save();
                    aborts = 0;
                    if (p != null) Seller.msg(p, "§cAuto-Sell wurde nach mehreren Fehlern AUSGESCHALTET.");
                }
            }
        };
        if (e.isSell()) {
            // /sell: Fenster öffnen, ALLES hineinlegen, Knopf unten rechts klicken
            Seller.startGui(e.name, s -> eligible(e, s), s -> e.price > 0 ? salePrice(e, s) : -1L,
                !e.perStack, verb, true, done);
        } else if (e.perStack) {
            Seller.startWhole(e.name, s -> eligible(e, s), job, exact ? min : Long.MAX_VALUE, verb, false, done);
        } else {
            Seller.startItems(e.name, s -> eligible(e, s), job, exact ? min : Long.MAX_VALUE,
                (int) Math.min(min, Integer.MAX_VALUE), verb, false, done);
        }
    }

    public static void status() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        Config cfg = Config.get();
        Seller.msg(p, "Auto-Sell: " + (cfg.autoSellEnabled ? "AN" : "AUS")
            + " | Fenster offen: " + Seller.guiOpen
            + " | Verkauf läuft: " + Seller.running);
        for (Config.Entry e : cfg.entries) {
            Seller.msg(p, e.name + ": " + (e.enabled ? "aktiv" : "AUS")
                + ", " + (e.isSell() ? "/sell" : "/ah")
                + ", Preis " + e.price
                + ", gefunden " + Fmt.amount(available(e, p), e.perStack)
                + ", nötig " + Fmt.amount(e.minAmount, e.perStack));
        }
    }
}
