package de.multiah.gui;

import de.multiah.AutoSell;
import de.multiah.Config;
import de.multiah.Fmt;
import de.multiah.Icons;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Reiter "Farmen / Auto-Sell": Übersicht aller Einträge mit Item-Icons. */
public class SellSettingsScreen extends BaseScreen {
    private int page = 0;
    private String status = "";
    private final List<Config.Entry> shown = new ArrayList<>();

    public SellSettingsScreen(Screen parent) {
        super("SellSettings", parent);
    }

    private int perPage() {
        return Math.max(2, Math.min(8, (this.height - 144) / 24));
    }

    private static String label(Config.Entry e) {
        return (e.enabled ? "§a● " : "§c● ") + "§f" + e.name + " §7· §f" + Fmt.money(e.price)
            + (e.perStack ? "/Stack" : "/Item") + " §7· ab §f" + Fmt.amount(e.minAmount, e.perStack)
            + " §7· " + (e.isSell() ? "§a/sell" : "§e/ah");
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        addTabs(0);
        Config c = Config.get();
        int left = this.width / 2 - 150;
        int perPage = perPage();
        int pages = Math.max(1, (c.entries.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int start = page * perPage;

        shown.clear();
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= c.entries.size()) break;
            Config.Entry e = c.entries.get(idx);
            shown.add(e);
            int y = 60 + i * 24;
            this.addRenderableWidget(Button.builder(Component.literal(label(e)),
                b -> this.minecraft.setScreenAndShow(new EntryScreen(this, e)))
                .bounds(left + 22, y, 203, 20).build());
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
                if (!c.autoSellEnabled) Seller.stop();
                Config.save();
                this.rebuildWidgets();
            }).bounds(left, row2, 140, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(left + 144, row2, 156, 20).build());
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
        header(g);
        int left = this.width / 2 - 150;
        for (int i = 0; i < shown.size(); i++) {
            g.item(Icons.of(shown.get(i).id), left + 2, 60 + i * 24 + 2);
        }
        if (Config.get().entries.isEmpty()) {
            centered(g, "Noch keine Items. Nimm ein Item in die Hand", 90, 0xFFFFFFFF);
            centered(g, "und klicke auf \"+ Item aus Hand\".", 102, 0xFFFFFFFF);
        }
        if (!status.isEmpty()) centered(g, status, this.height - 78, 0xFFFFFFFF);
        watermark(g);
    }
}
