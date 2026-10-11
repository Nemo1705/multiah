package de.multiah;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("ahmulti")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word())
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        ClientPlayerEntity p = mc.player;
                        if (p == null) return 0;
                        ItemStack hand = p.getMainHandStack();
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
            dispatcher.register(ClientCommandManager.literal("ahstop").executes(ctx -> {
                running = false;
                if (MinecraftClient.getInstance().player != null)
                    msg(MinecraftClient.getInstance().player, "§eMultiverkauf abgebrochen (" + sold + " Stacks verkauft).");
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(MultiAhClient::tick);
    }

    private static void tick(MinecraftClient mc) {
        if (!running) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            running = false;
            return;
        }
        // Wenn ein Menü offen ist, nicht eingreifen
        if (mc.currentScreen != null) return;
        if (cooldown-- > 0) return;

        int selected = p.getInventory().getSelectedSlot();
        ItemStack hand = p.getMainHandStack();

        // Passt die Hand schon -> direkt verkaufen
        if (!hand.isEmpty() && ItemStack.areItemsAndComponentsEqual(hand, template)) {
            sell(mc, p);
            return;
        }

        // Sonst nächsten passenden Stack im Inventar (Slots 0-35) suchen und in die Hand tauschen
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (i == selected || s.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(s, template)) {
                int screenSlot = i < 9 ? 36 + i : i; // Hotbar liegt im Screen-Handler bei 36-44
                mc.interactionManager.clickSlot(
                    p.playerScreenHandler.syncId, screenSlot, selected, SlotActionType.SWAP, p);
                sell(mc, p);
                return;
            }
        }

        // Nichts mehr gefunden -> fertig
        running = false;
        msg(p, "§aFertig! " + sold + " Stacks verkauft.");
    }

    private static void sell(MinecraftClient mc, ClientPlayerEntity p) {
        mc.getNetworkHandler().sendChatCommand("ah sell " + price);
        sold++;
        cooldown = DELAY_TICKS;
    }

    private static void msg(ClientPlayerEntity p, String text) {
        p.sendMessage(Text.literal("[MultiAH] " + text), false);
    }
}
