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

import java.util.List;

/** Reiter "Verdienst": was dein aktuelles Inventar bei den konfigurierten Items wert wäre. */
public class EarningsScreen extends BaseScreen {
    private int page = 0;

    public EarningsScreen(Screen parent) {
        super("Verdienst", parent);
    }

    private int perPage() {
        return Math.max(3, (this.height - 160) / 18);
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        addTabs(1);
        int left = this.width / 2 - 150;
        int row = this.height - 40;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            this.rebuildWidgets();
        }).bounds(left, row, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            this.rebuildWidgets();
        }).bounds(left + 34, row, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(left + 144, row, 156, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        header(g);
        if (this.minecraft.player == null) return;
        List<Config.Entry> entries = Config.get().entries;
        int perPage = perPage();
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;

        int left = this.width / 2 - 150;
        centered(g, "§l💰 Verdienst §7(was dein Inventar jetzt wert wäre)", 60, 0xFFFFFFFF);

        long total = 0;
        boolean anySell = false;
        for (Config.Entry e : entries) {
            long[] h = AutoSell.holding(e, this.minecraft.player);
            if (e.price > 0) total += h[1];
            if (e.isSell()) anySell = true;
        }

        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx >= entries.size()) break;
            Config.Entry e = entries.get(idx);
            long[] h = AutoSell.holding(e, this.minecraft.player);
            String line = (e.enabled ? "§a● " : "§c● ") + "§f" + e.name + " §7"
                + Fmt.amount(h[0], e.perStack) + " × " + Fmt.money(e.price)
                + " = §a" + Fmt.money(h[1]) + " §7(" + (e.isSell() ? "/sell" : "/ah") + ")";
            g.item(Icons.of(e.id), left, 72 + i * 18);
            g.text(this.font, Component.literal(line), left + 20, 77 + i * 18, 0xFFFFFFFF);
        }
        if (entries.isEmpty()) centered(g, "Noch keine Items konfiguriert.", 90, 0xFFFFFFFF);

        int by = this.height - 74;
        centered(g, "§7Konfigurierte Items: §f" + entries.size() + "   §7Seite " + (page + 1) + "/" + pages,
            by, 0xFFFFFFFF);
        centered(g, "§lPotentieller Gesamtverdienst: §a" + Fmt.money(total), by + 12, 0xFFFFFFFF);
        if (anySell) centered(g, "§7/sell-Preise sind nur Schätzwerte.", by + 24, 0xFFAAAAAA);
        watermark(g);
    }
}
