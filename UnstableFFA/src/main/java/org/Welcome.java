package org;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Welcome message for players who join the server for the FIRST time:
 * a title on screen, a sound, a chat message, fireworks, and a line for everyone else on the server.
 * Players who come BACK get a big "Welcome back, last seen 2 days ago" chat message (welcome.back).
 *
 *   /welcome [player]     shows the welcome message to yourself (or someone else) to test it
 *
 * Placeholders in config.yml -> welcome:   {player}  {number} (what number player they are, 20)  {ordinal} (20th)  {server}  {online}
 * Colors: &a, &#FFD21F and <gradient:#AAAAAA:#BBBBBB>text</gradient>
 *
 * Permission:  unstableffa.welcome (for /welcome only, the message itself needs no permission)
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class Welcome implements Listener {

    private final zenithEssentials plugin;
    private final Set<UUID> ourFireworks = new HashSet<>();
    private final Random rnd = new Random();

    Welcome(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!this.plugin.getConfig().getBoolean("welcome.enabled", true)) {
            return;
        }
        final UUID id = p.getUniqueId();
        int version = this.plugin.data().getInt("first-join-version", 0); // goes up with every /kickall
        boolean pending = this.plugin.data().getString("welcome-pending." + id, null) != null;
        // somebody who was here before a /kickall and has not been through the welcome since
        boolean afterKickAll = p.hasPlayedBefore() && version > 0
                && this.plugin.data().getInt("first-join-seen." + id, 0) < version
                && this.plugin.data().getInt("welcome-kickall-seen." + id, 0) < version;
        if (p.hasPlayedBefore() && !pending && !afterKickAll) {
            this.welcomeBack(p);
            return;
        }
        boolean realFirst = !p.hasPlayedBefore();
        if (realFirst && !pending) {
            // Remember that this player was welcomed, so quick reconnects don't show it twice.
            String mark = "welcomed." + id;
            if (this.plugin.data().getBoolean(mark, false)) {
                return;
            }
            this.plugin.data().set(mark, true);
            this.plugin.data().set("welcome-numbers." + id, this.nextNumber(p));
        }
        if (!pending) {
            // "first" = a brand new player (everyone gets the broadcast), "kickall" = back after /kickall (only they see it)
            this.plugin.data().set("welcome-pending." + id, realFirst ? "first" : "kickall");
        }
        this.plugin.saveData();
        long delay = Math.max(1L, this.plugin.getConfig().getLong("welcome.delay-ticks", 30L));
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (this.plugin.firstJoinPending(p)) {
                return; // the rules / guide menu is open: afterSteps() shows the welcome when they accept it
            }
            this.deliver(p, 0L);
        }, delay);
    }

    /** Called when a player finishes the rules and guide menus (first join or after /kickall): now they get the welcome. */
    void afterSteps(Player p) {
        if (!this.plugin.getConfig().getBoolean("welcome.enabled", true)
                || this.plugin.data().getString("welcome-pending." + p.getUniqueId(), null) == null) {
            return;
        }
        this.deliver(p, Math.max(1L, this.plugin.getConfig().getLong("welcome.after-steps-delay-ticks", 25L)));
    }

    private void deliver(final Player p, long delay) {
        UUID id = p.getUniqueId();
        final boolean first = "first".equals(this.plugin.data().getString("welcome-pending." + id, "kickall"));
        this.plugin.data().set("welcome-pending." + id, null);
        this.plugin.data().set("welcome-kickall-seen." + id, this.plugin.data().getInt("first-join-version", 0));
        this.plugin.saveData();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline()) {
                this.show(p, first, true);
            }
        }, Math.max(1L, delay));
    }

    // =====================================================================================
    // returning players
    // =====================================================================================

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.plugin.data().set("last-quit." + e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    private static String ago(long ms) {
        long min = Math.max(1L, ms / 60000L);
        long days = min / 1440L;
        long hours = (min % 1440L) / 60L;
        long mins = min % 60L;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + (days == 1 ? " day" : " days"));
        }
        if (hours > 0 && days < 7) {
            parts.add(hours + (hours == 1 ? " hour" : " hours"));
        }
        if (mins > 0 && days == 0) {
            parts.add(mins + (mins == 1 ? " minute" : " minutes"));
        }
        if (parts.isEmpty()) {
            parts.add("1 minute");
        }
        return String.join(" ", parts.subList(0, Math.min(2, parts.size())));
    }

    /** Big, easy to see chat message for someone who has been here before. */
    private void welcomeBack(final Player p) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("welcome.back.enabled", true)) {
            return;
        }
        long last = this.plugin.data().getLong("last-quit." + p.getUniqueId(), 0L);
        long away = last > 0 ? System.currentTimeMillis() - last : -1L;
        long minMin = c.getLong("welcome.back.min-away-minutes", 5L);
        if (away >= 0 && away < minMin * 60000L) {
            return; // quick reconnect
        }
        final String agoText = away < 0 ? "" : ago(away);
        List<String> lines = c.getStringList("welcome.back.lines");
        if (lines.isEmpty()) {
            lines = java.util.Arrays.asList(
                    "",
                    "&8&m                                                    ",
                    "          <gradient:#FF7518:#FFFFFF>&lWELCOM</gradient><gradient:#FFFFFF:#FF7518>&lE BACK</gradient> &f&l{player}",
                    "          &7Last seen &#FF791F{ago} &7ago",
                    "          &7There are &#FF791F{online} &7players online",
                    "&8&m                                                    ",
                    "");
        }
        final List<String> out = new ArrayList<>();
        int number = this.plugin.data().getInt("welcome-numbers." + p.getUniqueId(), 0);
        for (String line : lines) {
            if (agoText.isEmpty() && line.contains("{ago}")) {
                continue; // we don't know when they left
            }
            out.add(this.fill(line.replace("{ago}", agoText), p, number));
        }
        long delay = Math.max(1L, c.getLong("welcome.back.delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            for (String l : out) {
                p.sendMessage(l);
            }
            String sn = this.plugin.getConfig().getString("welcome.back.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
            if (sn != null && !sn.isEmpty()) {
                try {
                    p.playSound(p.getLocation(), Sound.valueOf(sn.toUpperCase(Locale.ROOT)), 1.0F, 1.2F);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }, delay);
    }

    // =====================================================================================
    // fireworks (first join)
    // =====================================================================================

    private static final Color[] FW_COLORS = {Color.fromRGB(0xFFD21F), Color.fromRGB(0xFF5555), Color.fromRGB(0x55FF55),
        Color.fromRGB(0x55FFFF), Color.fromRGB(0xFF55FF), Color.WHITE, Color.ORANGE};

    private void fireworks(final Player p) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("welcome.fireworks.enabled", true)) {
            return;
        }
        final int amount = Math.max(1, Math.min(20, c.getInt("welcome.fireworks.amount", 5)));
        final int every = Math.max(1, c.getInt("welcome.fireworks.every-ticks", 8));
        final double radius = Math.max(1.0, c.getDouble("welcome.fireworks.radius", 4.0));
        for (int i = 0; i < amount; i++) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                Location l = p.getLocation().clone().add((this.rnd.nextDouble() * 2 - 1) * radius, 0.5, (this.rnd.nextDouble() * 2 - 1) * radius);
                Firework fw = p.getWorld().spawn(l, Firework.class);
                this.ourFireworks.add(fw.getUniqueId());
                FireworkMeta m = fw.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).flicker(true).trail(true)
                        .withColor(FW_COLORS[this.rnd.nextInt(FW_COLORS.length)], FW_COLORS[this.rnd.nextInt(FW_COLORS.length)])
                        .withFade(Color.WHITE).build());
                m.setPower(1);
                fw.setFireworkMeta(m);
                final UUID id = fw.getUniqueId();
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.ourFireworks.remove(id), 100L);
            }, (long) i * every);
        }
    }

    /** Our fireworks are only for show - they never hurt anyone. */
    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Firework && this.ourFireworks.contains(d.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.welcome")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        Player target = null;
        if (args.length > 0) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Player '&f" + args[0] + "&7' not found."));
                return;
            }
        } else if (s instanceof Player) {
            target = (Player) s;
        }
        if (target == null) {
            s.sendMessage("Use /welcome <player>.");
            return;
        }
        this.show(target, false);
        s.sendMessage(Announcer.color("&8» &#FF9147&lSuccess! &7Showed the welcome message to &f" + target.getName() + "&7 (test, nothing was saved)."));
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && s.hasPermission("unstableffa.welcome")) {
            String last = args[0].toLowerCase(Locale.ROOT);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(o.getName());
                }
            }
        }
        return out;
    }

    /**
     * The join counter. It goes up by 1 for every new player and is saved in data.yml (welcome-count).
     * The first time it is used it starts from the number of players the plugin already knows.
     */
    private int nextNumber(Player p) {
        int count;
        if (this.plugin.data().contains("welcome-count")) {
            count = this.plugin.data().getInt("welcome-count");
        } else {
            ConfigurationSection users = this.plugin.data().getConfigurationSection("users");
            count = 0;
            if (users != null) {
                for (String k : users.getKeys(false)) {
                    if (!p.getUniqueId().toString().equals(users.getString(k + ".uuid"))) {
                        count++;
                    }
                }
            }
        }
        count++;
        this.plugin.data().set("welcome-count", count);
        return count;
    }

    private static String ordinal(int n) {
        int h = n % 100;
        if (h >= 11 && h <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1:
                return n + "st";
            case 2:
                return n + "nd";
            case 3:
                return n + "rd";
            default:
                return n + "th";
        }
    }

    private void show(Player p, boolean broadcast) {
        this.show(p, broadcast, broadcast || this.plugin.getConfig().getBoolean("welcome.fireworks.on-test", true));
    }

    /** Shows the welcome message. broadcast = also tell everyone (only for a real first join). */
    private void show(Player p, boolean broadcast, boolean withFireworks) {
        FileConfiguration c = this.plugin.getConfig();
        int number = this.plugin.data().getInt("welcome-numbers." + p.getUniqueId(), 0);
        if (number <= 0) { // /welcome on someone from before the counter existed
            number = Math.max(1, this.plugin.data().getInt("welcome-count", 1));
        }

        String title = this.fill(c.getString("welcome.title", "<gradient:#FFD21F:#C3950E>&lWELCOME</gradient>"), p, number);
        String sub = this.fill(c.getString("welcome.subtitle", "&7to &f{server}&7, &f{player}&7!"), p, number);
        if (!title.isEmpty() || !sub.isEmpty()) {
            p.sendTitle(title, sub, c.getInt("welcome.fade-in", 10), c.getInt("welcome.stay", 100), c.getInt("welcome.fade-out", 20));
        }

        String sn = c.getString("welcome.sound", "ENTITY_PLAYER_LEVELUP");
        if (sn != null && !sn.isEmpty()) {
            try {
                p.playSound(p.getLocation(), Sound.valueOf(sn.toUpperCase(Locale.ROOT)), 1.0F, 1.0F);
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (withFireworks) {
            this.fireworks(p);
        }

        String bc = c.getString("welcome.broadcast", "");
        if (bc != null && !bc.isEmpty()) {
            String msg = this.fill(bc, p, number);
            if (broadcast) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    o.sendMessage(msg);
                }
            } else {
                p.sendMessage(msg); // /welcome test: only the player sees it
            }
        }

        for (String line : c.getStringList("welcome.chat")) {
            p.sendMessage(this.fill(line, p, number));
        }
    }

    private String fill(String s, Player p, int number) {
        return Announcer.color(s == null ? "" : s
                .replace("{player}", p.getName())
                .replace("{number}", String.valueOf(number))
                .replace("{ordinal}", ordinal(number))
                .replace("{server}", this.plugin.getConfig().getString("welcome.server-name", "Unstable Events"))
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size())));
    }
}
