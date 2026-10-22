package de.multiah;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.multiah.gui.DurabilityScreen;
import de.multiah.gui.PlayerLogScreen;
import de.multiah.gui.SellSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * /ahmulti <betrag>  -> verkauft ALLE Stacks des Items in deiner Hand (/ah sell <betrag>)
 * /ahmulti stop      -> stoppt sofort (auch /ahstop)
 * /sellsettings      -> öffnet die Einstellungen (oder Taste in den Steuerungs-Einstellungen)
 * /autosell [on|off|stop|status|reload]
 * /durability [on|off|status|percent <1-100>]  -> Werkzeug-Schutz (Taste: Durability Guard an/aus)
 * /playerlog [clear|debug on|debug off]       -> TPA-Log (Taste: Player-Log öffnen)
 */
public class MultiAhClient implements ClientModInitializer {
    private static KeyMapping openKey;
    private static KeyMapping durabilityKey;
    private static KeyMapping playerLogKey;
    private static int openGuiTicks = -1;
    private static Supplier<Screen> openRequest;

    @Override
    public void onInitializeClient() {
        Config.get();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.multiah.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KeyMapping.Category.MISC));
        durabilityKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.multiah.durability", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

        playerLogKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.multiah.playerlog", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, KeyMapping.Category.MISC));

        // Player-Log: eingehende Server-Nachrichten auf TPA-Anfragen prüfen (läuft im Hauptthread)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) PlayerLog.onMessage(message.getString());
        });

        // Werkzeug-Schutz: Abbau sperren, wenn das Werkzeug gefährdet ist
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
            DurabilityGuard.onAttack(player, level, pos));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("ahmulti")
                .then(ClientCommands.literal("stop").executes(ctx -> stopSelling()))
                .then(ClientCommands.argument("betrag", StringArgumentType.word())
                    .executes(ctx -> {
                        LocalPlayer p = Minecraft.getInstance().player;
                        if (p == null) return 0;
                        ItemStack hand = p.getMainHandItem();
                        if (hand.isEmpty()) {
                            Seller.msg(p, "§cDu hast kein Item in der Hand.");
                            return 0;
                        }
                        ItemStack template = hand.copyWithCount(1);
                        String price = StringArgumentType.getString(ctx, "betrag");
                        long value = price.matches("\\d{1,15}") ? Long.parseLong(price) : -1L;
                        Seller.startWhole(hand.getHoverName().getString(),
                            s -> !s.isEmpty() && ItemStack.isSameItemSameComponents(s, template),
                            new Seller.Job(s -> "ah sell " + price, s -> value),
                            Long.MAX_VALUE, "zum Verkauf eingestellt", false, null);
                        Seller.msg(p, "§aMultiverkauf gestartet. Stoppen mit /ahmulti stop");
                        return 1;
                    })));

            dispatcher.register(ClientCommands.literal("ahstop").executes(ctx -> stopSelling()));

            dispatcher.register(ClientCommands.literal("sellsettings").executes(ctx -> {
                request(() -> new SellSettingsScreen(null), 3); // erst nach dem Schließen des Chats öffnen
                return 1;
            }));

            dispatcher.register(ClientCommands.literal("autosell")
                .executes(ctx -> setAutoSell(!Config.get().autoSellEnabled))
                .then(ClientCommands.literal("on").executes(ctx -> setAutoSell(true)))
                .then(ClientCommands.literal("off").executes(ctx -> setAutoSell(false)))
                .then(ClientCommands.literal("stop").executes(ctx -> setAutoSell(false)))
                .then(ClientCommands.literal("reload").executes(ctx -> {
                    Config.reload();
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) Seller.msg(p, "§aEinstellungen neu geladen.");
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    AutoSell.status();
                    return 1;
                })));

            dispatcher.register(ClientCommands.literal("playerlog")
                .executes(ctx -> {
                    request(() -> new PlayerLogScreen(null), 3);
                    return 1;
                })
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    PlayerLog.clear();
                    LocalPlayer p = Minecraft.getInstance().player;
                    if (p != null) Seller.msg(p, "§aPlayer-Log geleert.");
                    return 1;
                }))
                .then(ClientCommands.literal("debug")
                    .then(ClientCommands.literal("on").executes(ctx -> setPlayerLogDebug(true)))
                    .then(ClientCommands.literal("off").executes(ctx -> setPlayerLogDebug(false)))));

            dispatcher.register(ClientCommands.literal("durability")
                .executes(ctx -> {
                    request(() -> new DurabilityScreen(null), 3);
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    DurabilityGuard.setEnabled(true);
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    DurabilityGuard.setEnabled(false);
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    DurabilityGuard.status();
                    return 1;
                }))
                .then(ClientCommands.literal("percent")
                    .then(ClientCommands.argument("wert", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                        Config.get().durability.warnPercent = IntegerArgumentType.getInteger(ctx, "wert");
                        Config.save();
                        LocalPlayer p = Minecraft.getInstance().player;
                        if (p != null) {
                            Seller.msg(p, "§aWarnschwelle: " + Config.get().durability.warnPercent + " %");
                        }
                        return 1;
                    }))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            while (openKey.consumeClick()) request(() -> new SellSettingsScreen(null), 1);
            while (durabilityKey.consumeClick()) DurabilityGuard.toggle();
            while (playerLogKey.consumeClick()) request(() -> new PlayerLogScreen(null), 1);
            if (openGuiTicks > 0 && --openGuiTicks == 0 && openRequest != null) {
                mc.setScreenAndShow(openRequest.get());
            }
            DurabilityGuard.tick(mc);
            Seller.tick(mc);
            AutoSell.tick(mc);
        });
    }

    private static int setPlayerLogDebug(boolean on) {
        Config.get().playerLog.debug = on;
        Config.save();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            Seller.msg(p, on ? "§aPlayer-Log-Debug AN: Chat-Zeilen mit tp/teleport werden angezeigt."
                : "§ePlayer-Log-Debug AUS.");
        }
        return 1;
    }

    private static void request(Supplier<Screen> screen, int ticks) {
        openRequest = screen;
        openGuiTicks = ticks;
    }

    private static int stopSelling() {
        if (!Seller.stop()) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) Seller.msg(p, "§7Es läuft gerade kein Verkauf.");
        }
        return 1;
    }

    /** Ausschalten stoppt zuerst einen laufenden Verkauf und schaltet dann Auto-Sell ab. */
    private static int setAutoSell(boolean on) {
        Config cfg = Config.get();
        if (!on) {
            cfg.autoSellEnabled = false; // zuerst aus, damit nichts neu startet
            Seller.stop();
        } else {
            cfg.autoSellEnabled = true;
        }
        Config.save();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) Seller.msg(p, on ? "§aAuto-Sell ist jetzt AN." : "§eAuto-Sell ist jetzt AUS.");
        return 1;
    }
}
