package org;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * /unstableffa debug  -  a quick health check in chat:
 *   - server: version, TPS, tick time, memory, players
 *   - which of your systems are on
 *   - mistakes in the config files (unknown sounds/items, menu slots used twice or out of range,
 *     missing worlds, placeholder links, ...)
 *
 * Permission: unstableffa.admin
 * Works on its own: it only needs the plugin's config.
 */
final class DebugInfo {

    private final zenithEssentials plugin;

    DebugInfo(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    void handle(CommandSender s) {
        FileConfiguration c = this.plugin.getConfig();
        s.sendMessage(Announcer.color("&8&m      &r <gradient:#FF7518:#FFFFFF>&lUnstableF</gradient><gradient:#FFFFFF:#FF7518>&lFA debug</gradient> &8&m      "));

        // ---- server
        String tps = "unknown";
        try {
            Method m = Bukkit.class.getMethod("getTPS");
            double[] t = (double[]) m.invoke(null);
            tps = String.format(Locale.ROOT, "%.1f", Math.min(20.0, t[0]));
        } catch (Exception | LinkageError ignored) {
        }
        String mspt = "";
        try {
            Method m = Bukkit.class.getMethod("getAverageTickTime");
            mspt = String.format(Locale.ROOT, " &8| &7Tick &f%.1fms", (double) (Double) m.invoke(null));
        } catch (Exception | LinkageError ignored) {
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        s.sendMessage(Announcer.color("&7Plugin &f" + this.plugin.getDescription().getVersion() + " &8| &7Server &f" + Bukkit.getBukkitVersion()));
        s.sendMessage(Announcer.color("&7TPS &f" + tps + mspt + " &8| &7Memory &f" + used + "&7/&f" + max + " MB &8| &7Players &f"
                + Bukkit.getOnlinePlayers().size() + "&7/&f" + Bukkit.getMaxPlayers()));

        // ---- systems
        String[][] systems = {
            {"Anti-cuss", "anti-cuss.enabled", "true"}, {"Flood filter", "chat-flood.enabled", "false"},
            {"Slur filter", "chat-filter.enabled", "true"}, {"@ mentions", "chat-extras.mentions.enabled", "false"},
            {"Combat", "combat.enabled", "true"}, {"Modmode", "modmode.enabled", "true"},
            {"Welcome", "welcome.enabled", "true"}, {"First-join", "first-join.enabled", "false"},
            {"Rules", "rules.enabled", "true"}, {"Guide", "guide.enabled", "true"},
            {"Quests", "quests.enabled", "true"}, {"Rewards", "playtime-rewards.enabled", "true"},
            {"Leaderboards", "top.enabled", "true"}, {"Unstable menu", "zenithmenu.enabled", "true"},
            {"Discord", "discord-webhook.enabled", "false"}, {"Backups", "backups.enabled", "true"},
        };
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (String[] sys : systems) {
            boolean on = c.getBoolean(sys[1], Boolean.parseBoolean(sys[2]));
            sb.append(on ? "&#FF9147" : "&#D9381E").append(sys[0]).append("&8 | ");
            if (++col % 4 == 0) {
                s.sendMessage(Announcer.color(sb.toString()));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            s.sendMessage(Announcer.color(sb.toString()));
        }
        s.sendMessage(Announcer.color("&7Green = on, red = off. Disabled commands: &f" + c.getStringList("commands.disabled").size()));

        // ---- config problems
        List<String> problems = new ArrayList<>();
        for (String p : new String[] {"welcome.sound", "welcome.back.sound", "announce.sound", "chat-extras.mentions.sound", "zenithmenu.click-sound"}) {
            this.sound(problems, p, c.getString(p, ""));
        }
        ConfigurationSection types = c.getConfigurationSection("announce.types");
        if (types != null) {
            for (String k : types.getKeys(false)) {
                this.sound(problems, "announce.types." + k + ".sound", types.getString(k + ".sound", ""));
            }
        }
        this.menuItems(problems, "zenithmenu.items", c.getConfigurationSection("zenithmenu.items"), 54, 49);
        ConfigurationSection menus = c.getConfigurationSection("zenithmenu.menus");
        if (menus != null) {
            for (String m : menus.getKeys(false)) {
                this.menuItems(problems, "zenithmenu.menus." + m + ".items", menus.getConfigurationSection(m + ".items"), 54, 49);
            }
        }
        ConfigurationSection cats = c.getConfigurationSection("top.categories");
        if (cats != null) {
            int slots = Math.max(1, Math.min(6, c.getInt("top.menu-rows", 3))) * 9;
            this.menuItems(problems, "top.categories", cats, slots, -1);
        }
        ConfigurationSection tpl = c.getConfigurationSection("announce.templates");
        if (tpl != null) {
            List<String> known = new ArrayList<>(java.util.Arrays.asList("event", "warning", "update"));
            if (types != null) {
                known.addAll(types.getKeys(false));
            }
            for (String k : tpl.getKeys(false)) {
                String type = tpl.getString(k + ".type", "").toLowerCase(Locale.ROOT);
                if (!type.isEmpty() && !known.contains(type)) {
                    problems.add("announce.templates." + k + ".type: unknown type '" + type + "'");
                }
                String target = tpl.getString(k + ".target", "all");
                if (target.toLowerCase(Locale.ROOT).startsWith("world:") && Bukkit.getWorld(target.substring(6)) == null) {
                    problems.add("announce.templates." + k + ".target: world '" + target.substring(6) + "' doesn't exist");
                }
            }
        }
        for (String p : new String[] {"discord-command.link", "announce.templates.discord.link"}) {
            if (c.getString(p, "").contains("your-invite")) {
                problems.add(p + ": still the placeholder invite");
            }
        }
        for (String p : new String[] {"zenithmenu.items.discord.link"}) {
            if (c.getString(p, "").contains("your-invite")) {
                problems.add(p + ": still the placeholder invite");
            }
        }
        if (c.getBoolean("first-join.enabled", false) && c.getStringList("first-join.steps").isEmpty()) {
            problems.add("first-join.steps: empty, nobody would see the steps");
        }
        if (c.getBoolean("discord-webhook.enabled", false) && !c.getString("discord-webhook.url", "").startsWith("http")) {
            problems.add("discord-webhook.url: empty or not a link");
        }

        if (problems.isEmpty()) {
            s.sendMessage(Announcer.color("&#FF9147&lNo config problems found."));
        } else {
            s.sendMessage(Announcer.color("&#D9381E&l" + problems.size() + " config problem(s):"));
            for (int i = 0; i < Math.min(15, problems.size()); i++) {
                s.sendMessage(Announcer.color("&8- &7" + problems.get(i)));
            }
            if (problems.size() > 15) {
                s.sendMessage(Announcer.color("&8... and " + (problems.size() - 15) + " more"));
            }
        }
    }

    private void sound(List<String> problems, String path, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            Sound.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            problems.add(path + ": unknown sound '" + value + "'");
        }
    }

    /** Checks icon names, slot numbers and slots used twice for a list of menu buttons. */
    private void menuItems(List<String> problems, String path, ConfigurationSection items, int size, int reserved) {
        if (items == null) {
            return;
        }
        Map<Integer, String> used = new HashMap<>();
        for (String k : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(k);
            if (sec == null || !sec.getBoolean("enabled", true)) {
                continue;
            }
            for (String key : new String[] {"icon", "icon-off"}) {
                String icon = sec.getString(key, null);
                if (icon != null && !icon.equalsIgnoreCase("PLAYER_HEAD") && Material.matchMaterial(icon) == null) {
                    problems.add(path + "." + k + "." + key + ": unknown item '" + icon + "'");
                }
            }
            int slot = sec.getInt("slot", -1);
            if (slot < 0 || slot >= size) {
                problems.add(path + "." + k + ".slot: " + slot + " is outside the menu (0-" + (size - 1) + ")");
            } else if (slot == reserved) {
                problems.add(path + "." + k + ".slot: " + slot + " is the Close button");
            } else if (used.containsKey(slot)) {
                // two buttons may share a slot only if one is hidden by permissions - still worth a look
                problems.add(path + "." + k + ".slot: " + slot + " is also used by '" + used.get(slot) + "'");
            } else {
                used.put(slot, k);
            }
        }
    }
}
