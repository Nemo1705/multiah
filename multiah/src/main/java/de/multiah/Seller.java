package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Gemeinsamer Verkaufs-Baustein für /ahmulti und Auto-Sell (Methode /ah und /sell).
 *
 * Zwei Arten von Aufträgen:
 *  - startWhole: verkauft ganze Stacks (maximal die angegebene Anzahl)
 *  - startItems: verkauft genau die angegebene Anzahl einzelner Items; Stacks werden dafür
 *    automatisch aufgeteilt, der Rest bleibt im Inventar.
 *
 * Es gibt keine Timer/Threads, nur diesen einen Zustand. Ein Stop (oder das Ende) setzt alles sofort zurück,
 * danach läuft garantiert nichts mehr weiter.
 */
public final class Seller {
    public enum Result { OK, FAILED, CANCELLED }

    /** command: kompletter Befehl ohne Schrägstrich. value: Verkaufswert des Stacks (-1 = unbekannt). */
    public record Job(Function<ItemStack, String> command, ToLongFunction<ItemStack> value) {}

    public interface Done { void finished(Result result); }

    private static final int MAX_FAILURES = 4;
    private static final int BLOCK_LIMIT = 200;   // 10 Sekunden blockiert -> abbrechen statt hängen
    private static final int CLICKS_PER_TICK = 2; // Aufteilen ohne Serverspam
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
    private static final ArrayDeque<int[]> clicks = new ArrayDeque<>(); // {screenSlot, button, 0=PICKUP|1=SWAP}

    private static ItemStack savedHand = ItemStack.EMPTY;
    private static int origSlot;

    private static int soldStacks;
    private static long soldItems;
    private static long totalValue;
    private static boolean valueKnown;

    private Seller() {}

    /** Verkauft ganze Stacks, höchstens maxStacks Stück. */
    public static void startWhole(String label, Predicate<ItemStack> matcher, Job job, long maxStacks,
                                  String verb, boolean estimated, Done done) {
        init(label, matcher, job, false, maxStacks, verb, estimated, done);
    }

    /** Verkauft genau totalItems einzelne Items (teilt Stacks bei Bedarf automatisch auf). */
    public static void startItems(String label, Predicate<ItemStack> matcher, Job job, long totalItems,
                                  String verb, boolean estimated, Done done) {
        init(label, matcher, job, true, totalItems, verb, estimated, done);
    }

    private static void init(String label, Predicate<ItemStack> matcher, Job job, boolean itemsMode,
                             long remaining, String verb, boolean estimated, Done done) {
        if (running) finish(Result.CANCELLED, null);
        Seller.label = label;
        Seller.matcher = matcher;
        Seller.job = job;
        Seller.itemsMode = itemsMode;
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
        // Kisten-/Container-Menü offen: warten, aber nicht ewig
        if (p.containerMenu != p.inventoryMenu) {
            if (++blocked > BLOCK_LIMIT) finish(Result.FAILED, "§cEin Menü ist offen - Verkauf abgebrochen.");
            return;
        }

        // 1. Klick-Folge fürs Aufteilen (wenige Klicks pro Tick)
        if (!clicks.isEmpty()) {
            for (int k = 0; k < CLICKS_PER_TICK && !clicks.isEmpty(); k++) {
                int[] c = clicks.poll();
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, c[0], c[1],
                    c[2] == 0 ? ContainerInput.PICKUP : ContainerInput.SWAP, p);
            }
            if (clicks.isEmpty()) settle = SETTLE_TICKS;
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
        if (lot == (srcCount + 1) / 2) {
            // Rechtsklick nimmt die (aufgerundete) Hälfte
            clicks.add(new int[] {ss, 1, 0});
            clicks.add(new int[] {hs, 0, 0});
        } else if (srcCount - lot < lot) {
            // Aufnehmen, den Rest einzeln zurücklegen, die gewünschte Menge in die Hand legen
            clicks.add(new int[] {ss, 0, 0});
            for (int k = 0; k < srcCount - lot; k++) clicks.add(new int[] {ss, 1, 0});
            clicks.add(new int[] {hs, 0, 0});
        } else {
            // Aufnehmen, gewünschte Menge einzeln in die Hand legen, Rest zurück in den Stack
            clicks.add(new int[] {ss, 0, 0});
            for (int k = 0; k < lot; k++) clicks.add(new int[] {hs, 1, 0});
            clicks.add(new int[] {ss, 0, 0});
        }
        planLot = lot;
    }

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

    private static void sell(Minecraft mc, ItemStack stack) {
        mc.getConnection().sendCommand(job.command().apply(stack));
        pendingCount = stack.getCount();
        pendingValue = job.value().applyAsLong(stack);
        verifyPending = true;
        cooldown = Config.get().delayTicks;
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
