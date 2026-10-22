package de.multiah.gui;

import de.multiah.Config;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Gemeinsame Basis: Reiter oben (Auto-Sell / Verdienst / Einstellungen), Schließen, Textzeichnen. */
public abstract class BaseScreen extends Screen {
    protected final Screen parent;

    protected BaseScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    protected void addTabs(int active) {
        int left = this.width / 2 - 155;
        String[] names = {"Auto-Sell", "Verdienst", "Einstellungen", "Werkzeug"};
        int[] widths = {74, 66, 86, 72};
        int x = left;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = Button.builder(Component.literal(names[i]), btn -> openTab(idx))
                .bounds(x, 36, widths[i], 18).build();
            b.active = i != active;
            this.addRenderableWidget(b);
            x += widths[i] + 4;
        }
    }

    private void openTab(int i) {
        Config.save();
        BaseScreen s;
        if (i == 0) s = new SellSettingsScreen(parent);
        else if (i == 1) s = new EarningsScreen(parent);
        else if (i == 2) s = new GlobalScreen(parent);
        else s = new DurabilityScreen(parent);
        this.minecraft.setScreenAndShow(s);
    }

    protected void centered(GuiGraphicsExtractor g, String s, int y, int color) {
        Component c = Component.literal(s);
        g.text(this.font, c, this.width / 2 - this.font.width(c) / 2, y, color);
    }

    protected void header(GuiGraphicsExtractor g) {
        centered(g, "§l§6SELL SETTINGS", 10, 0xFFFFFFFF);
        centered(g, "§7Farm-Items automatisch verkaufen", 22, 0xFFAAAAAA);
    }

    protected void watermark(GuiGraphicsExtractor g) {
        centered(g, "Made by Nemo1705 & HighestHunter", this.height - 12, 0xFF808080);
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
