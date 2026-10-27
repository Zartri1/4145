package org;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * /announce <all|staff|player> ...  -  a big announcement: a title on screen, a sound and a chat line,
 * plus a line that shows who made the announcement.
 *
 *   /announce all <message>              everyone online
 *   /announce staff <message>            only staff (config.yml -> announce.staff-permission)
 *   /announce player <name> <message>    one player
 *   /announce world <world> <message>    everyone in one world
 *   /announce bar <seconds> <message>    boss bar countdown ({time} in the message = seconds left)
 *   /announce in <10m> <all|...> <msg>   send later        /announce at <20:00> <all|...> <msg>
 *   /announce repeat <times> <every> <all|...> <msg>
 *   /announce template <name>   /announce history   /announce list   /announce cancel <id|all>
 *   Options after the message: --type event|warning|update   --link <url>   --cmd </command>
 *
 * Permission: unstableffa.announce.   Settings: config.yml -> announce
 *
 * /announcerestart <time> [reason]   countdown to a server restart (e.g. 5m, 30s, 1m30s)
 * /announcerestart cancel            stop the countdown
 * Permission: unstableffa.announcerestart.   Settings: config.yml -> restart
 *
 * Works on its own: it only needs the plugin's config.yml.
 */
final class Announcer {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern GRADIENT = Pattern.compile("<gradient:#([A-Fa-f0-9]{6}):#([A-Fa-f0-9]{6})>(.*?)</gradient>");

    private final zenithEssentials plugin;

    // restart countdown
    private BukkitTask restartTask;
    private int restartLeft;
    private String restartReason = "";
    private String restartBy = "";

