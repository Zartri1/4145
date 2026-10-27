package org;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Every settings file lives in ONE folder:  plugins/UnstableFFA/configs/
 *
 *   config.yml       core settings (owners, teleports, homes, lockdown, backups, restart, commands)
 *   combat.yml       combat tag and combat logging
 *   chat.yml         cooldowns, swear filter, chat extras, chat logs, announcements, auto broadcast
 *   punishments.yml  durations, warn escalation, reports, ban evasion
 *   staff.yml        mod mode, screenshare, command spy, lag finder, staff guide
 *   webhooks.yml     Discord webhooks and /livelink
 *   features.yml     welcome, warps, stats, /top, rules, guide, kits, quests, rewards
 *   messages.yml     every message
 *   quests.yml       the quests
 *   kits.yml         the kits
 *
 * (data.yml and the other files the plugin writes itself stay in plugins/UnstableFFA/.)
 *
 * The rest of the plugin does not care which file a setting is in: getConfig() is ONE combined
 * config. Saving (/ze config, /lockdown) writes each value back into the file it came from.
 *
 * Updating from an older version:
 *  - the old plugins/UnstableFFA/config.yml is split into the files above (your values are
 *    kept) and renamed config.yml.old; messages.yml, quests.yml and kits.yml are moved into configs/
 *  - settings that a new version adds are appended to the right file, with their comments
 */
final class Configs {

    static final String DIR = "configs";

    /** file name -> the top-level keys that belong in it (config.yml gets everything else) */
    private static final Map<String, String[]> FILES = new LinkedHashMap<>();
    private static final String[] MOVED = {"messages.yml", "quests.yml", "kits.yml"};

    static {
        FILES.put("combat.yml", new String[] {"combat", "windcharge-tech", "projectile-hitbox"});
        FILES.put("chat.yml", new String[] {"chat-cooldown", "command-cooldowns", "anti-cuss", "chat-flood", "chat-filter",
            "chat-extras", "chat-cleared-broadcast", "nick-max-length", "auto-broadcast", "chat-logs", "announce"});
        FILES.put("punishments.yml", new String[] {"punishment-durations", "warn-escalation", "join-alert-threshold",
            "appeal-info", "reports", "ban-evasion"});
        FILES.put("staff.yml", new String[] {"modmode", "screenshare", "command-spy", "lag-finder", "staff-guide",
            "staff-activity", "punish-limits"});
        FILES.put("webhooks.yml", new String[] {"discord-webhook", "livelink"});
        FILES.put("features.yml", new String[] {"welcome", "first-join", "zenithmenu", "discord-command", "warp-list", "stats-chat", "top", "rules", "guide", "kits",
            "near", "quests", "playtime-rewards", "killstreak", "tpa-gui"});
    }

    private final zenithEssentials plugin;
    private final Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
    private final Map<String, String> owner = new LinkedHashMap<>(); // top-level key -> file name
    private YamlConfiguration merged;
    private boolean building;

