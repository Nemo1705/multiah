package de.multiah;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * /ahmulti <betrag>  -> verkauft ALLE Stacks des Items in deiner Hand (/ah sell <betrag>)
 * /ahstop            -> bricht den laufenden Verkauf ab
 */
public class MultiAhClient implements ClientModInitializer {
    private static final int DELAY_TICKS = 20; // Pause zwischen den Verkäufen (20 = 1 Sekunde)

    private static boolean running = false;
    private static String price;
    private static ItemStack template;
    private static int cooldown = 0;
    private static int sold = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("ahmulti")
                .then(ClientCommands.argument("betrag", StringArgumentType.word())
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        LocalPlayer p = mc.player;
                        if (p == null) return 0;
                        ItemStack hand = p.getMainHandItem();
                        if (hand.isEmpty()) {
                            msg(p, "§cDu hast kein Item in der Hand.");
                            return 0;
                        }
                        template = hand.copyWithCount(1);
                        price = StringArgumentType.getString(ctx, "betrag");
                        sold = 0;
                        cooldown = 0;
                        running = true;
                        msg(p, "§aMultiverkauf gestartet. Abbrechen mit /ahstop");
                        return 1;
                    })));
            dispatcher.register(ClientCommands.literal("ahstop").executes(ctx -> {
                running = false;
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) msg(p, "§eMultiverkauf abgebrochen (" + sold + " Stacks verkauft).");
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(MultiAhClient::tick);
    }

    private static void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.getConnection() == null) {
            running = false;
            return;
        }
        // Wenn ein Menü offen ist, nicht eingreifen
        if (mc.screen != null) return;
        if (cooldown-- > 0) return;

        int selected = p.getInventory().getSelectedSlot();
        ItemStack hand = p.getMainHandItem();

        // Passt die Hand schon -> direkt verkaufen
        if (!hand.isEmpty() && ItemStack.isSameItemSameComponents(hand, template)) {
            sell(mc);
            return;
        }

        // Sonst nächsten passenden Stack im Inventar (Slots 0-35) suchen und in die Hand tauschen
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (i == selected || s.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(s, template)) {
                int screenSlot = i < 9 ? 36 + i : i; // Hotbar liegt im Menü bei 36-44
                mc.gameMode.handleContainerInput(
                    p.inventoryMenu.containerId, screenSlot, selected, ContainerInput.SWAP, p);
                sell(mc);
                return;
            }
        }

        // Nichts mehr gefunden -> fertig
        running = false;
        msg(p, "§aFertig! " + sold + " Stacks verkauft.");
    }

    private static void sell(Minecraft mc) {
        mc.getConnection().sendCommand("ah sell " + price);
        sold++;
        cooldown = DELAY_TICKS;
    }

    private static void msg(LocalPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[MultiAH] " + text));
    }
}
