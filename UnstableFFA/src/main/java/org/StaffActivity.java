package org;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tracks what each staff member does, per day:
 *   online   minutes online         vanished  minutes vanished
 *   punish   bans/mutes/kicks/warns reports   reports claimed + resolved
 *   appeals  appeals handled        staffchat staff chat messages
 *
 * /staffactivity [days]             everyone, sorted by time online
 * /staffactivity <player> [days]    one staff member, day by day
 *
 * Staff = anyone with the staff-permission from the Discord status embed (unstableffa.staffcount).
 * Saved in plugins/UnstableFFA/staffactivity.yml, kept for 120 days.
 */
public final class StaffActivity {

    private static final String[] METRICS = {"online", "vanished", "punish", "reports", "appeals", "staffchat"};

    private final zenithEssentials plugin;
    private File file;
    private FileConfiguration cfg;
    private BukkitTask ticker;
    private boolean enabled;
    private boolean dirty;
    private int minutes;

    public StaffActivity(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
        this.save();
        this.enabled = this.plugin.getConfig().getBoolean("staff-activity.enabled", true);
        this.file = new File(this.plugin.getDataFolder(), "staffactivity.yml");
        this.cfg = YamlConfiguration.loadConfiguration(this.file);
        this.prune();
        if (this.enabled) {
            this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1200L, 1200L);
        }
    }

    public void save() {
        if (this.cfg == null || !this.dirty) {
            return;
        }
        try {
            this.cfg.save(this.file);
            this.dirty = false;
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save staffactivity.yml: " + ex.getMessage());
        }
    }

    private String today() {
        return LocalDate.now().toString();
    }

    private void add(UUID id, String name, String metric, int amount) {
        if (!this.enabled || this.cfg == null) {
            return;
        }
        String base = id + ".days." + this.today() + "." + metric;
        this.cfg.set(base, this.cfg.getInt(base, 0) + amount);
        this.cfg.set(id + ".name", name);
        this.cfg.set(id + ".last-active", System.currentTimeMillis());
        this.dirty = true;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.isStaff(p)) {
                this.add(p.getUniqueId(), p.getName(), "online", 1);
                if (this.plugin.vanished(p.getUniqueId())) {
                    this.add(p.getUniqueId(), p.getName(), "vanished", 1);
                }
            }
        }
        if (++this.minutes % 5 == 0) {
            this.save();
        }
    }

    private void prune() {
        LocalDate cutoff = LocalDate.now().minusDays(120);
        for (String id : this.cfg.getKeys(false)) {
            ConfigurationSection days = this.cfg.getConfigurationSection(id + ".days");
            if (days == null) {
                continue;
            }
            for (String d : days.getKeys(false)) {
                try {
                    if (LocalDate.parse(d).isBefore(cutoff)) {
                        days.set(d, null);
                        this.dirty = true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- hooks (called from the rest of the plugin) ----------

    // staff is a name (punishments are logged by name); "AutoMod"/"CONSOLE" are skipped
    public void onPunish(String staffName) {
        if (staffName == null) {
            return;
        }
        Player p = Bukkit.getPlayerExact(staffName);
        if (p != null) {
            this.add(p.getUniqueId(), p.getName(), "punish", 1);
        }
    }

    public void onReport(Player p) {
        this.add(p.getUniqueId(), p.getName(), "reports", 1);
    }

    public void onAppeal(Player p) {
        this.add(p.getUniqueId(), p.getName(), "appeals", 1);
    }

    public void onStaffChat(Player p) {
        this.add(p.getUniqueId(), p.getName(), "staffchat", 1);
    }

    // ---------- /staffactivity ----------

    private int[] totals(String id, int days) {
        int[] t = new int[METRICS.length];
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        ConfigurationSection ds = this.cfg.getConfigurationSection(id + ".days");
        if (ds == null) {
            return t;
        }
        for (String d : ds.getKeys(false)) {
            try {
                if (LocalDate.parse(d).isBefore(from)) {
                    continue;
                }
            } catch (Exception ex) {
                continue;
            }
            for (int i = 0; i < METRICS.length; i++) {
                t[i] += ds.getInt(d + "." + METRICS[i], 0);
            }
        }
        return t;
    }

    private String mins(int m) {
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    public void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.staffactivity")) {
            s.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", Collections.emptyMap()));
            return;
        }
        if (!this.enabled) {
            s.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Staff activity tracking is turned off."));
            return;
        }
        this.save();
        int days = 7;
        String who = null;
        for (String a : args) {
            try {
                days = Math.max(1, Math.min(120, Integer.parseInt(a)));
            } catch (NumberFormatException ex) {
                who = a;
            }
        }

        if (who != null) {
            OfflinePlayer t = this.plugin.offline(who);
            String id = t.getUniqueId().toString();
            String name = this.cfg.getString(id + ".name", who);
            int[] tot = this.totals(id, days);
            s.sendMessage(this.plugin.color("&8&m        &r &#FF791F&l" + name + " &7(last " + days + " days) &8&m        "));
            s.sendMessage(this.plugin.color("&7Online &f" + this.mins(tot[0]) + " &8(vanished " + this.mins(tot[1]) + ")"
                    + " &8· &7Punishments &f" + tot[2] + " &8· &7Reports &f" + tot[3]
                    + " &8· &7Appeals &f" + tot[4] + " &8· &7Staff chat &f" + tot[5]));
            for (int i = 0; i < Math.min(days, 14); i++) {
                String d = LocalDate.now().minusDays(i).toString();
                String b = id + ".days." + d + ".";
                if (!this.cfg.contains(id + ".days." + d)) {
                    s.sendMessage(this.plugin.color("&8" + d.substring(5) + " &8» not online"));
                    continue;
                }
                s.sendMessage(this.plugin.color("&7" + d.substring(5) + " &8» &f" + this.mins(this.cfg.getInt(b + "online"))
                        + " &7online, &f" + this.cfg.getInt(b + "punish") + " &7punish, &f" + this.cfg.getInt(b + "reports") + " &7reports"));
            }
            return;
        }

        List<Object[]> rows = new ArrayList<>(); // {name, totals, last-active}
        for (String id : this.cfg.getKeys(false)) {
            int[] tot = this.totals(id, days);
            rows.add(new Object[]{this.cfg.getString(id + ".name", id.substring(0, 8)), tot, this.cfg.getLong(id + ".last-active")});
        }
        rows.sort((a, b) -> Integer.compare(((int[]) b[1])[0], ((int[]) a[1])[0]));
        if (rows.isEmpty()) {
            s.sendMessage(this.plugin.color("&8» &7No staff activity recorded yet."));
            return;
        }
        s.sendMessage(this.plugin.color("&8&m        &r <gradient:#FF7518:#FFFFFF>&lSTAFF A</gradient><gradient:#FFFFFF:#FF7518>&lCTIVITY</gradient> &7(last " + days + " days) &8&m        "));
        for (Object[] r : rows) {
            int[] t = (int[]) r[1];
            long last = (Long) r[2];
            String seen = last == 0L ? "?" : this.plugin.duration(Math.max(60000L, System.currentTimeMillis() - last)) + " ago";
            s.sendMessage(this.plugin.color("&f" + r[0] + " &8» &#FF9147" + this.mins(t[0]) + " &7online &8· &#D9381E" + t[2]
                    + " &7punish &8· &#FF8330" + t[3] + " &7reports &8· &#B15CFF" + t[4] + " &7appeals &8(last active " + seen + ")"));
        }
        s.sendMessage(this.plugin.color("&8/staffactivity <name> [days] for day-by-day details"));
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (this.cfg != null) {
            for (String id : this.cfg.getKeys(false)) {
                out.add(this.cfg.getString(id + ".name", "").toLowerCase(Locale.ROOT).isEmpty() ? id : this.cfg.getString(id + ".name"));
            }
        }
        return out;
    }
}
