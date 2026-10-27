package org;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Killstreak effects: an animated popup for the killer after every kill, a server-wide message on
 * milestones (5, 10, 15, 20 ...), and a message when a big streak gets ended. Halloween theme by default,
 * everything is editable in features.yml -> killstreak, and "enabled: false" turns it all off.
 *
 * The streak itself is counted by StatsMenu (the same number /stats, /leaderboard and the placeholders show);
 * StatsMenu calls onKill() after every kill and death.
 *
 *   /unstableffa killstreak [number]    preview the popup and the milestone message on yourself (admin)
 *
 * Placeholders: {player} {killer} {victim} {streak} {rank}
 * Colors: &a, &#FF7518 and <gradient:#AAAAAA:#BBBBBB>text</gradient>
 * Works on its own: it only needs the plugin's config and the bridge methods in the main class.
 */
final class Killstreak {

    private final zenithEssentials plugin;

    Killstreak(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return this.plugin.getConfig();
    }

    boolean enabled() {
        return this.c().getBoolean("killstreak.enabled", true);
    }

    // ------------------------------------------------------------------ called by StatsMenu

    /**
     * @param killer      the killer, or null when the victim died to something else
     * @param streak      the killer's streak after this kill (0 when killer is null)
     * @param victimStreak the streak the victim had before dying
     */
    void onKill(Player killer, Player victim, int streak, int victimStreak) {
        if (!this.enabled()) {
            return;
        }
        if (killer != null && this.disabledIn(killer)) {
            return;
        }
        if (killer != null) {
            this.popup(killer, victim.getName(), streak);
            this.deathEffect(victim.getLocation());
            if (this.isMilestone(streak)) {
                this.milestone(killer, streak, true);
            }
        }
        this.ended(killer, victim, victimStreak);
    }

    private boolean disabledIn(Player p) {
        return this.c().getStringList("killstreak.disabled-worlds").contains(p.getWorld().getName());
    }

    // ------------------------------------------------------------------ popup

    private void popup(Player killer, String victimName, int streak) {
        ConfigurationSection s = this.c().getConfigurationSection("killstreak.kill-popup");
        if (s == null || !s.getBoolean("enabled", true)) {
            return;
        }
        List<String> frames = s.getStringList("frames");
        if (frames.isEmpty()) {
            frames = new ArrayList<>();
            frames.add("&#FF7518&l+1 KILL");
        }
        final String sub = this.fill(s.getString("subtitle", "&7Streak &#FF7518{streak}"), killer.getName(), victimName, streak);
        final int every = Math.max(1, s.getInt("frame-ticks", 3));
        final int stay = Math.max(1, s.getInt("stay", 30));
        final int fadeOut = Math.max(0, s.getInt("fade-out", 6));
        final int fadeIn = Math.max(0, s.getInt("fade-in", 0));
        final List<String> list = frames;
        for (int i = 0; i < list.size(); i++) {
            final boolean last = i == list.size() - 1;
            final String title = this.fill(list.get(i), killer.getName(), victimName, streak);
            Runnable show = () -> {
                if (killer.isOnline()) {
                    if (last) {
                        killer.sendTitle(title, sub, 0, stay, fadeOut);
                    } else {
                        killer.sendTitle(title, sub, fadeIn, every + 2, 0);
                    }
                }
            };
            if (i == 0) {
                show.run();
            } else {
                Bukkit.getScheduler().runTaskLater(this.plugin, show, (long) i * every);
            }
        }
        float pitch = 1.0F;
        if (s.getBoolean("rising-pitch", true)) {
            pitch = (float) Math.min(2.0, 0.8 + streak * 0.05);
        }
        this.play(killer, s.getString("sound", "ENTITY_BAT_TAKEOFF"), (float) s.getDouble("volume", 1.0), pitch);
    }

    private void deathEffect(Location at) {
        ConfigurationSection s = this.c().getConfigurationSection("killstreak.kill-popup");
        if (s == null || !s.getBoolean("particles", true) || at == null || at.getWorld() == null) {
            return;
        }
        this.particles(at.clone().add(0, 1, 0), s.getStringList("particle-types"), 30);
    }

    // ------------------------------------------------------------------ milestone

    boolean isMilestone(int streak) {
        ConfigurationSection s = this.c().getConfigurationSection("killstreak.milestones");
        if (s == null || !s.getBoolean("enabled", true)) {
            return false;
        }
        int every = Math.max(1, s.getInt("every", 5));
        return streak >= every && streak % every == 0;
    }

