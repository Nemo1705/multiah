package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Prüft regelmäßig das Inventar und startet bei erreichter Mindestmenge einen Verkauf. */
public final class AutoSell {
    private static final int CHECK_INTERVAL = 40;   // alle 2 Sekunden prüfen
    private static final int BACKOFF_TICKS = 1200;  // 1 Minute Pause nach Abbruch/Fehler
    private static final int MAX_ABORTS = 3;

    private static int timer = 0;
    private static int backoff = 0;
    private static int aborts = 0;

    private AutoSell() {}

    public static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** Darf dieser Stack für den Eintrag automatisch verkauft werden? */
    public static boolean eligible(Config.Entry e, ItemStack s) {
        if (s.isEmpty() || !id(s).equals(e.id)) return false;
        // nur "normale" Items ohne Verzauberung/Name/Schaden
        if (!ItemStack.isSameItemSameComponents(s, s.getItem().getDefaultInstance())) return false;
        // bei Preis pro Stack nur volle Stacks
        return !e.perStack || s.getCount() == s.getMaxStackSize();
    }

    public static long salePrice(Config.Entry e, ItemStack s) {
        if (!e.perStack) return Math.max(1L, e.price * s.getCount());
        int max = Math.max(1, s.getMaxStackSize());
        if (s.getCount() == max) return e.price;
        return Math.max(1L, Math.round((double) e.price * s.getCount() / max));
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
            if (!e.enabled || e.price <= 0) continue;
            int count = 0;
            int maxStack = 64;
            for (int i = 0; i < 36; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (eligible(e, s)) {
                    count += s.getCount();
                    maxStack = s.getMaxStackSize();
                }
            }
            if (count > 0 && count >= e.thresholdItems(maxStack)) {
                startRound(e);
                return;
            }
        }
    }

    private static void startRound(Config.Entry e) {
        Config cfg = Config.get();
        Seller.start(e.name, s -> eligible(e, s), s -> String.valueOf(salePrice(e, s)),
            cfg.maxSellsPerRound, result -> {
                if (result == Seller.Result.OK) {
                    aborts = 0;
                } else {
                    backoff = BACKOFF_TICKS;
                    if (result == Seller.Result.FAILED) {
                        aborts++;
                        if (aborts >= MAX_ABORTS) {
                            cfg.autoSellEnabled = false;
                            Config.save();
                            aborts = 0;
                            LocalPlayer p = Minecraft.getInstance().player;
                            if (p != null) {
                                Seller.msg(p, "§cAuto-Sell wurde nach mehreren Fehlern AUSGESCHALTET.");
                            }
                        }
                    }
                }
            });
    }
}
