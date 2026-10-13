package de.multiah.gui;

import de.multiah.Config;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Reiter "Einstellungen": Auto-Sell an/aus, Pause pro Verkauf, Befehl für /sell. */
public class GlobalScreen extends BaseScreen {

    public GlobalScreen(Screen parent) {
        super("Einstellungen", parent);
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        addTabs(2);
        Config c = Config.get();
        int cx = this.width / 2;
        int wx = cx - 50;
        int y = 66;

        this.addRenderableWidget(Button.builder(
            Component.literal(c.autoSellEnabled ? "§aAN" : "§cAUS (pausiert)"), b -> {
                c.autoSellEnabled = !c.autoSellEnabled;
                if (!c.autoSellEnabled) Seller.stop();
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y, 150, 20).build());

        // Pause pro Verkauf
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            c.delayTicks = Math.max(10, c.delayTicks - 5);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx, y + 26, 30, 20).build());
        Button delay = Button.builder(Component.literal(
            String.format(Locale.GERMANY, "%.2f Sekunden", c.delayTicks / 20.0)), b -> { })
            .bounds(wx + 32, y + 26, 86, 20).build();
        delay.active = false;
        this.addRenderableWidget(delay);
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            c.delayTicks = Math.min(200, c.delayTicks + 5);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx + 120, y + 26, 30, 20).build());

        // Befehl für die Methode /sell
        EditBox cmd = new EditBox(this.font, wx, y + 52, 150, 20, Component.literal("Befehl"));
        cmd.setMaxLength(60);
        cmd.setValue(c.sellCommand == null ? "" : c.sellCommand);
        cmd.setResponder(s -> c.sellCommand = s.trim().replaceFirst("^/+", ""));
        this.addRenderableWidget(cmd);

        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(cx - 75, y + 96, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        header(g);
        int lx = this.width / 2 - 150;
        int y = 66;
        g.text(this.font, Component.literal("Auto-Sell"), lx, y + 6, 0xFFFFFFFF);
        g.text(this.font, Component.literal("Pause pro Verkauf"), lx, y + 32, 0xFFFFFFFF);
        g.text(this.font, Component.literal("Befehl für /sell"), lx, y + 58, 0xFFFFFFFF);
        centered(g, "§7Teste den /sell-Befehl vorher von Hand im Chat!", y + 80, 0xFFAAAAAA);
        watermark(g);
    }
}
