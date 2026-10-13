package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Gemeinsamer Verkaufs-Baustein für /ahmulti und Auto-Sell.
 *
 * Drei Arten von Aufträgen:
 *  - startWhole: verkauft ganze Stacks per Befehl (/ah sell), höchstens die angegebene Anzahl
 *  - startItems: verkauft genau die angegebene Anzahl einzelner Items per Befehl (/ah sell);
 *                Stacks werden dafür automatisch aufgeteilt, der Rest bleibt im Inventar
 *  - startGui:   verkauft über das /sell-Fenster: Befehl senden, Items ins Fenster legen,
 *                den Knopf unten rechts klicken, Fenster schließen
 *
 * Es gibt keine Timer/Threads, nur diesen einen Zustand. Ein Stop (oder das Ende) setzt alles sofort zurück,
 * danach läuft garantiert nichts mehr weiter.
 */
public final class Seller {
    public enum Result { OK, FAILED, CANCELLED }

    /** command: kompletter Befehl ohne Schrägstrich. value: Verkaufswert des Stacks (-1 = unbekannt). */
    public record Job(Function<ItemStack, String> command, ToLongFunction<ItemStack> value) {}

    public interface Done { void finished(Result result); }

    private enum G { START, OPEN_WAIT, FILL, CLOSE_WAIT, SETTLE }

    private static final int MAX_FAILURES = 4;
    private static final int BLOCK_LIMIT = 200;   // 10 Sekunden blockiert -> abbrechen statt hängen
    private static final int CLICKS_PER_TICK = 2; // Aufteilen/Einsortieren ohne Serverspam
    private static final int SETTLE_TICKS = 4;    // kurz auf die Server-Bestätigung warten

    public static boolean running = false;
    public static boolean guiOpen = false;

    private static Predicate<ItemStack> matcher;
    private static Job job;
    private static Done done;
    private static String label = "";
    private static String verb = "zum Verkauf eingestellt";
    private static boolean estimated = false;
    private static boolean itemsMode = false;
    private static boolean guiMode = false;
    private static long remaining = 0; // Stacks (whole) bzw. Items (items), die noch verkauft werden sollen

    private static int cooldown;
    private static int failures;
    private static int pendingCount;
    private static long pendingValue;
    private static boolean verifyPending;
    private static boolean retryHand;
    private static int blocked;
    private static int settle;
    private static int planLot;
    private static int splitSrcScreen = -1;
    private static int clickContainerId;
    private static final ArrayDeque<int[]> clicks = new ArrayDeque<>(); // {slot, button, 0=PICKUP|1=SWAP}

    private static ItemStack savedHand = ItemStack.EMPTY;
    private static int origSlot;

    // /sell-Fenster
    private static G gstate = G.START;
    private static int gwait;
    private static int gopen;
    private static int guiId;
    private static int guiSlots;
    private static long beforeTotal;
    private static long plannedItems;
    private static int plannedStacks;
    private static long plannedValue;

    private static int soldStacks;
    private static long soldItems;
    private static long totalValue;
    private static boolean valueKnown;

    private Seller() {}

    /** Verkauft ganze Stacks per Befehl, höchstens maxStacks Stück. */
    public static void startWhole(String label, Predicate<ItemStack> matcher, Job job, long maxStacks,
                                  String verb, boolean estimated, Done done) {
        init(label, matcher, job, false, false, maxStacks, verb, estimated, done);
    }

    /** Verkauft genau totalItems einzelne Items per Befehl (teilt Stacks bei Bedarf automatisch auf). */
    public static void startItems(String label, Predicate<ItemStack> matcher, Job job, long totalItems,
                                  String verb, boolean estimated, Done done) {
        init(label, matcher, job, true, false, totalItems, verb, estimated, done);
    }

    /** Verkauft über das /sell-Fenster. units = Stacks (itemsMode=false) oder Items (itemsMode=true). */
    public static void startGui(String label, Predicate<ItemStack> matcher, ToLongFunction<ItemStack> value,
                                boolean itemsMode, long units, String verb, boolean estimated, Done done) {
        init(label, matcher, new Job(s -> "", value), itemsMode, true, units, verb, estimated, done);
    }

