package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Gemeinsamer Verkaufs-Baustein für /ahmulti und Auto-Sell.
 * Tauscht passende Stacks nacheinander in die Hand und sendet "/ah sell <preis>".
 * Prüft danach, ob der Stack wirklich aus der Hand verschwunden ist (= Verkauf hat geklappt).
 */
public final class Seller {
    public enum Result { OK, FAILED, CANCELLED }

    public interface PriceFn { String price(ItemStack stack); }

    public interface Done { void finished(Result result); }

    private static final int MAX_FAILURES = 3;

    public static boolean running = false;
    public static boolean guiOpen = false;

    private static Predicate<ItemStack> matcher;
    private static PriceFn priceFn;
    private static Done done;
    private static String label = "";
    private static int maxActions;
    private static int cooldown;
    private static int sold;
    private static int failures;
    private static int pendingCount;
    private static boolean verifyPending;
    private static ItemStack savedHand = ItemStack.EMPTY;

    private Seller() {}

    public static void start(String label, Predicate<ItemStack> matcher, PriceFn priceFn,
                             int maxActions, Done done) {
        if (running) finish(Result.CANCELLED, null);
        Seller.label = label;
        Seller.matcher = matcher;
        Seller.priceFn = priceFn;
        Seller.maxActions = maxActions;
        Seller.done = done;
        sold = 0;
        failures = 0;
        cooldown = 0;
        verifyPending = false;
        savedHand = ItemStack.EMPTY;
        running = true;
    }

    public static void stop() {
        if (!running) return;
        finish(Result.CANCELLED, "§eVerkauf abgebrochen (" + sold + " Stacks verkauft).");
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.getConnection() == null) {
            running = false;
            done = null;
            return;
        }
        // Nur eingreifen, wenn kein Kisten-/Container-Menü offen ist und nichts am Mauszeiger hängt
        if (p.containerMenu != p.inventoryMenu) return;
        if (!p.containerMenu.getCarried().isEmpty()) return;
        if (cooldown-- > 0) return;

        int selected = p.getInventory().getSelectedSlot();
        ItemStack hand = p.getMainHandItem();

        // Hat der letzte Verkauf geklappt? (Stack muss aus der Hand verschwunden sein)
        if (verifyPending) {
            verifyPending = false;
            if (!hand.isEmpty() && matcher.test(hand) && hand.getCount() == pendingCount) {
                failures++;
                if (failures >= MAX_FAILURES) {
                    finish(Result.FAILED,
                        "§cDas AH reagiert nicht - Verkauf abgebrochen. Prüfe Preis, AH-Limit und Chat.");
                    return;
                }
            } else {
                failures = 0;
                sold++;
                if (maxActions > 0 && sold >= maxActions) {
                    finish(Result.OK, "§aRunde beendet: " + sold + " Stacks verkauft (" + label + ").");
                    return;
                }
            }
        }

        // Hand passt schon (oder Wiederholung nach Fehler) -> direkt verkaufen
        hand = p.getMainHandItem();
        if (!hand.isEmpty() && matcher.test(hand)) {
            sell(mc, hand.copy());
            return;
        }

        // Nächsten passenden Stack im Inventar suchen und in die Hand tauschen
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (i == selected || s.isEmpty() || !matcher.test(s)) continue;
            ItemStack target = s.copy();
            if (savedHand.isEmpty() && !hand.isEmpty()) savedHand = hand.copy();
            int screenSlot = i < 9 ? 36 + i : i; // Hotbar liegt im Menü bei 36-44
            mc.gameMode.handleContainerInput(
                p.inventoryMenu.containerId, screenSlot, selected, ContainerInput.SWAP, p);
            sell(mc, target);
            return;
        }

        // Nichts mehr gefunden -> fertig
        finish(Result.OK, sold > 0 ? "§aFertig! " + sold + " Stacks verkauft (" + label + ")." : null);
    }

    private static void sell(Minecraft mc, ItemStack stack) {
        mc.getConnection().sendCommand("ah sell " + priceFn.price(stack));
        pendingCount = stack.getCount();
        verifyPending = true;
        cooldown = Config.get().delayTicks;
    }

    private static void finish(Result result, String message) {
        running = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p != null) {
            restoreHand(mc, p);
            if (message != null) msg(p, message);
        }
        Done d = done;
        done = null;
        if (d != null) d.finished(result);
    }

    /** Legt das Item, das vorher in der Hand war (z.B. dein Werkzeug), wieder in die Hand. */
    private static void restoreHand(Minecraft mc, LocalPlayer p) {
        ItemStack wanted = savedHand;
        savedHand = ItemStack.EMPTY;
        if (wanted.isEmpty() || mc.gameMode == null) return;
        if (!p.getMainHandItem().isEmpty()) return;
        int selected = p.getInventory().getSelectedSlot();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (i == selected || s.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(s, wanted) && s.getCount() == wanted.getCount()) {
                int screenSlot = i < 9 ? 36 + i : i;
                mc.gameMode.handleContainerInput(
                    p.inventoryMenu.containerId, screenSlot, selected, ContainerInput.SWAP, p);
                return;
            }
        }
    }

    public static void msg(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[MultiAH] " + text));
    }
}
