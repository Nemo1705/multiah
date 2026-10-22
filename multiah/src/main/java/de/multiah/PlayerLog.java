package de.multiah;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player-Log: erkennt eingehende TPA-Anfragen in den Server-Nachrichten und speichert sie.
 * Alles bleibt lokal auf deinem Computer, es wird nichts an einen Server gesendet.
 * Es wird nichts erfunden: gespeichert wird nur, was eindeutig zu einem Muster passt.
 */
public final class PlayerLog {
    public static class Entry {
        public String name = "";
        public long time = 0;
        public int count = 1;
        public String kind = "TPA";
    }

    private record Pat(Pattern pattern, String kind) {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int MAX_ENTRIES = 200;
    private static final long DEDUP_MS = 10_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Eingebaute Muster (deutsch und englisch). Gruppe 1 = Spielername. */
    private static final List<Pat> BUILTIN = List.of(
        new Pat(Pattern.compile("(\\w{3,16}) (?:möchte|moechte|will) (?:sich )?zu dir teleportieren", FLAGS), "TPA"),
        new Pat(Pattern.compile("(\\w{3,16}) (?:möchte|moechte|will) dich zu sich teleportieren", FLAGS), "TPAHERE"),
        new Pat(Pattern.compile("(\\w{3,16}) (?:möchte|moechte|will),? dass du dich zu (?:ihm|ihr) teleportierst", FLAGS), "TPAHERE"),
        new Pat(Pattern.compile("(\\w{3,16}) (?:has requested to teleport to you|wants to teleport to you|would like to teleport to you)", FLAGS), "TPA"),
        new Pat(Pattern.compile("(\\w{3,16}) (?:has requested that you teleport to (?:them|him|her)|wants you to teleport to (?:them|him|her))", FLAGS), "TPAHERE"),
        new Pat(Pattern.compile("(?:teleport(?:ation)?[- ]?(?:request|anfrage)|tpa(?:-anfrage)?)\\s+(?:von|from)\\s+(\\w{3,16})", FLAGS), "TPA"),
        new Pat(Pattern.compile("(\\w{3,16}) (?:hat dir eine|sent you a) (?:tpa|teleport)[- ]?(?:anfrage|request)", FLAGS), "TPA")
    );

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded = false;

    private PlayerLog() {}

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Wird für jede eingehende Server-Nachricht aufgerufen (nur Hauptthread). */
    public static void onMessage(String text) {
        Config.PlayerLogSettings cfg = Config.get().playerLog;
        if (!cfg.enabled || text == null || text.isBlank()) return;
        if (text.length() > 500) text = text.substring(0, 500); // Schutz vor riesigen Nachrichten
        ensureLoaded();

        if (cfg.debug) {
            String low = text.toLowerCase(Locale.ROOT);
            if (low.contains("tp") || low.contains("teleport")) {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) p.sendSystemMessage(Component.literal("[PlayerLog] Gesehen: " + text));
            }
        }

        for (Pat pat : BUILTIN) {
            Matcher m = pat.pattern().matcher(text);
            if (m.find() && validName(m.group(1))) {
                add(m.group(1), pat.kind());
                return;
            }
        }
        for (String regex : cfg.customPatterns) {
            try {
                Matcher m = Pattern.compile(regex, FLAGS).matcher(text);
                if (m.find() && m.groupCount() >= 1 && validName(m.group(1))) {
                    add(m.group(1), "TPA");
                    return;
                }
            } catch (Exception ignored) {
                // ungültiges eigenes Muster: ignorieren
            }
        }
    }

    private static void add(String name, String kind) {
        long now = System.currentTimeMillis();
        // gleiche Anfrage kurz hintereinander = derselbe Eintrag
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry e = ENTRIES.get(i);
            if (e.name.equalsIgnoreCase(name) && e.kind.equals(kind) && now - e.time < DEDUP_MS) {
                e.time = now;
                return;
            }
            if (now - e.time >= DEDUP_MS) break;
        }
        Entry e = new Entry();
        e.name = name;
        e.kind = kind;
        e.time = now;
        // gleicher Spieler früher schon da: Zähler erhöhen, aber als neuer Eintrag oben zeigen
        for (Entry old : ENTRIES) {
            if (old.name.equalsIgnoreCase(name)) e.count = Math.max(e.count, old.count + 1);
        }
        ENTRIES.add(e);
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
        save();
        if (Config.get().playerLog.notify) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                p.sendOverlayMessage(Component.literal("§e⚠ " + kind + "-Anfrage von §f" + name));
            }
        }
    }

    /** Einträge, neueste zuerst, optional nach Name gefiltert. */
    public static List<Entry> filtered(String query) {
        ensureLoaded();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry e = ENTRIES.get(i);
            if (q.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(q)) out.add(e);
        }
        return out;
    }

    public static int size() {
        ensureLoaded();
        return ENTRIES.size();
    }

    public static void remove(Entry e) {
        ENTRIES.remove(e);
        save();
    }

    public static void clear() {
        ENTRIES.clear();
        save();
    }

    // ---- optionale lokale Speicherung (standardmäßig aus) ----

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("multiah-playerlog.json");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Config.get().playerLog.persist) return;
        try {
            if (Files.exists(path())) {
                List<Entry> list = GSON.fromJson(Files.readString(path()), new TypeToken<List<Entry>>() {}.getType());
                if (list != null) {
                    for (Entry e : list) {
                        if (e != null && validName(e.name)) ENTRIES.add(e);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        if (!Config.get().playerLog.persist) return;
        try {
            Files.writeString(path(), GSON.toJson(ENTRIES));
        } catch (Exception ignored) {
        }
    }
}