    private static void init(String label, Predicate<ItemStack> matcher, Job job, boolean itemsMode,
                             boolean guiMode, long remaining, String verb, boolean estimated, Done done) {
        if (running) finish(Result.CANCELLED, null);
        Seller.label = label;
        Seller.matcher = matcher;
        Seller.job = job;
        Seller.itemsMode = itemsMode;
        Seller.guiMode = guiMode;
        Seller.remaining = remaining;
        Seller.verb = verb;
        Seller.estimated = estimated;
        Seller.done = done;
        soldStacks = 0;
        soldItems = 0;
        totalValue = 0;
        valueKnown = false;
        failures = 0;
        cooldown = 0;
        blocked = 0;
        settle = 0;
        gstate = G.START;
        gwait = 0;
        gopen = 0;
        verifyPending = false;
        retryHand = false;
        clicks.clear();
        splitSrcScreen = -1;
        savedHand = ItemStack.EMPTY;
        LocalPlayer p = Minecraft.getInstance().player;
        origSlot = p != null ? p.getInventory().getSelectedSlot() : 0;
        running = true;
    }

    /** Beendet sofort alles. Gibt false zurück, wenn nichts lief. */
    public static boolean stop() {
        if (!running) return false;
        finish(Result.CANCELLED, "§eVerkauf gestoppt.");
        return true;
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.getConnection() == null) {
            running = false;
            verifyPending = false;
            clicks.clear();
            settle = 0;
            done = null;
            return;
        }
        if (guiMode) {
            guiTick(mc, p);
            return;
        }
        // Kisten-/Container-Menü offen: warten, aber nicht ewig
        if (p.containerMenu != p.inventoryMenu) {
            if (++blocked > BLOCK_LIMIT) finish(Result.FAILED, "§cEin Menü ist offen - Verkauf abgebrochen.");
            return;
        }

        // 1. Klick-Folge fürs Aufteilen (wenige Klicks pro Tick)
        if (!clicks.isEmpty()) {
            runClicks(mc, p);
            return;
        }
        // 2. Kurz warten, dann prüfen ob genau die geplante Menge in der Hand liegt
        if (settle > 0) {
            if (--settle > 0) return;
            ItemStack h = p.getMainHandItem();
            if (!p.containerMenu.getCarried().isEmpty() || h.isEmpty() || !matcher.test(h)
                || h.getCount() != planLot) {
                finish(Result.FAILED, "§cAufteilen hat nicht geklappt - abgebrochen, nichts verkauft.");
                return;
            }
            sell(mc, h.copy());
            return;
        }

        if (!p.containerMenu.getCarried().isEmpty()) {
            if (++blocked > BLOCK_LIMIT) {
                finish(Result.FAILED, "§cEin Item hängt am Mauszeiger - Verkauf abgebrochen.");
            }
            return;
        }
        blocked = 0;
        if (cooldown-- > 0) return;

        int selected = p.getInventory().getSelectedSlot();
        ItemStack hand = p.getMainHandItem();

        // Hat der letzte Verkauf geklappt? (der Stack muss aus der Hand verschwunden sein)
        if (verifyPending) {
            verifyPending = false;
            if (!hand.isEmpty() && matcher.test(hand) && hand.getCount() == pendingCount) {
                failures++;
                if (failures >= MAX_FAILURES) {
                    finish(Result.FAILED, "§cDer Server hat den Verkauf nicht angenommen - abgebrochen. "
                        + "Lies im Chat die Antwort des Servers (z.B. AH-Limit).");
                    return;
                }
                retryHand = true;
                cooldown = Config.get().delayTicks * (1 << failures); // je Fehlversuch länger warten
                return;
            }
            failures = 0;
            soldStacks++;
            soldItems += pendingCount;
            if (pendingValue >= 0) {
                totalValue += pendingValue;
                valueKnown = true;
            }
            remaining -= itemsMode ? pendingCount : 1;
            if (remaining <= 0) {
                finish(Result.OK, null);
                return;
            }
        }