    Announcer(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    // /announce
    // =====================================================================================

    private static final List<String> KINDS = Arrays.asList("all", "staff", "player", "world", "bar");
    private static final List<String> BUILTIN_TYPES = Arrays.asList("event", "warning", "update");

    /** Extra options typed after the message: --type event|warning|update  --link <url>  --cmd </command> */
    private static final class Opts {
        String type = "";
        String link = "";
        String cmd = "";
    }

    /** One announcement that is ready to be sent (now, later or on repeat). */
    private static final class Plan {
        String kind;       // all | staff | player | world
        String arg = "";   // player name or world name
        int bar = 0;       // > 0 = boss bar for this many seconds
        String message = "";
        Opts opts = new Opts();
    }

    private static final class Sched {
        int id;
        String text;
        long fireAt;       // epoch millis of the next run
        BukkitTask task;
    }

    private final Map<Integer, Sched> scheduled = new LinkedHashMap<>();
    private int nextId = 1;

    private static String usage(String u) {
        return color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f" + u);
    }

    private static String err(String m) {
        return color("&8» &#D9381E&lError! &7" + m);
    }

    private static String ok(String m) {
        return color("&8» &#FF9147&lSuccess! &7" + m);
    }

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.announce")) {
            s.sendMessage(color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (args.length == 0) {
            s.sendMessage(usage("/announce <all|staff|player|world|bar|in|at|repeat|template|history|list|cancel> ..."));
            s.sendMessage(color("&7Options after the message: &f--type event|warning|update  --link <url>  --cmd </command>"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "history":
                this.showHistory(s, args);
                return;
            case "list":
                this.listScheduled(s);
                return;
            case "cancel":
                this.cancelScheduled(s, args);
                return;
            case "template":
                this.runTemplate(s, args);
                return;
            case "in":
            case "at": {
                if (args.length < 4) {
                    s.sendMessage(usage(sub.equals("in") ? "/announce in <10m> <all|staff|player|world|bar> ... <message>"
                            : "/announce at <20:00> <all|staff|player|world|bar> ... <message>"));
                    return;
                }
                long secs = sub.equals("in") ? parseSeconds(args[1]) : secondsUntil(args[1]);
                if (secs <= 0) {
                    s.sendMessage(err(sub.equals("in") ? "Use a time like &f30s&7, &f10m &7or &f1h30m&7." : "Use a time of day like &f20:00&7."));
                    return;
                }
                Plan plan = this.parsePlan(s, args, 2);
                if (plan == null) {
                    return;
                }
                this.schedule(s, plan, secs, 1, 0);
                return;
            }
            case "repeat": {
                if (args.length < 5) {
                    s.sendMessage(usage("/announce repeat <times> <every, e.g. 5m> <all|staff|...> ... <message>"));
                    return;
                }
                int times;
                try {
                    times = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    times = -1;
                }
                long every = parseSeconds(args[2]);
                if (times < 2 || times > 100 || every < 5) {
                    s.sendMessage(err("Times must be 2-100 and the gap at least &f5s&7."));
                    return;
                }
                Plan plan = this.parsePlan(s, args, 3);
                if (plan == null) {
                    return;
                }
                this.schedule(s, plan, 0, times, every);
                return;
            }
            default: {
                Plan plan = this.parsePlan(s, args, 0);
                if (plan == null) {
                    return;
                }
                this.run(s, plan, nameOf(s));
            }
        }
    }

    private String nameOf(CommandSender s) {
        return s instanceof Player ? s.getName() : this.plugin.getConfig().getString("announce.console-name", "Server");
    }

    // ---------- reading what the staff member typed ----------

    /** a[from] = all|staff|player|world|bar, the rest is the message (with optional --options). */
    private Plan parsePlan(CommandSender s, String[] a, int from) {
        if (a.length <= from) {
            s.sendMessage(usage("/announce <all|staff|player|world|bar> ..."));
            return null;
        }
        Plan p = new Plan();
        String kind = a[from].toLowerCase(Locale.ROOT);
        int msgFrom;
        switch (kind) {
            case "all":
            case "staff":
                p.kind = kind;
                msgFrom = from + 1;
                break;
            case "player":
                if (a.length < from + 3) {
                    s.sendMessage(usage("/announce player <name> <message>"));
                    return null;
                }
                p.kind = kind;
                p.arg = a[from + 1];
                msgFrom = from + 2;
                break;
            case "world":
                if (a.length < from + 3) {
                    s.sendMessage(usage("/announce world <world> <message>"));
                    return null;
                }
                if (Bukkit.getWorld(a[from + 1]) == null) {
                    s.sendMessage(err("World '&f" + a[from + 1] + "&7' not found."));
                    return null;
                }
                p.kind = kind;
                p.arg = Bukkit.getWorld(a[from + 1]).getName();
                msgFrom = from + 2;
                break;
            case "bar": {
                if (a.length < from + 3) {
                    s.sendMessage(usage("/announce bar <seconds> <message>"));
                    return null;
                }
                long secs = a[from + 1].matches("\\d+") ? Long.parseLong(a[from + 1]) : parseSeconds(a[from + 1]);
                if (secs < 1 || secs > 3600) {
                    s.sendMessage(err("The bar can last &f1-3600 &7seconds."));
                    return null;
                }
                p.kind = "all";
                p.bar = (int) secs;
                msgFrom = from + 2;
                break;
            }
            default:
                s.sendMessage(err("Use all, staff, player, world or bar."));
                return null;
        }
        // split message words from --options
        List<String> words = new ArrayList<>();
        String cur = null;
        List<String> val = new ArrayList<>();
        for (int i = msgFrom; i < a.length; i++) {
            String t = a[i];
            String low = t.toLowerCase(Locale.ROOT);
            if (low.equals("--type") || low.equals("--link") || low.equals("--cmd")) {
                this.setOpt(p.opts, cur, val);
                cur = low;
                val = new ArrayList<>();
            } else if (cur != null) {
                val.add(t);
            } else {
                words.add(t);
            }
        }
        this.setOpt(p.opts, cur, val);
        p.message = String.join(" ", words).trim();
        if (p.message.isEmpty()) {
            s.sendMessage(err("Write a message."));
            return null;
        }
        if (!p.opts.type.isEmpty() && !this.typeNames().contains(p.opts.type)) {
            s.sendMessage(err("Unknown type. Use: &f" + String.join(", ", this.typeNames())));
            return null;
        }
        if (!p.opts.link.isEmpty() && !p.opts.link.toLowerCase(Locale.ROOT).startsWith("http")) {
            p.opts.link = "https://" + p.opts.link;
        }
        if (!p.opts.cmd.isEmpty() && !p.opts.cmd.startsWith("/")) {
            p.opts.cmd = "/" + p.opts.cmd;
        }
        return p;
    }

    private void setOpt(Opts o, String key, List<String> val) {
        if (key == null || val.isEmpty()) {
            return;
        }
        String v = String.join(" ", val);
        if (key.equals("--type")) {
            o.type = v.toLowerCase(Locale.ROOT);
        } else if (key.equals("--link")) {
            o.link = val.get(0);
        } else {
            o.cmd = v;
        }
    }

    private List<String> typeNames() {
        List<String> out = new ArrayList<>(BUILTIN_TYPES);
        ConfigurationSection sec = this.plugin.getConfig().getConfigurationSection("announce.types");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                if (!out.contains(k.toLowerCase(Locale.ROOT))) {
                    out.add(k.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private String typeValue(String type, String key, String fallback) {
        FileConfiguration c = this.plugin.getConfig();
        if (type.isEmpty()) {
            return c.getString("announce." + key, fallback);
        }
        String v = c.getString("announce.types." + type + "." + key, null);
        if (v != null) {
            return v;
        }
        // built-in looks, used when the type is not in your config
        switch (type) {
            case "event":
                return key.equals("title") ? "<gradient:#FF7518:#FFFFFF>&lEVE</gradient><gradient:#FFFFFF:#FF7518>&lNT</gradient>" : key.equals("sound") ? "UI_TOAST_CHALLENGE_COMPLETE"
                        : key.equals("bar-color") ? "YELLOW" : key.equals("chat-format") ? "&8» <gradient:#FF7518:#FFFFFF>&lEve</gradient><gradient:#FFFFFF:#FF7518>&lnt</gradient> &8• &f{message}" : fallback;
            case "warning":
                return key.equals("title") ? "<gradient:#FF7518:#FFFFFF>&lWARN</gradient><gradient:#FFFFFF:#FF7518>&lING</gradient>" : key.equals("sound") ? "BLOCK_ANVIL_LAND"
                        : key.equals("bar-color") ? "RED" : key.equals("chat-format") ? "&8» <gradient:#FF7518:#FFFFFF>&lWarn</gradient><gradient:#FFFFFF:#FF7518>&ling</gradient> &8• &f{message}" : fallback;
            case "update":
                return key.equals("title") ? "<gradient:#FF7518:#FFFFFF>&lUPD</gradient><gradient:#FFFFFF:#FF7518>&lATE</gradient>" : key.equals("sound") ? "ENTITY_EXPERIENCE_ORB_PICKUP"
                        : key.equals("bar-color") ? "GREEN" : key.equals("chat-format") ? "&8» <gradient:#FF7518:#FFFFFF>&lUpd</gradient><gradient:#FFFFFF:#FF7518>&late</gradient> &8• &f{message}" : fallback;
            default:
                return c.getString("announce." + key, fallback);
        }
    }

    // ---------- sending ----------

    private List<Player> targetsOf(Plan p) {
        List<Player> out = new ArrayList<>();
        switch (p.kind) {
            case "staff": {
                String perm = this.plugin.getConfig().getString("announce.staff-permission", "unstableffa.staffchat");
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (pl.hasPermission(perm)) {
                        out.add(pl);
                    }
                }
                break;
            }
            case "player": {
                Player t = Bukkit.getPlayer(p.arg);
                if (t != null) {
                    out.add(t);
                }
                break;
            }
            case "world": {
                org.bukkit.World w = Bukkit.getWorld(p.arg);
                if (w != null) {
                    out.addAll(w.getPlayers());
                }
                break;
            }
            default:
                out.addAll(Bukkit.getOnlinePlayers());
        }
        return out;
    }

    /** Sends the announcement now. */
    private void run(CommandSender feedback, Plan plan, String senderName) {
        List<Player> targets = this.targetsOf(plan);
        if (plan.kind.equals("player") && targets.isEmpty()) {
            if (feedback != null) {
                feedback.sendMessage(err("Player '&f" + plan.arg + "&7' not found."));
            }
            return;
        }
        FileConfiguration c = this.plugin.getConfig();
        String type = plan.opts.type;
        String text = color(plan.message);
        String title = color(this.typeValue(type, "title", "<gradient:#FFD21F:#C3950E>&lANNOUNCEMENT</gradient>"));
        String chat = color(this.typeValue(type, "chat-format", "&8» <gradient:#FF7518:#FFFFFF>&lAnnoun</gradient><gradient:#FFFFFF:#FF7518>&lcement</gradient> &8• &f{message}")
                .replace("{sender}", senderName)).replace("{message}", text);
        String byLine = color(c.getString("announce.sender-line", "&7Announced by &#FF791F{sender}").replace("{sender}", senderName));
        boolean showSender = c.getBoolean("announce.show-sender", true);
        boolean showTitle = c.getBoolean("announce.show-title", true);
        int fadeIn = c.getInt("announce.fade-in", 10);
        int stay = c.getInt("announce.stay", 100);
        int fadeOut = c.getInt("announce.fade-out", 10);
        Sound sound = null;
        String sn = this.typeValue(type, "sound", "ENTITY_PLAYER_LEVELUP");
        if (sn != null && !sn.isEmpty()) {
            try {
                sound = Sound.valueOf(sn.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                sound = Sound.ENTITY_PLAYER_LEVELUP;
            }
        }

        if (plan.bar > 0) {
            this.showBar(targets, text, type, plan.bar, sound);
        } else {
            for (Player p : targets) {
                if (showTitle) {
                    p.sendTitle(title, text, fadeIn, stay, fadeOut);
                }
                if (sound != null) {
                    p.playSound(p.getLocation(), sound, 1.0F, 1.0F);
                }
                p.sendMessage("");
                this.sendClickable(p, chat, plan.opts);
                if (showSender && !byLine.isEmpty()) {
                    p.sendMessage(byLine);
                }
                p.sendMessage("");
            }
        }
        this.record(senderName, plan);
        if (feedback != null) {
            feedback.sendMessage(ok("Announcement sent to &f" + targets.size() + " &7player(s)."));
        }
    }

    private void sendClickable(Player p, String line, Opts o) {
        if (o.link.isEmpty() && o.cmd.isEmpty()) {
            p.sendMessage(line);
            return;
        }
        String hint = color(this.plugin.getConfig().getString("announce.click-hint", "&7Click to open"));
        String suffix = color(this.plugin.getConfig().getString("announce.click-suffix", " &8[&#FF8330Click&8]"));
        TextComponent comp = new TextComponent(TextComponent.fromLegacyText(line + suffix));
        comp.setClickEvent(new ClickEvent(o.link.isEmpty() ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.OPEN_URL,
                o.link.isEmpty() ? o.cmd : o.link));
        comp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(hint)));
        p.spigot().sendMessage(comp);
    }

    private void showBar(List<Player> targets, String text, String type, int seconds, Sound sound) {
        BarColor bc;
        try {
            bc = BarColor.valueOf(this.typeValue(type, "bar-color", "PURPLE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            bc = BarColor.PURPLE;
        }
        final String template = text;
        final BossBar bar = Bukkit.createBossBar(template.replace("{time}", String.valueOf(seconds)), bc, BarStyle.SOLID);
        for (Player p : targets) {
            bar.addPlayer(p);
            if (sound != null) {
                p.playSound(p.getLocation(), sound, 1.0F, 1.0F);
            }
        }
        final int total = seconds;
        final int[] left = {seconds};
        new BukkitRunnable() {
            @Override
            public void run() {
                left[0]--;
                if (left[0] <= 0) {
                    bar.removeAll();
                    this.cancel();
                    return;
                }
                bar.setProgress(Math.max(0.0, Math.min(1.0, left[0] / (double) total)));
                if (template.contains("{time}")) {
                    bar.setTitle(template.replace("{time}", String.valueOf(left[0])));
                }
            }
        }.runTaskTimer(this.plugin, 20L, 20L);
    }

    // ---------- scheduled / repeat ----------

    private static long secondsUntil(String hhmm) {
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(hhmm.trim());
        if (!m.matches()) {
            return -1;
        }
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        if (h > 23 || mi > 59) {
            return -1;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime at = now.toLocalDate().atTime(h, mi);
        if (!at.isAfter(now)) {
            at = at.plusDays(1);
        }
        return Math.max(1, Duration.between(now, at).getSeconds());
    }

    private void schedule(CommandSender s, Plan plan, long delaySec, int times, long everySec) {
        if (this.scheduled.size() >= 20) {
            s.sendMessage(err("Too many scheduled announcements. Use &f/announce list &7and &f/announce cancel <id>&7."));
            return;
        }
        final Sched sc = new Sched();
        sc.id = this.nextId++;
        final String by = nameOf(s);
        final int[] left = {times};
        String what = times > 1 ? "x" + times + " every " + timeText((int) everySec) : "once";
        sc.text = plan.kind + (plan.arg.isEmpty() ? "" : " " + plan.arg) + (plan.bar > 0 ? " bar" : "") + " (" + what + "): "
                + (plan.message.length() > 40 ? plan.message.substring(0, 40) + "..." : plan.message);
        sc.fireAt = System.currentTimeMillis() + delaySec * 1000L;
        final long gap = everySec;
        Runnable job = () -> {
            this.run(null, plan, by);
            left[0]--;
            if (left[0] <= 0) {
                BukkitTask t = sc.task;
                this.scheduled.remove(sc.id);
                if (t != null && times > 1) {
                    t.cancel();
                }
            } else {
                sc.fireAt = System.currentTimeMillis() + gap * 1000L;
            }
        };
        if (times > 1) {
            sc.task = Bukkit.getScheduler().runTaskTimer(this.plugin, job, delaySec * 20L, everySec * 20L);
        } else {
            sc.task = Bukkit.getScheduler().runTaskLater(this.plugin, job, delaySec * 20L);
        }
        this.scheduled.put(sc.id, sc);
        s.sendMessage(ok("Scheduled as &f#" + sc.id + "&7" + (delaySec > 0 ? " - first one in &f" + timeText((int) Math.min(delaySec, Integer.MAX_VALUE)) : " - starting now")
                + "&7. &8(/announce list, /announce cancel " + sc.id + ")"));
    }

    private void listScheduled(CommandSender s) {
        if (this.scheduled.isEmpty()) {
            s.sendMessage(err("Nothing is scheduled."));
            return;
        }
        s.sendMessage(color("<gradient:#FF7518:#FFFFFF>&lScheduled an</gradient><gradient:#FFFFFF:#FF7518>&lnouncements</gradient>"));
        long now = System.currentTimeMillis();
        for (Sched sc : this.scheduled.values()) {
            int in = (int) Math.max(0, (sc.fireAt - now) / 1000L);
            s.sendMessage(color("&8#" + sc.id + " &7next in &f" + timeText(in) + " &8- &7" + sc.text));
        }
    }

    private void cancelScheduled(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(usage("/announce cancel <id|all>"));
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int n = this.scheduled.size();
            for (Sched sc : this.scheduled.values()) {
                sc.task.cancel();
            }
            this.scheduled.clear();
            s.sendMessage(ok("Cancelled &f" + n + " &7scheduled announcement(s)."));
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1].replace("#", ""));
        } catch (NumberFormatException ex) {
            id = -1;
        }
        Sched sc = this.scheduled.remove(id);
        if (sc == null) {
            s.sendMessage(err("No scheduled announcement with that id."));
            return;
        }
        sc.task.cancel();
        s.sendMessage(ok("Cancelled &f#" + id + "&7."));
    }

    // ---------- templates ----------

    private void runTemplate(CommandSender s, String[] args) {
        ConfigurationSection sec = this.plugin.getConfig().getConfigurationSection("announce.templates");
        if (args.length < 2) {
            s.sendMessage(usage("/announce template <name>"));
            if (sec != null && !sec.getKeys(false).isEmpty()) {
                s.sendMessage(color("&7Templates: &f" + String.join(", ", sec.getKeys(false))));
            }
            return;
        }
        ConfigurationSection t = sec == null ? null : sec.getConfigurationSection(args[1]);
        if (t == null) {
            s.sendMessage(err("No template called '&f" + args[1] + "&7'."));
            return;
        }
        Plan p = new Plan();
        String target = t.getString("target", "all").toLowerCase(Locale.ROOT);
        if (target.startsWith("world:")) {
            org.bukkit.World w = Bukkit.getWorld(target.substring(6));
            if (w == null) {
                s.sendMessage(err("World in this template doesn't exist."));
                return;
            }
            p.kind = "world";
            p.arg = w.getName();
        } else if (target.equals("staff")) {
            p.kind = "staff";
        } else {
            p.kind = "all";
        }
        p.bar = Math.max(0, t.getInt("bar", 0));
        p.message = t.getString("message", "");
        if (p.message.isEmpty()) {
            s.sendMessage(err("That template has no message."));
            return;
        }
        p.opts.type = t.getString("type", "").toLowerCase(Locale.ROOT);
        if (!p.opts.type.isEmpty() && !this.typeNames().contains(p.opts.type)) {
            p.opts.type = "";
        }
        p.opts.link = t.getString("link", "");
        p.opts.cmd = t.getString("command", "");
        this.run(s, p, nameOf(s));
    }

    // ---------- history ----------

    private void record(String sender, Plan plan) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("announce.history.enabled", true)) {
            return;
        }
        List<String> h = new ArrayList<>(this.plugin.data().getStringList("announce-history"));
        String target = plan.kind + (plan.arg.isEmpty() ? "" : " " + plan.arg);
        h.add(System.currentTimeMillis() + "|" + sender + "|" + target + "|" + plan.message.replace("|", "/"));
        int keep = Math.max(5, c.getInt("announce.history.keep", 50));
        while (h.size() > keep) {
            h.remove(0);
        }
        this.plugin.data().set("announce-history", h);
        this.plugin.saveData();
        if (c.getBoolean("announce.history.discord", false)) {
            this.plugin.discordEmbed("announce", "Announcement", 0xFFD21F, new String[][] {
                {"Sender", sender}, {"Sent to", target}, {"Message", plan.message}});
        }
    }

    private void showHistory(CommandSender s, String[] args) {
        List<String> h = this.plugin.data().getStringList("announce-history");
        if (h.isEmpty()) {
            s.sendMessage(err("No announcements yet."));
            return;
        }
        int n = 8;
        if (args.length > 1) {
            try {
                n = Math.max(1, Math.min(20, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        s.sendMessage(color("<gradient:#FF7518:#FFFFFF>&lLast anno</gradient><gradient:#FFFFFF:#FF7518>&luncements</gradient>"));
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm");
        for (int i = Math.max(0, h.size() - n); i < h.size(); i++) {
            String[] p = h.get(i).split("\\|", 4);
            if (p.length < 4) {
                continue;
            }
            long when;
            try {
                when = Long.parseLong(p[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            s.sendMessage(color("&8" + f.format(new Date(when)) + " &f" + p[1] + " &8(" + p[2] + ") &7" + p[3]));
        }
    }

    // =====================================================================================
    // /announcerestart
    // =====================================================================================

    // "5m", "90s", "1m30s", "2" (= minutes) -> seconds, or -1 if it can't be read
    private static int parseSeconds(String in) {
        String v = in.toLowerCase(Locale.ROOT).trim();
        if (v.matches("\\d+")) {
            return Integer.parseInt(v) * 60;
        }
        Matcher m = Pattern.compile("(\\d+)([hms])").matcher(v);
        int total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return -1;
            }
            int n = Integer.parseInt(m.group(1));
            total += m.group(2).equals("h") ? n * 3600 : m.group(2).equals("m") ? n * 60 : n;
            end = m.end();
        }
        return end == v.length() && total > 0 ? total : -1;
    }

    private static String timeText(int sec) {
        if (sec >= 60) {
            int m = sec / 60;
            int s = sec % 60;
            return m + (m == 1 ? " minute" : " minutes") + (s > 0 ? " " + s + (s == 1 ? " second" : " seconds") : "");
        }
        return sec + (sec == 1 ? " second" : " seconds");
    }

    void handleRestart(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.announcerestart")) {
            s.sendMessage(color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (args.length == 0) {
            s.sendMessage(color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/announcerestart <time> [reason] &7or &f/announcerestart cancel"));
            if (this.restartTask != null) {
                s.sendMessage(color("&8» &7A restart is planned in &f" + timeText(this.restartLeft) + "&7."));
            }
            return;
        }
        FileConfiguration c = this.plugin.getConfig();
        if (args[0].equalsIgnoreCase("cancel") || args[0].equalsIgnoreCase("stop")) {
            if (this.restartTask == null) {
                s.sendMessage(color("&8» &#D9381E&lError! &7There is no restart planned."));
                return;
            }
            this.restartTask.cancel();
            this.restartTask = null;
            String sender = s instanceof Player ? s.getName() : c.getString("announce.console-name", "Server");
            Bukkit.broadcastMessage(color(c.getString("restart.cancelled", "&8» <gradient:#FF7518:#FFFFFF>&lRest</gradient><gradient:#FFFFFF:#FF7518>&lart</gradient> &8• &#FF9147The restart was cancelled &7by &f{sender}&7.")
                    .replace("{sender}", sender)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.resetTitle();
            }
            return;
        }
        int seconds = parseSeconds(args[0]);
        if (seconds <= 0) {
            s.sendMessage(color("&8» &#D9381E&lError! &7Use a time like &f5m&7, &f30s &7or &f1m30s&7."));
            return;
        }
        if (this.restartTask != null) {
            this.restartTask.cancel();
        }
        this.restartLeft = seconds;
        this.restartReason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        this.restartBy = s instanceof Player ? s.getName() : c.getString("announce.console-name", "Server");
        this.announceRestart(true);
        this.restartTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickRestart, 20L, 20L);
        s.sendMessage(color("&8» &#FF9147&lSuccess! &7Restart planned in &f" + timeText(seconds) + "&7. &8(/announcerestart cancel)"));
    }

    private void tickRestart() {
        this.restartLeft--;
        if (this.restartLeft <= 0) {
            this.restartTask.cancel();
            this.restartTask = null;
            this.doRestart();
            return;
        }
        List<Integer> warnAt = this.plugin.getConfig().getIntegerList("restart.warn-at");
        if (warnAt.isEmpty()) {
            warnAt = Arrays.asList(1800, 900, 600, 300, 120, 60, 30, 15, 10, 5, 4, 3, 2, 1);
        }
        if (warnAt.contains(this.restartLeft)) {
            this.announceRestart(false);
        }
    }

    private void announceRestart(boolean first) {
        FileConfiguration c = this.plugin.getConfig();
        String time = timeText(this.restartLeft);
        String line = color(c.getString("restart.message", "&8» <gradient:#FF7518:#FFFFFF>&lRest</gradient><gradient:#FFFFFF:#FF7518>&lart</gradient> &8• &7The server restarts in &#D9381E&l{time}&7!")
                .replace("{time}", time).replace("{sender}", this.restartBy));
        String reason = this.restartReason.isEmpty() ? "" : color(c.getString("restart.reason-line", "&7Reason: &f{reason}")
                .replace("{reason}", this.restartReason));
        String by = color(c.getString("restart.sender-line", "&7Announced by &#FF791F{sender}").replace("{sender}", this.restartBy));
        boolean big = first || this.restartLeft <= 10 || this.restartLeft == 60 || this.restartLeft == 300;
        String title = color(c.getString("restart.title", "<gradient:#FF7518:#FFFFFF>&lREST</gradient><gradient:#FFFFFF:#FF7518>&lART</gradient>"));
        String sub = color(c.getString("restart.subtitle", "&7in &f{time}").replace("{time}", time));
        Sound sound = this.restartLeft <= 10 ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BELL;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (first || this.restartLeft > 10) {
                p.sendMessage("");
                p.sendMessage(line);
                if (!reason.isEmpty()) {
                    p.sendMessage(reason);
                }
                if (first && c.getBoolean("announce.show-sender", true)) {
                    p.sendMessage(by);
                }
                p.sendMessage("");
            } else {
                p.sendMessage(line);
            }
            if (big && c.getBoolean("restart.show-title", true)) {
                p.sendTitle(title, sub, 5, this.restartLeft <= 10 ? 25 : 60, 5);
            }
            p.playSound(p.getLocation(), sound, 1.0F, this.restartLeft <= 10 ? 1.6F : 1.0F);
        }
        Bukkit.getConsoleSender().sendMessage("[UnstableFFA] Server restarts in " + time);
    }

    private void doRestart() {
        FileConfiguration c = this.plugin.getConfig();
        String kick = color(c.getString("restart.kick-message", "&#D9381E&lSERVER RESTARTING\n\n&7We'll be back in a minute!")
                .replace("{reason}", this.restartReason));
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            p.kickPlayer(kick);
        }
        Bukkit.savePlayers();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            w.save();
        }
        // "stop" works everywhere (most hosts start the server again by themselves).
        // "restart" only works if spigot.yml -> settings.restart-script is set up.
        String cmd = c.getString("restart.command", "stop");
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd), 20L);
    }

    List<String> tabRestart(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (s.hasPermission("unstableffa.announcerestart") && args.length == 1) {
            out.addAll(Arrays.asList("30s", "1m", "5m", "10m", "cancel"));
            String last = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(x -> !x.startsWith(last));
        }
        return out;
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("unstableffa.announce")) {
            return out;
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        String first = args[0].toLowerCase(Locale.ROOT);
        int n = args.length;
        if (n == 1) {
            out.addAll(Arrays.asList("all", "staff", "player", "world", "bar", "in", "at", "repeat", "template", "history", "list", "cancel"));
        } else if (last.startsWith("-")) {
            out.addAll(Arrays.asList("--type", "--link", "--cmd"));
        } else if (args[n - 2].equalsIgnoreCase("--type")) {
            out.addAll(this.typeNames());
        } else {
            int base = -1; // index of the "all/staff/player/world/bar" word
            if (KINDS.contains(first)) {
                base = 0;
            } else if (first.equals("in") || first.equals("at")) {
                base = 2;
                if (n == 2) {
                    out.addAll(first.equals("in") ? Arrays.asList("30s", "10m", "1h") : Arrays.asList("18:00", "20:00", "22:00"));
                }
            } else if (first.equals("repeat")) {
                base = 3;
                if (n == 2) {
                    out.addAll(Arrays.asList("2", "3", "5", "10"));
                } else if (n == 3) {
                    out.addAll(Arrays.asList("1m", "5m", "10m", "30m"));
                }
            } else if (first.equals("template") && n == 2) {
                ConfigurationSection sec = this.plugin.getConfig().getConfigurationSection("announce.templates");
                if (sec != null) {
                    out.addAll(sec.getKeys(false));
                }
            } else if (first.equals("cancel") && n == 2) {
                out.add("all");
                for (Integer id : this.scheduled.keySet()) {
                    out.add(String.valueOf(id));
                }
            } else if (first.equals("history") && n == 2) {
                out.addAll(Arrays.asList("5", "10", "20"));
            }
            if (base >= 0) {
                String kind = n > base ? args[base].toLowerCase(Locale.ROOT) : "";
                if (n == base + 1) {
                    out.addAll(KINDS);
                } else if (n == base + 2 && kind.equals("player")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        out.add(p.getName());
                    }
                } else if (n == base + 2 && kind.equals("world")) {
                    for (org.bukkit.World w : Bukkit.getWorlds()) {
                        out.add(w.getName());
                    }
                } else if (n == base + 2 && kind.equals("bar")) {
                    out.addAll(Arrays.asList("10", "30", "60"));
                }
            }
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ---------- colors: &a, &#FFD21F and <gradient:#AAAAAA:#BBBBBB>text</gradient> ----------

    static String color(String msg) {
        if (msg == null) {
            return "";
        }
        Matcher g = GRADIENT.matcher(msg);
        StringBuffer gb = new StringBuffer();
        while (g.find()) {
            g.appendReplacement(gb, Matcher.quoteReplacement(gradient(g.group(3), g.group(1), g.group(2))));
        }
        g.appendTail(gb);
        Matcher m = HEX.matcher(gb.toString());
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char ch : m.group(1).toCharArray()) {
                rep.append('§').append(ch);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private static String gradient(String text, String from, String to) {
        String formats = "";
        while (text.length() >= 2 && text.charAt(0) == '&' && "klmnoKLMNO".indexOf(text.charAt(1)) >= 0) {
            formats += text.substring(0, 2);
            text = text.substring(2);
        }
        int len = text.length();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                out.append(ch);
                continue;
            }
            double t = len == 1 ? 0 : i / (double) (len - 1);
            int r = (int) Math.round(Integer.parseInt(from.substring(0, 2), 16) * (1 - t) + Integer.parseInt(to.substring(0, 2), 16) * t);
            int gr = (int) Math.round(Integer.parseInt(from.substring(2, 4), 16) * (1 - t) + Integer.parseInt(to.substring(2, 4), 16) * t);
            int b = (int) Math.round(Integer.parseInt(from.substring(4, 6), 16) * (1 - t) + Integer.parseInt(to.substring(4, 6), 16) * t);
            out.append(String.format("&#%02X%02X%02X", r, gr, b)).append(formats).append(ch);
        }
        return out.toString();
    }
}
