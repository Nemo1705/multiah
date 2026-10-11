package de.multiah.gui;

import de.multiah.Config;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Einstellungen für ein einzelnes Item. */
public class EntryScreen extends Screen {
    private final Screen parent;
    private final Config.Entry e;
    private boolean confirmDelete = false;

    public EntryScreen(Screen parent, Config.Entry e) {
        super(Component.literal("Item bearbeiten"));
        this.parent = parent;
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
        de.multiah.Seller.guiOpen = true;
        int cx = this.width / 2;
        int wx = cx - 50;
        int ww = 150;
        int y = 40;
        int step = 22;

        // 1. Status
        this.addRenderableWidget(Button.builder(
            Component.literal(e.enabled ? "§aAKTIV" : "§cDEAKTIVIERT"), b -> {
                e.enabled = !e.enabled;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y, ww, 20).build());

        // 2. Preis
        EditBox price = new EditBox(this.font, wx, y + step, ww, 20, Component.literal("Preis"));
        price.setMaxLength(12);
        
        price.setValue(e.price > 0 ? String.valueOf(e.price) : "");
        price.setResponder(s -> e.price = parseLong(s));
        this.addRenderableWidget(price);

        // 3. Preis pro Stack / pro Item
        this.addRenderableWidget(Button.builder(
            Component.literal(e.perStack ? "pro Stack" : "pro Item"), b -> {
                e.perStack = !e.perStack;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y + step * 2, ww, 20).build());

        // 4. Mindestmenge
        EditBox amount = new EditBox(this.font, wx, y + step * 3, ww, 20, Component.literal("Menge"));
        amount.setMaxLength(6);
       
        amount.setValue(String.valueOf(e.minAmount));
        amount.setResponder(s -> e.minAmount = (int) Math.max(1, Math.min(999999, parseLong(s))));
        this.addRenderableWidget(amount);

        // 5. Mindestmenge in Stacks / Items
        this.addRenderableWidget(Button.builder(
            Component.literal(e.minInStacks ? "Stacks" : "Items"), b -> {
                e.minInStacks = !e.minInStacks;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y + step * 4, ww, 20).build());

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
        int by = y + step * 6 + 8;
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
        int y = 40;
        int step = 22;
        Component title = Component.literal("§l" + e.name);
        g.text(this.font, title, cx - this.font.width(title) / 2, 16, 0xFFFFFFFF);
        label(g, "Status", lx, y);
        label(g, "Preis ($)", lx, y + step);
        label(g, "Preis gilt", lx, y + step * 2);
        label(g, "Mindestmenge", lx, y + step * 3);
        label(g, "Menge in", lx, y + step * 4);
        label(g, "Priorität", lx, y + step * 5);

        String info = e.price <= 0
            ? "§cKein Preis gesetzt - es wird nichts verkauft."
            : "§7Verkauft ab §f" + e.minAmount + (e.minInStacks ? " Stacks" : " Items")
                + " §7für §f" + SellSettingsScreen.money(e.price) + " $ " + (e.perStack ? "pro Stack" : "pro Item");
        Component c = Component.literal(info);
        g.text(this.font, c, cx - this.font.width(c) / 2, y + step * 6 + 36, 0xFFFFFFFF);
    }

    private void label(GuiGraphicsExtractor g, String s, int x, int y) {
        g.text(this.font, Component.literal(s), x, y + 6, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        de.multiah.Seller.guiOpen = false;
    }
}
