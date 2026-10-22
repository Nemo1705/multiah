package de.multiah;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Durability Guard: warnt vor dem Zerbrechen von Werkzeugen und kann den nächsten Abbau sperren.
 * Alles passiert nur auf deinem Computer. Der Server entscheidet am Ende selbst über die Haltbarkeit,
 * darum ist der Schutz eine vorsichtige Schätzung mit Reserve, keine Garantie.
 */
public final class DurabilityGuard {
    private static String lastKey = "";
    private static int warnLevel = 0; // 0 = nichts, 1 = Warnung, 2 = kritisch
    private static int timer = 0;
    private static long lastBlockMsg = 0;

    private DurabilityGuard() {}

    public static boolean isStar(ItemStack s) {
        String n = Config.get().durability.starName;
        if (n == null || n.isBlank()) return false;
        return s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT));
    }

    public static int remaining(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue();
    }

    /** Geschätzte Kosten des nächsten Abbaus inkl. Reserve. */
    public static int cost(ItemStack s) {
        Config.Durability d = Config.get().durability;
        int base = isStar(s) ? Math.max(1, d.starCost) : 1;
        return base + Math.max(0, d.reservePoints);
    }

    /** Grenze in Punkten: Prozent der Maximal-Haltbarkeit oder fester Mindestabstand (der größere Wert). */
    public static int limit(ItemStack s) {
        Config.Durability d = Config.get().durability;
        int byPercent = (int) Math.ceil(s.getMaxDamage() * d.warnPercent / 100.0);
        return Math.max(Math.max(1, byPercent), d.minPoints);
    }

    /** Würde der nächste Abbau voraussichtlich unter die Grenze führen? */
    public static boolean unsafe(ItemStack s) {
        return s.getMaxDamage() > 0 && remaining(s) - cost(s) < limit(s);
    }

    private static boolean critical(ItemStack s) {
        return s.getMaxDamage() > 0 && remaining(s) - cost(s) < Math.max(1, limit(s) / 2);
    }

    public static String describe(ItemStack s) {
        if (s.isEmpty() || s.getMaxDamage() <= 0) return "kein Werkzeug mit Haltbarkeit in der Hand";
        int rem = remaining(s);
        int max = s.getMaxDamage();
        return s.getHoverName().getString() + " " + rem + "/" + max + " ("
            + String.format(Locale.GERMANY, "%.1f", rem * 100.0 / max) + " %)";
    }

    public static void toggle() {
        Config.Durability d = Config.get().durability;
        d.enabled = !d.enabled;
        Config.save();
        warnLevel = 0;
        lastKey = "";
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            String t = d.enabled ? "§aDurability Guard aktiviert" : "§eDurability Guard deaktiviert";
            p.sendOverlayMessage(Component.literal(t));
            msg(p, t);
        }
    }

    public static void setEnabled(boolean on) {
        Config.Durability d = Config.get().durability;
        if (d.enabled != on) toggle();
    }

    /** Wird ca. 4x pro Sekunde aufgerufen: prüft das Werkzeug in der Hand. */
    public static void tick(Minecraft mc) {
        Config.Durability d = Config.get().durability;
        if (!d.enabled || mc.player == null) return;
        if (timer-- > 0) return;
        timer = 5;
        LocalPlayer p = mc.player;
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty() || s.getMaxDamage() <= 0) {
            lastKey = "";
            warnLevel = 0;
            return;
        }
        String key = AutoSell.id(s) + "|" + s.getMaxDamage() + "|" + s.getHoverName().getString();
        if (!key.equals(lastKey)) { // anderes Werkzeug -> Warnung neu bewerten
            lastKey = key;
            warnLevel = 0;
        }
        if (!unsafe(s)) {
            warnLevel = 0; // repariert oder wieder sicher
            return;
        }
        int target = critical(s) ? 2 : 1;
        if (target > warnLevel) { // Warnung nur bei Zustandsänderung, nicht bei jedem Block
            warnLevel = target;
            warn(p, s, target == 2);
        }
    }

    private static void warn(LocalPlayer p, ItemStack s, boolean critical) {
        Config.Durability d = Config.get().durability;
        int rem = remaining(s);
        int max = s.getMaxDamage();
        String pct = String.format(Locale.GERMANY, "%.1f", rem * 100.0 / max);
        String name = s.getHoverName().getString();
        String what = isStar(s) ? "Der nächste 3x3-Abbau" : "Der nächste Abbau";
        String head = critical ? "§c⚠ KRITISCH! " : "§e⚠ DURABILITY WARNUNG! ";
        String text = head + "§fDein Werkzeug §6" + name + "§f hat nur noch §c" + pct + " %§f Haltbarkeit ("
            + rem + "/" + max + "). " + what + " könnte deine Sicherheitsgrenze unterschreiten!";
        if (d.chatWarn) msg(p, text);
        if (d.screenWarn) p.sendOverlayMessage(Component.literal(head + "§f" + name + ": §c" + pct + " %"));
        if (d.sound) p.playSound(SoundEvents.ANVIL_LAND, 0.8F, critical ? 0.8F : 1.2F);
    }

    /** Wird beim Start eines Abbaus aufgerufen. FAIL = Abbau wird nicht gestartet. */
    public static InteractionResult onAttack(Player player, Level level, BlockPos pos) {
        Config.Durability d = Config.get().durability;
        if (!d.enabled || d.blockMode == 0 || !level.isClientSide() || player.isCreative()) {
            return InteractionResult.PASS;
        }
        ItemStack s = player.getMainHandItem();
        if (s.isEmpty() || s.getMaxDamage() <= 0) return InteractionResult.PASS;
        if (level.getBlockState(pos).getDestroySpeed(level, pos) <= 0.0F) return InteractionResult.PASS; // kostet nichts
        boolean block = d.blockMode == 1 ? unsafe(s) : remaining(s) - cost(s) < 1;
        if (!block) return InteractionResult.PASS;
        long now = System.currentTimeMillis();
        if (now - lastBlockMsg > 3000) {
            lastBlockMsg = now;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                String t = "§cAbbau gesperrt: §f" + describe(s) + ". Grenze: " + limit(s)
                    + " Punkte. Mit der Durability-Taste (Standard K) schaltest du den Schutz aus.";
                p.sendOverlayMessage(Component.literal("§c⚠ Abbau gesperrt - Werkzeug schützen!"));
                msg(p, t);
            }
        }
        return InteractionResult.FAIL;
    }

    public static void status() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        Config.Durability d = Config.get().durability;
        ItemStack s = p.getMainHandItem();
        msg(p, "Guard: " + (d.enabled ? "AN" : "AUS") + " | Schwelle " + d.warnPercent + " % | Sperre-Modus "
            + d.blockMode + " | Reserve " + d.reservePoints);
        if (s.isEmpty() || s.getMaxDamage() <= 0) {
            msg(p, "In der Hand: " + describe(s));
            return;
        }
        msg(p, "In der Hand: " + describe(s) + " | ID " + AutoSell.id(s));
        msg(p, "Star-Picke erkannt: " + isStar(s) + " | geschätzte Kosten/Abbau: " + cost(s)
            + " | Grenze: " + limit(s) + " Punkte | riskant: " + unsafe(s));
    }

    private static void msg(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[DurabilityGuard] " + text));
    }
}
