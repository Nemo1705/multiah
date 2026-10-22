package de.multiah.gui;

import de.multiah.Config;
import de.multiah.PlayerLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Player-Log: Liste der TPA-Anfragen mit Suche und Aktionen (/ec, /invsee, kopieren, entfernen). */
public class PlayerLogScreen extends BaseScreen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private String query = "";
    private int page = 0;
    private PlayerLog.Entry selected = null;
    private String status = "";

    public PlayerLogScreen(Screen parent) {
        super("Player-Log", parent);
    }

    private int perPage() {
        return Math.max(3, Math.min(8, (this.height - 190) / 22));
    }

    private static String time(long t) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).format(TIME);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - 150;

        // Suche
        EditBox search = new EditBox(this.font, left, 34, 190, 18, Component.literal("Suche"));
        search.setMaxLength(20);
        search.setValue(query);
        search.setResponder(s -> query = s);
        this.addRenderableWidget(search);
        this.addRenderableWidget(Button.builder(Component.literal("Suchen"), b -> {
            page = 0;
            this.rebuildWidgets();
        }).bounds(left + 194, 33, 52, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Leeren"), b -> {
            PlayerLog.clear();
            selected = null;
            status = "§aLog geleert.";
            this.rebuildWidgets();
        }).bounds(left + 250, 33, 50, 20).build());

        // Liste
        List<PlayerLog.Entry> rows = PlayerLog.filtered(query);
        int perPage = perPage();
        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= rows.size()) break;
            PlayerLog.Entry e = rows.get(idx);
            boolean sel = e == selected;
            String label = (sel ? "§a▶ " : "") + "§f" + e.name + " §7" + time(e.time)
                + (e.count > 1 ? " §e×" + e.count : "") + " §8" + e.kind;
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                selected = e;
                this.rebuildWidgets();
            }).bounds(left, 58 + i * 22, 300, 20).build());
        }

        // Blättern
        int rowP = this.height - 88;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            this.rebuildWidgets();
        }).bounds(left, rowP, 30, 20).build());
        Button pageLabel = Button.builder(Component.literal("Seite " + (page + 1) + "/" + pages), b -> { })
            .bounds(left + 32, rowP, 70, 20).build();
        pageLabel.active = false;
        this.addRenderableWidget(pageLabel);
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            this.rebuildWidgets();
        }).bounds(left + 104, rowP, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
            .bounds(left + 200, rowP, 100, 20).build());

        // Aktionen für den ausgewählten Spieler
        int rowA = this.height - 64;
        int rowB = this.height - 40;
        String n = selected == null ? "" : selected.name;
        Button ec = Button.builder(Component.literal("/ec " + n), b -> run("ec")).bounds(left, rowA, 98, 20).build();
        Button inv = Button.builder(Component.literal("/invsee " + n), b -> run("invsee"))
            .bounds(left + 101, rowA, 98, 20).build();
        Button copyName = Button.builder(Component.literal("Name kopieren"), b -> copy(n))
            .bounds(left + 202, rowA, 98, 20).build();
        Button copyEc = Button.builder(Component.literal("/ec kopieren"), b -> copy("/ec " + n))
            .bounds(left, rowB, 98, 20).build();
        Button copyInv = Button.builder(Component.literal("/invsee kopieren"), b -> copy("/invsee " + n))
            .bounds(left + 101, rowB, 98, 20).build();
        Button remove = Button.builder(Component.literal("Entfernen"), b -> {
            PlayerLog.remove(selected);
            selected = null;
            this.rebuildWidgets();
        }).bounds(left + 202, rowB, 98, 20).build();
        for (Button b : new Button[] {ec, inv, copyName, copyEc, copyInv, remove}) {
            b.active = selected != null;
            this.addRenderableWidget(b);
        }
    }

    /** Führt /ec oder /invsee für den ausgewählten Spieler aus (der Server prüft die Berechtigung). */
    private void run(String command) {
        if (selected == null || !PlayerLog.validName(selected.name)) {
            status = "§cUngültiger Spielername.";
            return;
        }
        String full = command + " " + selected.name;
        this.minecraft.setScreenAndShow(null);
        if (this.minecraft.getConnection() != null) this.minecraft.getConnection().sendCommand(full);
    }

    private void copy(String text) {
        this.minecraft.keyboardHandler.setClipboard(text);
        status = "§aKopiert: " + text;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        centered(g, "§l§6PLAYER-LOG (TPA-ANFRAGEN)", 10, 0xFFFFFFFF);
        int total = PlayerLog.size();
        int shown = PlayerLog.filtered(query).size();
        centered(g, "§7" + total + " Anfragen gespeichert" + (shown != total ? " (" + shown + " gefunden)" : ""),
            22, 0xFFAAAAAA);
        if (shown == 0) {
            centered(g, total == 0 ? "Noch keine TPA-Anfragen in dieser Sitzung." : "Keine Treffer für die Suche.",
                90, 0xFFFFFFFF);
        }
        if (!status.isEmpty()) centered(g, status, this.height - 100, 0xFFFFFFFF);
        watermark(g);
    }
}
