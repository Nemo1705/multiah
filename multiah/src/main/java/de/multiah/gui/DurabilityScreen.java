package de.multiah.gui;

import de.multiah.Config;
import de.multiah.DurabilityGuard;
import de.multiah.Seller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reiter "Werkzeug": Einstellungen des Durability Guard. */
public class DurabilityScreen extends BaseScreen {

    public DurabilityScreen(Screen parent) {
        super("Werkzeug-Schutz", parent);
    }

    private void toggle(int x, int y, String label, boolean value, Runnable flip) {
        this.addRenderableWidget(Button.builder(
            Component.literal(label + ": " + (value ? "§aAN" : "§cAUS")), b -> {
                flip.run();
                Config.save();
                this.rebuildWidgets();
            }).bounds(x, y, 150, 20).build());
    }

    private void stepper(int x, int y, String text, Runnable dec, Runnable inc) {
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            dec.run();
            Config.save();
            this.rebuildWidgets();
        }).bounds(x, y, 22, 20).build());
        Button value = Button.builder(Component.literal(text), b -> { }).bounds(x + 24, y, 102, 20).build();
        value.active = false;
        this.addRenderableWidget(value);
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            inc.run();
            Config.save();
            this.rebuildWidgets();
        }).bounds(x + 128, y, 22, 20).build());
    }

    @Override
    protected void init() {
        Seller.guiOpen = true;
        addTabs(3);
        Config.Durability d = Config.get().durability;
        int cx = this.width / 2;
        int lx = cx - 155;
        int rx = cx + 5;
        int y0 = 62;
        int st = 22;

        // linke Spalte
        toggle(lx, y0, "Schutz", d.enabled, () -> d.enabled = !d.enabled);
        stepper(lx, y0 + st, "Warnung " + d.warnPercent + " %",
            () -> d.warnPercent = Math.max(1, d.warnPercent <= 10 ? d.warnPercent - 1 : Math.max(10, d.warnPercent - 5)),
            () -> d.warnPercent = Math.min(100, d.warnPercent < 10 ? d.warnPercent + 1 : d.warnPercent + 5));
        stepper(lx, y0 + st * 2, "Mind. " + d.minPoints + " Pkt",
            () -> d.minPoints = Math.max(0, d.minPoints - 10),
            () -> d.minPoints = Math.min(5000, d.minPoints + 10));
        stepper(lx, y0 + st * 3, "Reserve " + d.reservePoints + " Pkt",
            () -> d.reservePoints = Math.max(0, d.reservePoints - 1),
            () -> d.reservePoints = Math.min(50, d.reservePoints + 1));
        String[] modes = {"Nur warnen", "Sperre bei Grenze", "Sperre kurz vor Bruch"};
        this.addRenderableWidget(Button.builder(Component.literal("Abbau: " + modes[d.blockMode]), b -> {
            d.blockMode = (d.blockMode + 1) % 3;
            Config.save();
            this.rebuildWidgets();
        }).bounds(lx, y0 + st * 4, 150, 20).build());

        // rechte Spalte
        toggle(rx, y0, "Chat-Warnung", d.chatWarn, () -> d.chatWarn = !d.chatWarn);
        toggle(rx, y0 + st, "Bildschirm-Warnung", d.screenWarn, () -> d.screenWarn = !d.screenWarn);
        toggle(rx, y0 + st * 2, "Sound", d.sound, () -> d.sound = !d.sound);
        stepper(rx, y0 + st * 3, "3x3 kostet " + d.starCost,
            () -> d.starCost = Math.max(1, d.starCost - 1),
            () -> d.starCost = Math.min(30, d.starCost + 1));
        EditBox star = new EditBox(this.font, rx, y0 + st * 4 + 12, 150, 18, Component.literal("Name"));
        star.setMaxLength(30);
        star.setValue(d.starName);
        star.setResponder(s -> d.starName = s.trim());
        this.addRenderableWidget(star);

        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(cx - 75, this.height - 40, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        centered(g, "§l§6WERKZEUG-SCHUTZ", 10, 0xFFFFFFFF);
        centered(g, "§7Warnt vor dem Zerbrechen und sperrt riskante Abbauvorgänge", 22, 0xFFAAAAAA);
        int cx = this.width / 2;
        int y0 = 62;
        int st = 22;
        g.text(this.font, Component.literal("Star-Picke erkennen am Namen:"), cx + 5, y0 + st * 4 + 2, 0xFFFFFFFF);
        var p = this.minecraft.player;
        String tool = p == null ? "" : DurabilityGuard.describe(p.getMainHandItem());
        centered(g, "§7In der Hand: §f" + tool, y0 + st * 5 + 18, 0xFFFFFFFF);
        centered(g, "§7Gemessen: ein 3x3-Abbau kostet 1 Haltbarkeitspunkt", y0 + st * 5 + 30, 0xFFAAAAAA);
        watermark(g);
    }
}