        hand = p.getMainHandItem();
        if (retryHand) {
            retryHand = false;
            if (!hand.isEmpty() && matcher.test(hand)) {
                sell(mc, hand.copy());
                return;
            }
        }
        if (itemsMode) itemsStep(mc, p, selected, hand);
        else wholeStep(mc, p, selected, hand);
    }

    private static void runClicks(Minecraft mc, LocalPlayer p) {
        for (int k = 0; k < CLICKS_PER_TICK && !clicks.isEmpty(); k++) {
            int[] c = clicks.poll();
            mc.gameMode.handleContainerInput(clickContainerId, c[0], c[1],
                c[2] == 0 ? ContainerInput.PICKUP : ContainerInput.SWAP, p);
        }
        if (clicks.isEmpty()) settle = SETTLE_TICKS;
    }

    // ------------------------------------------------------------------
    // Verkauf per Befehl (/ah sell ...)
    // ------------------------------------------------------------------

    /** Ganze Stacks: Hand passt -> verkaufen, sonst nächsten passenden Stack in die Hand tauschen. */
    private static void wholeStep(Minecraft mc, LocalPlayer p, int selected, ItemStack hand) {
        if (!hand.isEmpty() && matcher.test(hand)) {
            sell(mc, hand.copy());
            return;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (i == selected || s.isEmpty() || !matcher.test(s)) continue;
            ItemStack target = s.copy();
            if (savedHand.isEmpty() && !hand.isEmpty()) savedHand = hand.copy();
            swapWithHotbar(mc, p, i, selected);
            sell(mc, target);
            return;
        }
        finish(Result.OK, null); // nichts Passendes mehr
    }

    /** Einzelne Items: genau "lot" Items bereitstellen (Stack aufteilen, wenn nötig) und verkaufen. */
    private static void itemsStep(Minecraft mc, LocalPlayer p, int selected, ItemStack hand) {
        int cap = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && matcher.test(s)) cap = Math.max(cap, s.getMaxStackSize());
        }
        if (cap == 0) {
            finish(Result.OK, null); // nichts Passendes mehr
            return;
        }
        int lot = (int) Math.min(remaining, cap);

        // Kleinster Stack, der mindestens "lot" Items hat (bevorzugt exakt passend)
        int src = -1;
        int srcCount = Integer.MAX_VALUE;
        int largest = -1;
        int largestCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !matcher.test(s)) continue;
            int c = s.getCount();
            if (c >= lot && c < srcCount) {
                src = i;
                srcCount = c;
            }
            if (c > largestCount) {
                largest = i;
                largestCount = c;
            }
        }
        if (src == -1) { // alle Stacks kleiner als lot -> den größten ganz verkaufen
            src = largest;
            srcCount = largestCount;
            lot = largestCount;
        }

        // Exakt passender Stack: einfach in die Hand holen und verkaufen
        if (srcCount == lot) {
            ItemStack target = p.getInventory().getItem(src).copy();
            if (src != selected) {
                if (savedHand.isEmpty() && !hand.isEmpty()) savedHand = hand.copy();
                swapWithHotbar(mc, p, src, selected);
            }
            sell(mc, target);
            return;
        }

        // Aufteilen: Stack hat mehr Items als verkauft werden sollen
        clickContainerId = p.inventoryMenu.containerId;
        int hs = 36 + selected;
        if (!hand.isEmpty()) {
            int empty = findEmpty(p, selected);
            if (empty == -1) {
                finish(Result.FAILED, "§cKein freier Inventarplatz zum Aufteilen - abgebrochen.");
                return;
            }
            if (!matcher.test(hand) && savedHand.isEmpty()) savedHand = hand.copy();
            clicks.add(new int[] {screenSlot(empty), selected, 1}); // Hand-Item auf freien Platz tauschen
            if (src == selected) src = empty;
        }
        int ss = screenSlot(src);
        splitSrcScreen = ss;
        addLotClicks(ss, srcCount, lot, hs);
        planLot = lot;
    }

    /** Klicks, um genau "lot" Items aus einem Stack mit "srcCount" Items in einen leeren Ziel-Slot zu legen. */
    private static void addLotClicks(int srcSlot, int srcCount, int lot, int destSlot) {
        if (lot == srcCount) {
            clicks.add(new int[] {srcSlot, 0, 0});
            clicks.add(new int[] {destSlot, 0, 0});
        } else if (lot == (srcCount + 1) / 2) {
            // Rechtsklick nimmt die (aufgerundete) Hälfte
            clicks.add(new int[] {srcSlot, 1, 0});
            clicks.add(new int[] {destSlot, 0, 0});
        } else if (srcCount - lot < lot) {
            // Aufnehmen, den Rest einzeln zurücklegen, die gewünschte Menge ablegen
            clicks.add(new int[] {srcSlot, 0, 0});
            for (int k = 0; k < srcCount - lot; k++) clicks.add(new int[] {srcSlot, 1, 0});
            clicks.add(new int[] {destSlot, 0, 0});
        } else {
            // Aufnehmen, gewünschte Menge einzeln ablegen, Rest zurück in den Stack
            clicks.add(new int[] {srcSlot, 0, 0});
            for (int k = 0; k < lot; k++) clicks.add(new int[] {destSlot, 1, 0});
            clicks.add(new int[] {srcSlot, 0, 0});
        }
    }

    private static void sell(Minecraft mc, ItemStack stack) {
        mc.getConnection().sendCommand(job.command().apply(stack));
        pendingCount = stack.getCount();
        pendingValue = job.value().applyAsLong(stack);
        verifyPending = true;
        cooldown = Config.get().delayTicks;
    }

    // ------------------------------------------------------------------
    // Verkauf über das /sell-Fenster
    // ------------------------------------------------------------------

    private static long countMatching(LocalPlayer p) {
        long n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && matcher.test(s)) n += s.getCount();
        }
        return n;
    }

    private static int menuSlotOfInv(int i) {
        return i < 9 ? guiSlots + 27 + i : guiSlots + (i - 9); // Spieler-Inventar steht hinter dem Fenster
    }

    private static void guiTick(Minecraft mc, LocalPlayer p) {
        switch (gstate) {
            case START -> {
                if (cooldown-- > 0) return;
                if (p.containerMenu != p.inventoryMenu || !p.containerMenu.getCarried().isEmpty()) {
                    if (++blocked > BLOCK_LIMIT) {
                        finish(Result.FAILED, "§cEin Menü ist offen oder ein Item hängt am Mauszeiger - abgebrochen.");
                    }
                    return;
                }
                blocked = 0;
                beforeTotal = countMatching(p);
                if (remaining <= 0 || beforeTotal == 0) {
                    finish(Result.OK, null);
                    return;
                }
                String cmd = Config.get().sellCommand;
                if (cmd == null || cmd.isBlank()) {
                    finish(Result.FAILED, "§cKein Befehl für /sell eingestellt (Reiter Einstellungen).");
                    return;
                }
                mc.getConnection().sendCommand(cmd);
                gstate = G.OPEN_WAIT;
                gwait = 0;
                gopen = 0;
            }
            case OPEN_WAIT -> {
                if (p.containerMenu != p.inventoryMenu && p.containerMenu.slots.size() >= 45) {
                    if (++gopen < 4) return; // kurz warten, bis der Server den Inhalt geschickt hat
                    guiId = p.containerMenu.containerId;
                    guiSlots = p.containerMenu.slots.size() - 36;
                    if (!planGui(p)) {
                        finish(Result.FAILED, "§cKein Platz im /sell-Fenster oder keine passenden Items - abgebrochen.");
                        return;
                    }
                    gstate = G.FILL;
                    return;
                }
                if (++gwait > 60) {
                    finish(Result.FAILED,
                        "§cDas /sell-Fenster hat sich nicht geöffnet. Prüfe den Befehl unter Einstellungen.");
                }
            }
            case FILL -> {
                if (p.containerMenu.containerId != guiId) {
                    finish(Result.FAILED, "§c/sell-Fenster wurde geschlossen - abgebrochen.");
                    return;
                }
                if (!clicks.isEmpty()) {
                    runClicks(mc, p);
                    return;
                }
                if (settle > 0) {
                    settle--;
                    return;
                }
                if (!p.containerMenu.getCarried().isEmpty()) {
                    finish(Result.FAILED, "§cEin Item hängt am Mauszeiger - abgebrochen, nichts verkauft.");
                    return;
                }
                // Genau die geplante Menge muss im Fenster liegen, sonst NICHT bestätigen
                long placed = 0;
                for (int k = 0; k < guiSlots - 1; k++) {
                    ItemStack s = p.containerMenu.getSlot(k).getItem();
                    if (!s.isEmpty() && matcher.test(s)) placed += s.getCount();
                }
                if (placed != plannedItems) {
                    finish(Result.FAILED, "§cInhalt des /sell-Fensters stimmt nicht - abgebrochen, nichts verkauft.");
                    return;
                }
                // Knopf unten rechts (letzter Slot des Fensters) klicken
                mc.gameMode.handleContainerInput(guiId, guiSlots - 1, 0, ContainerInput.PICKUP, p);
                gstate = G.CLOSE_WAIT;
                gwait = 0;
            }
            case CLOSE_WAIT -> {
                if (p.containerMenu == p.inventoryMenu) {
                    gstate = G.SETTLE;
                    gwait = 0;
                    return;
                }
                gwait++;
                if (gwait == 40) p.closeContainer(); // Fenster selbst schließen, übrige Items kommen zurück
                if (gwait > 100) finish(Result.FAILED, "§cDas /sell-Fenster lässt sich nicht schließen - abgebrochen.");
            }
            case SETTLE -> {
                if (++gwait < 8) return;
                long sold = beforeTotal - countMatching(p);
                if (sold <= 0) {
                    failures++;
                    if (failures >= MAX_FAILURES) {
                        finish(Result.FAILED, "§cDer Server hat den Verkauf nicht angenommen - abgebrochen. "
                            + "Lies im Chat die Antwort des Servers.");
                        return;
                    }
                    cooldown = Config.get().delayTicks * (1 << failures);
                    gstate = G.START;
                    return;
                }
                failures = 0;
                long units;
                if (itemsMode) {
                    units = Math.min(sold, remaining);
                } else {
                    units = plannedItems > 0 ? Math.min(plannedStacks, Math.max(1, Math.round(
                        (double) sold / plannedItems * plannedStacks))) : 1;
                }
                soldItems += sold;
                soldStacks += (int) units;
                if (plannedValue >= 0) {
                    totalValue += sold >= plannedItems ? plannedValue : plannedValue * sold / Math.max(1, plannedItems);
                    valueKnown = true;
                }
                remaining -= units;
                if (remaining <= 0) {
                    finish(Result.OK, null);
                    return;
                }
                cooldown = Config.get().delayTicks;
                gstate = G.START;
            }
        }
    }

    /** Plant, welche Items in welche freien Slots des /sell-Fensters gelegt werden. */
    private static boolean planGui(LocalPlayer p) {
        int[] cnt = new int[36];
        int cap = 0;
        ItemStack sample = ItemStack.EMPTY;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && matcher.test(s)) {
                cnt[i] = s.getCount();
                cap = Math.max(cap, s.getMaxStackSize());
                if (sample.isEmpty()) sample = s;
            }
        }
        if (cap == 0) return false;

        List<Integer> dests = new ArrayList<>();
        for (int k = 0; k < guiSlots - 1; k++) { // der letzte Slot ist der Verkaufs-Knopf
            if (p.containerMenu.getSlot(k).getItem().isEmpty()) dests.add(k);
        }

        clickContainerId = guiId;
        clicks.clear();
        plannedItems = 0;
        plannedStacks = 0;
        plannedValue = 0;
        boolean valueOk = true;
        long left = remaining;
        int di = 0;
        while (left > 0 && di < dests.size()) {
            int dest = dests.get(di);
            int src = -1;
            int srcCount = Integer.MAX_VALUE;
            int largest = -1;
            int largestCount = 0;
            int lot;
            if (!itemsMode) {
                for (int i = 0; i < 36; i++) {
                    if (cnt[i] > 0) {
                        src = i;
                        srcCount = cnt[i];
                        break;
                    }
                }
                if (src == -1) break;
                lot = srcCount; // ganzer Stack
            } else {
                lot = (int) Math.min(left, cap);
                for (int i = 0; i < 36; i++) {
                    int c = cnt[i];
                    if (c <= 0) continue;
                    if (c >= lot && c < srcCount) {
                        src = i;
                        srcCount = c;
                    }
                    if (c > largestCount) {
                        largest = i;
                        largestCount = c;
                    }
                }
                if (src == -1) {
                    if (largest == -1) break;
                    src = largest;
                    srcCount = largestCount;
                    lot = largestCount;
                }
            }
            addLotClicks(menuSlotOfInv(src), srcCount, lot, dest);
            cnt[src] -= lot;
            plannedItems += lot;
            plannedStacks++;
            long v = job.value().applyAsLong(sample.copyWithCount(lot));
            if (v < 0) valueOk = false;
            else plannedValue += v;
            left -= itemsMode ? lot : 1;
            di++;
        }
        if (!valueOk) plannedValue = -1;
        return plannedItems > 0;
    }

    // ------------------------------------------------------------------
    // Hilfsfunktionen
    // ------------------------------------------------------------------

    private static int findEmpty(LocalPlayer p, int exclude) {
        for (int i = 0; i < 36; i++) {
            if (i != exclude && p.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private static int screenSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex; // Hotbar liegt im Menü bei 36-44
    }

    private static void swapWithHotbar(Minecraft mc, LocalPlayer p, int invIndex, int hotbarSlot) {
        mc.gameMode.handleContainerInput(
            p.inventoryMenu.containerId, screenSlot(invIndex), hotbarSlot, ContainerInput.SWAP, p);
    }

    private static void finish(Result result, String headline) {
        // Alles sofort ungültig machen: danach läuft garantiert keine Aktion mehr
        running = false;
        verifyPending = false;
        retryHand = false;
        cooldown = 0;
        settle = 0;
        clicks.clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p != null) {
            // Offenes /sell-Fenster schließen: Items kommen zurück ins Inventar
            if (guiMode && p.containerMenu != p.inventoryMenu) p.closeContainer();
            putBackCarried(mc, p);
            restoreHand(mc, p);
            if (headline != null) msg(p, headline);
            String sum = summary();
            if (sum != null) msg(p, sum);
        }
        splitSrcScreen = -1;
        Done d = done;
        done = null;
        if (d != null) d.finished(result);
    }

    /** Falls beim Abbruch noch ein Item am Mauszeiger hängt: zurück in den Quell-Slot legen. */
    private static void putBackCarried(Minecraft mc, LocalPlayer p) {
        if (mc.gameMode == null || p.containerMenu != p.inventoryMenu) return;
        if (p.containerMenu.getCarried().isEmpty()) return;
        if (splitSrcScreen >= 0) {
            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, splitSrcScreen, 0, ContainerInput.PICKUP, p);
        }
        if (!p.containerMenu.getCarried().isEmpty()) {
            int empty = findEmpty(p, -1);
            if (empty >= 0) {
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, screenSlot(empty), 0,
                    ContainerInput.PICKUP, p);
            }
        }
    }

    private static String summary() {
        if (soldStacks == 0) return null;
        long n = itemsMode ? soldItems : soldStacks;
        String m = "§a" + Fmt.amount(n, !itemsMode) + " " + label + (n == 1 ? " wurde " : " wurden ") + verb + ".";
        if (valueKnown) {
            m += " Gesamtwert: " + Fmt.money(totalValue) + (estimated ? " (geschätzt)" : "");
        }
        return m;
    }

    /** Legt das ursprüngliche Item (z.B. Spitzhacke) wieder in den ursprünglichen Hotbar-Slot. */
    private static void restoreHand(Minecraft mc, LocalPlayer p) {
        ItemStack wanted = savedHand;
        savedHand = ItemStack.EMPTY;
        if (wanted.isEmpty() || mc.gameMode == null || p.containerMenu != p.inventoryMenu) return;
        ItemStack atOrig = p.getInventory().getItem(origSlot);
        if (!atOrig.isEmpty() && ItemStack.isSameItemSameComponents(atOrig, wanted)
            && atOrig.getCount() == wanted.getCount()) return; // liegt schon richtig
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (i == origSlot || s.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(s, wanted) && s.getCount() == wanted.getCount()) {
                swapWithHotbar(mc, p, i, origSlot);
                return;
            }
        }
    }

    public static void msg(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[MultiAH] " + text));
    }
}
