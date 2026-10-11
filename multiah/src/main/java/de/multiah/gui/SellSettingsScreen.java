package de.multiah.gui;

import de.multiah.AutoSell;
import de.multiah.Config;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** Hauptfenster: Übersicht aller Auto-Sell-Einträge. */
public class SellSettingsScreen extends Screen {
    private final Screen parent;
    private int page = 0;
    private String status = "";

    public SellSettingsScreen(Screen parent) {
        super(Component.literal("SellSettings"));
        this.parent = parent;
    }

    private int perPage() {
        return Math.max(2, Math.min(8, (this.height - 124) / 24));
    }

    static String money(long v) {
        return String.format(Locale.GERMANY, "%,d", v);
    }

    private static String label(Config.Entry e) {
        return (e.enabled ? "§a● " : "§c● ") + "§f" + e.name + " §7| §f" + money(e.price) + " $"
            + (e.perStack ? "/Stack" : "/Item") + " §7| ab §f" + e.minAmount + (e.minInStacks ? " Stacks" : " Items");
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        Config c = Config.get();
        int cx = this.width / 2;
        int left = cx - 150;
        int perPage = perPage();
        int pages = Math.max(1, (c.entries.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int start = page * perPage;

        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= c.entries.size()) break;
            Config.Entry e = c.entries.get(idx);
            int y = 44 + i * 24;
            this.addRenderableWidget(Button.builder(Component.literal(label(e)),
                b -> this.minecraft.setScreenAndShow(new EntryScreen(this, e)))
                .bounds(left, y, 225, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(e.enabled ? "§aAN" : "§cAUS"), b -> {
                e.enabled = !e.enabled;
                Config.save();
                this.rebuildWidgets();
            }).bounds(left + 230, y, 70, 20).build());
        }

        int row1 = this.height - 64;
        int row2 = this.height - 40;

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            this.rebuildWidgets();
        }).bounds(left, row1, 30, 20).build());
        Button pageLabel = Button.builder(Component.literal("Seite " + (page + 1) + "/" + pages), b -> { })
            .bounds(left + 32, row1, 70, 20).build();
        pageLabel.active = false;
        this.addRenderableWidget(pageLabel);
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            this.rebuildWidgets();
        }).bounds(left + 104, row1, 30, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("+ Item aus Hand"), b -> addFromHand())
            .bounds(left + 140, row1, 160, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Auto-Sell: " + (c.autoSellEnabled ? "§aAN" : "§cAUS")), b -> {
                c.autoSellEnabled = !c.autoSellEnabled;
                Config.save();
                this.rebuildWidgets();
            }).bounds(left, row2, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Einstellungen"),
            b -> this.minecraft.setScreenAndShow(new GlobalScreen(this)))
            .bounds(left + 144, row2, 90, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(left + 238, row2, 62, 20).build());
    }

    private void addFromHand() {
        if (this.minecraft.player == null) return;
        ItemStack hand = this.minecraft.player.getMainHandItem();
        if (hand.isEmpty()) {
            status = "§cNimm erst ein Item in die Hand.";
            return;
        }
        String id = AutoSell.id(hand);
        Config c = Config.get();
        for (Config.Entry e : c.entries) {
            if (e.id.equals(id)) {
                this.minecraft.setScreenAndShow(new EntryScreen(this, e));
                return;
            }
        }
        Config.Entry e = new Config.Entry(id, hand.getHoverName().getString());
        c.entries.add(e);
        Config.save();
        this.minecraft.setScreenAndShow(new EntryScreen(this, e));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        centered(g, "§l§6AUTO-SELL", 14, 0xFFFFFFFF);
        centered(g, "§7Farm-Items automatisch über das AH verkaufen", 26, 0xFFAAAAAA);
        if (Config.get().entries.isEmpty()) {
            centered(g, "Noch keine Items. Nimm ein Item in die Hand", 70, 0xFFFFFFFF);
            centered(g, "und klicke auf \"+ Item aus Hand\".", 82, 0xFFFFFFFF);
        }
        if (!status.isEmpty()) centered(g, status, this.height - 78, 0xFFFFFFFF);
        centered(g, "Made by Nemo1705 & HighestHunter", this.height - 12, 0xFF808080);
    }

    private void centered(GuiGraphicsExtractor g, String s, int y, int color) {
        Component c = Component.literal(s);
        g.text(this.font, c, this.width / 2 - this.font.width(c) / 2, y, color);
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        Seller.guiOpen = false;
    }
}
