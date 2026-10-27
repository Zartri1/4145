package org;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends a rotating message to everyone every few minutes.
 * Settings: config.yml -> auto-broadcast   (links in messages are clickable automatically)
 */
public final class AutoBroadcaster {

    private final zenithEssentials plugin;
    private BukkitTask task;
    private List<String> queue = new ArrayList<>();
    private int index = 0;

    public AutoBroadcaster(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("auto-broadcast.enabled", true)) {
            return;
        }
        this.queue = new ArrayList<>(c.getStringList("auto-broadcast.messages"));
        if (this.queue.isEmpty()) {
            return;
        }
        if (c.getBoolean("auto-broadcast.random-order", false)) {
            Collections.shuffle(this.queue);
        }
        this.index = 0;
        long every = Math.max(10L, c.getLong("auto-broadcast.interval-seconds", 300L)) * 20L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::next, every, every);
    }

    private void next() {
        FileConfiguration c = this.plugin.getConfig();
        int online = Bukkit.getOnlinePlayers().size();
        if (online < c.getInt("auto-broadcast.min-players", 1) || this.queue.isEmpty()) {
            return;
        }
        if (this.index >= this.queue.size()) {
            this.index = 0;
            if (c.getBoolean("auto-broadcast.random-order", false)) {
                Collections.shuffle(this.queue);
            }
        }
        String raw = this.queue.get(this.index++);
        String header = c.getString("auto-broadcast.header", "");
        String footer = c.getString("auto-broadcast.footer", "");
        Sound sound = null;
        String sn = c.getString("auto-broadcast.sound", "");
        if (sn != null && !sn.isEmpty()) {
            try {
                sound = Sound.valueOf(sn.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (header != null && !header.isEmpty()) {
                p.sendMessage(this.plugin.color(header));
            }
            for (String line : raw.split("\n")) {
                // fromLegacyText turns links into clickable links
                p.spigot().sendMessage(TextComponent.fromLegacyText(this.plugin.color(line.replace("{player}", p.getName())
                        .replace("{online}", String.valueOf(online)))));
            }
            if (footer != null && !footer.isEmpty()) {
                p.sendMessage(this.plugin.color(footer));
            }
            if (sound != null) {
                p.playSound(p.getLocation(), sound, 0.5F, 1.2F);
            }
        }
    }
}
