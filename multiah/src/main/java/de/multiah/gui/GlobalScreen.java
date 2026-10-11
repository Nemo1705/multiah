package de.multiah.gui;

import de.multiah.Config;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Allgemeine Einstellungen für Auto-Sell. */
public class GlobalScreen extends Screen {
    private final Screen parent;

    public GlobalScreen(Screen parent) {
        super(Component.literal("Einstellungen"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        Config c = Config.get();
        int cx = this.width / 2;
        int wx = cx - 50;
        int y = 50;

        this.addRenderableWidget(Button.builder(
            Component.literal(c.autoSellEnabled ? "§aAN" : "§cAUS (pausiert)"), b -> {
                c.autoSellEnabled = !c.autoSellEnabled;
                Config.save();
                this.rebuildWidgets();
            }).bounds(wx, y, 150, 20).build());

        // Verzögerung
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            c.delayTicks = Math.max(10, c.delayTicks - 5);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx, y + 28, 30, 20).build());
        Button delay = Button.builder(Component.literal(
            String.format(Locale.GERMANY, "%.2f Sekunden", c.delayTicks / 20.0)), b -> { })
            .bounds(wx + 32, y + 28, 86, 20).build();
        delay.active = false;
        this.addRenderableWidget(delay);
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            c.delayTicks = Math.min(200, c.delayTicks + 5);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx + 120, y + 28, 30, 20).build());

        // Maximale Verkäufe pro Runde
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            c.maxSellsPerRound = Math.max(1, c.maxSellsPerRound - 1);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx, y + 56, 30, 20).build());
        Button max = Button.builder(Component.literal(c.maxSellsPerRound + " Verkäufe"), b -> { })
            .bounds(wx + 32, y + 56, 86, 20).build();
        max.active = false;
        this.addRenderableWidget(max);
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            c.maxSellsPerRound = Math.min(100, c.maxSellsPerRound + 1);
            Config.save();
            this.rebuildWidgets();
        }).bounds(wx + 120, y + 56, 30, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> this.onClose())
            .bounds(cx - 75, y + 100, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int lx = cx - 150;
        Component title = Component.literal("§lAllgemeine Einstellungen");
        g.text(this.font, title, cx - this.font.width(title) / 2, 20, 0xFFFFFFFF);
        g.text(this.font, Component.literal("Auto-Sell"), lx, 56, 0xFFFFFFFF);
        g.text(this.font, Component.literal("Pause pro Verkauf"), lx, 84, 0xFFFFFFFF);
        g.text(this.font, Component.literal("Max. pro Runde"), lx, 112, 0xFFFFFFFF);
        Component hint = Component.literal("§7Eine Runde endet nach dieser Anzahl; kurz danach geht es weiter.");
        g.text(this.font, hint, cx - this.font.width(hint) / 2, 160, 0xFFAAAAAA);
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
