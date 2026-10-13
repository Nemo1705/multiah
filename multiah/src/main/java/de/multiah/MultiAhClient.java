package de.multiah;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.multiah.gui.SellSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * /ahmulti <betrag>  -> verkauft ALLE Stacks des Items in deiner Hand (/ah sell <betrag>)
 * /ahmulti stop      -> stoppt sofort (auch /ahstop)
 * /sellsettings      -> öffnet die Einstellungen (oder Taste in den Steuerungs-Einstellungen)
 * /autosell [on|off|stop|status]
 */
public class MultiAhClient implements ClientModInitializer {
    private static KeyMapping openKey;
    private static int openGuiTicks = -1;

    @Override
    public void onInitializeClient() {
        Config.get();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.multiah.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KeyMapping.Category.MISC));

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
                openGuiTicks = 3; // erst nach dem Schließen des Chats öffnen
                return 1;
            }));

            dispatcher.register(ClientCommands.literal("autosell")
                .executes(ctx -> setAutoSell(!Config.get().autoSellEnabled))
                .then(ClientCommands.literal("on").executes(ctx -> setAutoSell(true)))
                .then(ClientCommands.literal("off").executes(ctx -> setAutoSell(false)))
                .then(ClientCommands.literal("stop").executes(ctx -> setAutoSell(false)))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    AutoSell.status();
                    return 1;
                })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            while (openKey.consumeClick()) openGuiTicks = 1;
            if (openGuiTicks > 0 && --openGuiTicks == 0) {
                mc.setScreenAndShow(new SellSettingsScreen(null));
            }
            Seller.tick(mc);
            AutoSell.tick(mc);
        });
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