    Configs(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    /** A file inside the configs folder. */
    static File file(zenithEssentials plugin, String name) {
        return new File(new File(plugin.getDataFolder(), DIR), name);
    }

    /** Writes the default of a file (from the jar) if it does not exist yet. Returns true if it exists now. */
    static boolean saveDefault(zenithEssentials plugin, String name) {
        File f = file(plugin, name);
        if (f.exists()) {
            return true;
        }
        f.getParentFile().mkdirs();
        try (InputStream in = resource(plugin, name)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not create " + name + ": " + ex.getMessage());
            return false;
        }
    }

    private static InputStream resource(zenithEssentials plugin, String name) {
        InputStream in = plugin.getResource(DIR + "/" + name);
        return in != null ? in : plugin.getResource(name);
    }

    FileConfiguration merged() {
        return this.merged;
    }

    // ------------------------------------------------------------------ setup / migration

    void setup() {
        File dir = new File(this.plugin.getDataFolder(), DIR);
        dir.mkdirs();
        try {
            this.moveOldFiles();
            this.migrateOldConfig();
            this.addMissingSections();
            this.fixBackupList();
        } catch (Exception ex) {
            this.plugin.getLogger().warning("Config update problem: " + ex.getMessage());
        }
        this.rebuild();
    }

    private void moveOldFiles() throws IOException {
        for (String n : MOVED) {
            File old = new File(this.plugin.getDataFolder(), n);
            File now = file(this.plugin, n);
            if (old.isFile() && !now.exists()) {
                Files.move(old.toPath(), now.toPath());
                this.plugin.getLogger().info("Moved " + n + " into the " + DIR + " folder.");
            }
        }
    }

    /** The old single plugins/UnstableFFA/config.yml becomes the files in configs/. */
    private void migrateOldConfig() throws IOException {
        File oldFile = new File(this.plugin.getDataFolder(), "config.yml");
        YamlConfiguration old = oldFile.isFile() ? YamlConfiguration.loadConfiguration(oldFile) : null;
        Set<String> left = new LinkedHashSet<>();
        if (old != null) {
            left.addAll(old.getKeys(false));
        }
        for (Map.Entry<String, String[]> en : FILES.entrySet()) {
            String name = en.getKey();
            if (file(this.plugin, name).exists() || !saveDefault(this.plugin, name)) {
                continue; // already there, or not in the jar (then its settings stay in config.yml)
            }
            for (String k : en.getValue()) {
                left.remove(k);
            }
            if (old != null) {
                this.apply(old, en.getValue(), name);
            }
        }
        File core = file(this.plugin, "config.yml");
        boolean fresh = !core.exists();
        if (fresh) {
            saveDefault(this.plugin, "config.yml");
        }
        if (old != null && fresh) {
            this.apply(old, left.toArray(new String[0]), "config.yml");
        }
        if (old != null) {
            File to = new File(this.plugin.getDataFolder(), "config.yml.old");
            Files.move(oldFile.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.plugin.getLogger().info("Your old config.yml was split into the " + DIR + " folder (kept as config.yml.old).");
        }
    }

    /** Copies the given top-level keys from the old config into a file, only where they differ from its defaults. */
    private void apply(YamlConfiguration old, String[] keys, String name) throws IOException {
        File f = file(this.plugin, name);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        boolean changed = false;
        for (String k : keys) {
            if (!old.contains(k)) {
                continue;
            }
            Object v = old.get(k);
            if (v instanceof ConfigurationSection) {
                for (Map.Entry<String, Object> e : ((ConfigurationSection) v).getValues(true).entrySet()) {
                    if (e.getValue() instanceof ConfigurationSection) {
                        continue;
                    }
                    String path = k + "." + e.getKey();
                    if (!Objects.equals(y.get(path), e.getValue())) {
                        y.set(path, e.getValue());
                        changed = true;
                    }
                }
            } else if (!Objects.equals(y.get(k), v)) {
                y.set(k, v);
                changed = true;
            }
        }
        if (changed) {
            y.save(f);
        }
    }

    /** Sections a new version adds are appended to the matching file, with their comments. */
    private void addMissingSections() throws IOException {
        List<String> names = new ArrayList<>();
        names.add("config.yml");
        names.addAll(FILES.keySet());
        for (String name : names) {
            File f = file(this.plugin, name);
            if (!f.isFile()) {
                continue;
            }
            String bundled;
            try (InputStream in = resource(this.plugin, name)) {
                if (in == null) {
                    continue;
                }
                bundled = new String(readAll(in), StandardCharsets.UTF_8);
            }
            YamlConfiguration have = YamlConfiguration.loadConfiguration(f);
            String[] lines = bundled.split("\r?\n", -1);
            Pattern top = Pattern.compile("^([A-Za-z0-9_-]+):.*");
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (top.matcher(lines[i]).matches()) {
                    idx.add(i);
                }
            }
            StringBuilder add = new StringBuilder();
            List<String> added = new ArrayList<>();
            for (int n = 0; n < idx.size(); n++) {
                int i = idx.get(n);
                String key = lines[i].substring(0, lines[i].indexOf(':'));
                if (have.contains(key) || this.ownedElsewhere(key, name)) {
                    continue;
                }
                int start = i;
                int prevEnd = n == 0 ? 0 : idx.get(n - 1) + 1;
                while (start > prevEnd && lines[start - 1].startsWith("#")) {
                    start--;
                }
                int end = lines.length;
                if (n + 1 < idx.size()) {
                    end = idx.get(n + 1);
                    while (end > i + 1 && lines[end - 1].startsWith("#")) {
                        end--;
                    }
                }
                for (int k = start; k < end; k++) {
                    add.append(lines[k]).append('\n');
                }
                added.add(key);
            }
            if (add.length() == 0) {
                continue;
            }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (!text.endsWith("\n")) {
                text += "\n";
            }
            Files.write(f.toPath(), (text + "\n# ---- added by an update ----\n" + add).getBytes(StandardCharsets.UTF_8));
            this.plugin.getLogger().info("Added new settings to " + name + ": " + String.join(", ", added));
        }
    }

    /** True when the key belongs to another file than the one being checked (only matters for config.yml). */
    private boolean ownedElsewhere(String key, String name) {
        if (!name.equals("config.yml")) {
            return false;
        }
        for (Map.Entry<String, String[]> en : FILES.entrySet()) {
            for (String k : en.getValue()) {
                if (k.equals(key) && file(this.plugin, en.getKey()).exists()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The backup list names the files; they now live in the configs folder. */
    private void fixBackupList() throws IOException {
        File f = file(this.plugin, "config.yml");
        if (!f.isFile()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        List<String> inc = y.getStringList("backups.include");
        if (inc.isEmpty()) {
            return;
        }
        List<String> out = new ArrayList<>(inc);
        boolean changed = out.removeAll(java.util.Arrays.asList("config.yml", "messages.yml", "quests.yml", "kits.yml"));
        if (changed && !out.contains(DIR)) {
            out.add(DIR);
        }
        if (changed) {
            y.set("backups.include", out);
            y.save(f);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ combined view

    /** Reads every settings file again and builds the combined config. */
    void rebuild() {
        if (this.building) {
            return;
        }
        this.building = true;
        try {
            YamlConfiguration m = new YamlConfiguration();
            this.loaded.clear();
            this.owner.clear();
            List<String> names = new ArrayList<>();
            names.add("config.yml");
            names.addAll(FILES.keySet());
            for (String name : names) {
                File f = file(this.plugin, name);
                if (!f.exists()) {
                    continue;
                }
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.load(f);
                } catch (Exception ex) {
                    this.plugin.getLogger().severe(name + " has a mistake and was NOT loaded (" + ex.getMessage()
                            + "). Fix it and use /ze reload. The defaults are used meanwhile.");
                    if (FILES.containsKey(name)) {
                        for (String k : FILES.get(name)) {
                            this.owner.put(k, name); // never write its keys anywhere else
                        }
                    }
                    continue;
                }
                this.loaded.put(name, y);
                if (!name.equals("config.yml")) {
                    for (String k : y.getKeys(false)) {
                        this.owner.put(k, name);
                    }
                }
                copy(y, m);
            }
            this.merged = m;
        } finally {
            this.building = false;
        }
    }

    private static void copy(ConfigurationSection from, ConfigurationSection to) {
        for (Map.Entry<String, Object> e : from.getValues(true).entrySet()) {
            if (e.getValue() instanceof ConfigurationSection) {
                if (((ConfigurationSection) e.getValue()).getKeys(false).isEmpty()) {
                    to.createSection(e.getKey());
                }
            } else {
                to.set(e.getKey(), e.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ saving

    /** Writes every changed value back into the file it belongs to. */
    void save() {
        if (this.merged == null) {
            return;
        }
        Set<String> dirty = new LinkedHashSet<>();
        for (String key : this.merged.getKeys(false)) {
            String file = this.owner.get(key);
            if (file == null) {
                file = "config.yml";
            }
            ConfigurationSection target = this.loaded.get(file);
            if (target == null) {
                continue;
            }
            Object v = this.merged.get(key);
            if (v instanceof ConfigurationSection) {
                for (Map.Entry<String, Object> e : ((ConfigurationSection) v).getValues(true).entrySet()) {
                    if (e.getValue() instanceof ConfigurationSection) {
                        continue;
                    }
                    String path = key + "." + e.getKey();
                    if (!Objects.equals(target.get(path), e.getValue())) {
                        target.set(path, e.getValue());
                        dirty.add(file);
                    }
                }
            } else if (!Objects.equals(target.get(key), v)) {
                target.set(key, v);
                dirty.add(file);
            }
        }
        for (String file : dirty) {
            try {
                this.loaded.get(file).save(file(this.plugin, file));
            } catch (IOException ex) {
                this.plugin.getLogger().warning("Could not save " + file + ": " + ex.getMessage());
            }
        }
    }
}
