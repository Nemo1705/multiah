package de.multiah.gui;

import de.multiah.Config;
import de.multiah.Fmt;
import de.multiah.Icons;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Einstellungen für ein einzelnes Item. */
public class EntryScreen extends BaseScreen {
    private final Config.Entry e;
    private boolean confirmDelete = false;

    public EntryScreen(Screen parent, Config.Entry e) {
        super("Item bearbeiten", parent);
        this.e = e;
    }

    private static long parseLong(String s) {
        try {
            String digits = s.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        int cx = this.width / 2;
        int wx = cx - 50;
        int ww = 150;
        int y = 30;
        int step = 22;

        // 1. Status
        this.addRenderableWidget(Button.builder(
            Component.literal(e.enabled ? "§aAKTIV" : "§cDEAKTIVIERT"), b -> {
                e.enabled = !e.enabled;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y, ww, 20).build());

        // 2. Verkaufsmethode
        this.addRenderableWidget(Button.builder(
            Component.literal(e.isSell() ? "§a/sell" : "§e/ah"), b -> {
                e.method = e.isSell() ? "ah" : "sell";
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y + step, ww, 20).build());

        // 3. Preis
        EditBox price = new EditBox(this.font, wx, y + step * 2, ww, 20, Component.literal("Preis"));
        price.setMaxLength(12);
        price.setValue(e.price > 0 ? String.valueOf(e.price) : "");
        price.setResponder(s -> e.price = parseLong(s));
        this.addRenderableWidget(price);

        // 4. Einheit (gilt für Preis UND Mindestmenge)
        this.addRenderableWidget(Button.builder(
            Component.literal(e.perStack ? "Stacks" : "Items"), b -> {
                e.perStack = !e.perStack;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y + step * 3, ww, 20).build());

        // 5. Mindestmenge
        EditBox amount = new EditBox(this.font, wx, y + step * 4, ww, 20, Component.literal("Menge"));
        amount.setMaxLength(6);
        amount.setValue(String.valueOf(e.minAmount));
        amount.setResponder(s -> e.minAmount = (int) Math.max(1, Math.min(999999, parseLong(s))));
        this.addRenderableWidget(amount);

        // 6. Priorität
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            e.priority = Math.max(-99, e.priority - 1);
            this.rebuildWidgets();
        }).bounds(wx, y + step * 5, 30, 20).build());
        Button prio = Button.builder(Component.literal(String.valueOf(e.priority)), b -> { })
            .bounds(wx + 32, y + step * 5, ww - 64, 20).build();
        prio.active = false;
        this.addRenderableWidget(prio);
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            e.priority = Math.min(99, e.priority + 1);
            this.rebuildWidgets();
        }).bounds(wx + ww - 30, y + step * 5, 30, 20).build());

        // Löschen / Fertig
        int by = y + step * 6 + 6;
        this.addRenderableWidget(Button.builder(
            Component.literal(confirmDelete ? "§cWirklich löschen?" : "Löschen"), b -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    this.rebuildWidgets();
                } else {
                    Config.get().entries.remove(e);
                    Config.save();
                    this.minecraft.setScreenAndShow(parent);
                }
            }).bounds(cx - 150, by, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(cx + 5, by, 145, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int lx = cx - 150;
        int y = 30;
        int step = 22;
        Component title = Component.literal("§l" + e.name);
        g.text(this.font, title, cx - this.font.width(title) / 2 + 10, 14, 0xFFFFFFFF);
        g.item(Icons.of(e.id), cx - this.font.width(title) / 2 - 10, 10);
        label(g, "Status", lx, y);
        label(g, "Verkaufsmethode", lx, y + step);
        label(g, "Preis ($)", lx, y + step * 2);
        label(g, "Einheit", lx, y + step * 3);
        label(g, "Mindestmenge", lx, y + step * 4);
        label(g, "Priorität", lx, y + step * 5);

        String info;
        if (e.isSell()) {
            info = "§7Verkauft ab §f" + Fmt.amount(e.minAmount, e.perStack) + " §7per §a/sell §7(Preis bestimmt der Server)";
        } else if (e.price <= 0) {
            info = "§cKein Preis gesetzt - es wird nichts verkauft.";
        } else {
            info = "§7Verkauft ab §f" + Fmt.amount(e.minAmount, e.perStack) + " §7per §e/ah §7für §f"
                + Fmt.money(e.price) + (e.perStack ? " pro Stack" : " pro Item");
        }
        Component c = Component.literal(info);
        g.text(this.font, c, cx - this.font.width(c) / 2, y + step * 6 + 34, 0xFFFFFFFF);
    }

    private void label(GuiGraphicsExtractor g, String s, int x, int y) {
        g.text(this.font, Component.literal(s), x, y + 6, 0xFFFFFFFF);
    }
}