    /** @param everyone true = whole server, false = only the killer (used by the preview command) */
    private void milestone(Player killer, int streak, boolean everyone) {
        ConfigurationSection s = this.c().getConfigurationSection("killstreak.milestones");
        if (s == null) {
            return;
        }
        List<String> lines = s.getStringList("lines");
        List<Player> to = new ArrayList<>();
        if (everyone) {
            to.addAll(Bukkit.getOnlinePlayers());
        } else {
            to.add(killer);
        }
        for (String line : lines) {
            String out = this.fill(line, killer.getName(), "", streak);
            for (Player p : to) {
                p.sendMessage(out);
            }
            if (everyone) {
                Bukkit.getConsoleSender().sendMessage(out);
            }
        }
        String t = s.getString("title-everyone", "");
        String st = s.getString("subtitle-everyone", "");
        if (!t.isEmpty() || !st.isEmpty()) {
            for (Player p : to) {
                p.sendTitle(this.fill(t, killer.getName(), "", streak), this.fill(st, killer.getName(), "", streak), 10, 50, 15);
            }
        }
        String sound = s.getString("sound", "ENTITY_WITHER_SPAWN");
        float vol = (float) s.getDouble("volume", 0.5);
        float pitch = (float) s.getDouble("pitch", 1.2);
        for (Player p : to) {
            this.play(p, sound, vol, pitch);
        }
        if (s.getBoolean("lightning", true) && killer.getWorld() != null) {
            killer.getWorld().strikeLightningEffect(killer.getLocation());
        }
        if (s.getBoolean("particles", true)) {
            this.particles(killer.getLocation().add(0, 1, 0), s.getStringList("particle-types"), 80);
        }
    }

    // ------------------------------------------------------------------ streak ended

    private void ended(Player killer, Player victim, int victimStreak) {
        ConfigurationSection s = this.c().getConfigurationSection("killstreak.ended");
        if (s == null || !s.getBoolean("enabled", true) || victimStreak < Math.max(2, s.getInt("min-streak", 5))) {
            return;
        }
        String msg;
        if (killer != null) {
            msg = s.getString("message", "&#B15CFF{killer} &7ended &#FF7518{victim}&7's &#D9381E{streak} &7kill streak.");
        } else if (s.getBoolean("include-environment", true)) {
            msg = s.getString("message-other", "&#FF7518{victim} &7lost their &#D9381E{streak} &7kill streak.");
        } else {
            return;
        }
        String out = this.fill(msg, killer == null ? "" : killer.getName(), victim.getName(), victimStreak);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(out);
        }
        String sound = s.getString("sound", "ENTITY_GHAST_SCREAM");
        if (sound != null && !sound.isEmpty()) {
            this.play(victim, sound, 0.5F, 0.8F);
        }
    }

    // ------------------------------------------------------------------ helpers

    String rankFor(int streak) {
        ConfigurationSection r = this.c().getConfigurationSection("killstreak.ranks");
        String name = this.c().getString("killstreak.default-rank", "Trick or Treater");
        if (r == null) {
            return name;
        }
        TreeMap<Integer, String> map = new TreeMap<>();
        for (String k : r.getKeys(false)) {
            try {
                map.put(Integer.parseInt(k.trim()), r.getString(k, ""));
            } catch (NumberFormatException ignored) {
            }
        }
        Map.Entry<Integer, String> en = map.floorEntry(streak);
        return en == null ? name : en.getValue();
    }

    private String fill(String text, String killer, String victim, int streak) {
        if (text == null) {
            return "";
        }
        return Announcer.color(text
                .replace("{player}", killer)
                .replace("{killer}", killer)
                .replace("{victim}", victim)
                .replace("{streak}", String.valueOf(streak))
                .replace("{rank}", this.rankFor(streak)));
    }

    private void play(Player p, String name, float volume, float pitch) {
        if (name == null || name.isEmpty()) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)), volume, pitch);
        } catch (Exception ignored) {
        }
    }

    private void particles(Location at, List<String> types, int count) {
        List<String> use = types;
        if (use == null || use.isEmpty()) {
            use = new ArrayList<>();
            use.add("SOUL_FIRE_FLAME");
            use.add("WITCH");
        }
        for (String t : use) {
            try {
                at.getWorld().spawnParticle(Particle.valueOf(t.toUpperCase(Locale.ROOT)), at, count, 0.4, 0.6, 0.4, 0.03);
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ /unstableffa killstreak [number]

    void preview(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly a player can preview this.");
            return;
        }
        Player p = (Player) s;
        int streak = 5;
        if (args.length > 1) {
            try {
                streak = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                s.sendMessage("§cUsage: /unstableffa killstreak [number]");
                return;
            }
        }
        if (!this.enabled()) {
            s.sendMessage("§eKillstreak effects are turned off (killstreak.enabled: false in features.yml). Showing a preview anyway.");
        }
        this.popup(p, "Preview", streak);
        this.milestone(p, streak, false);
        s.sendMessage("§7Preview of a §f" + streak + " §7kill streak. Only you see this. "
                + (this.isMilestone(streak) ? "" : "§8(Not a milestone number, so everyone would only get the popup.)"));
    }
}
