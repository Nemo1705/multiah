package de.multiah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Dauerhafte Einstellungen (Datei: config/multiah.json im Minecraft-Ordner). */
public class Config {
    public boolean autoSellEnabled = false; // Sicherheit: standardmäßig AUS
    public int delayTicks = 20;             // Pause zwischen zwei Verkäufen (20 = 1 Sekunde)
    public int maxSellsPerRound = 27;       // maximale Verkäufe pro Auto-Sell-Runde
    public List<Entry> entries = new ArrayList<>();

    public static class Entry {
        public String id = "";          // z.B. minecraft:cobblestone
        public String name = "";        // Anzeigename
        public boolean enabled = false; // Auto-Sell für dieses Item an/aus
        public long price = 0;          // Preis (0 = nicht gesetzt -> es wird nie verkauft)
        public boolean perStack = true; // true = Preis pro Stack, false = Preis pro Item
        public int minAmount = 1;       // Mindestmenge
        public boolean minInStacks = true; // true = Mindestmenge in Stacks, false = in Items
        public int priority = 0;        // höhere Zahl = wird zuerst verkauft

        public Entry() {}

        public Entry(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public int thresholdItems(int maxStack) {
            long t = minInStacks ? (long) minAmount * maxStack : minAmount;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, t));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("multiah.json");
    }

    public static Config get() {
        if (instance == null) load();
        return instance;
    }

    private static void load() {
        try {
            if (Files.exists(path())) {
                instance = GSON.fromJson(Files.readString(path()), Config.class);
            }
        } catch (Exception e) {
            instance = null;
        }
        if (instance == null) instance = new Config();
        if (instance.entries == null) instance.entries = new ArrayList<>();
        instance.delayTicks = Math.max(10, Math.min(200, instance.delayTicks));
        instance.maxSellsPerRound = Math.max(1, Math.min(100, instance.maxSellsPerRound));
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(get()));
        } catch (Exception ignored) {
        }
    }
}
