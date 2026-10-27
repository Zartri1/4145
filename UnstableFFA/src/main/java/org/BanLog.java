package org;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Ban embeds for appeals, sent through the normal punishment webhook (config.yml -> discord-webhook).
 *
 *  - Every ban, tempban and IP ban posts an embed in your #appeals-channel with the Ban ID,
 *    the player, the reason, who banned them, how long and their history.
 *  - When the player is unbanned (in-game, /unban, /history menu, IP unban) the same embed is
 *    edited to show "UNBANNED by ..." so you can see which bans are still active.
 *  - Players make a ticket and tell staff their Ban ID; staff search the channel (Ctrl+F / Discord search).
 *
 *  /banlog sync      post embeds for everyone who is banned right now (for bans made before this update)
 *  /banlog <banid>   show which ban that ID belongs to
 *
 * Permission: unstableffa.banlog.   Settings: config.yml -> discord-webhook (url, events, colors, ban-embeds)
 * Saved in data.yml -> banlog.<banId>  (message id, player uuid, status)
 */
final class BanLog {

    private static final Pattern MSG_ID = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");

    private final zenithEssentials plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    BanLog(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return this.plugin.getConfig();
    }

    private String url() {
        String u = this.cfg().getString("discord-webhook.url", "");
        return u == null || !u.startsWith("http") ? null : u.trim().split("\\?")[0];
    }

    private boolean enabled() {
        return this.cfg().getBoolean("discord-webhook.enabled", false) && this.url() != null;
    }

    private boolean eventOn(String action) {
        return this.cfg().getBoolean("discord-webhook.events." + action.toLowerCase(), true);
    }

    private int color(String key, int def) {
        try {
            return Integer.parseInt(this.cfg().getString("discord-webhook.colors." + key, "").replace("#", ""), 16);
        } catch (Exception ex) {
            return def;
        }
    }

    // true when the last UNBAN edited an embed (then the plain "Unban" webhook message isn't needed)
    private boolean lastUnbanEdited = false;

    /** Does the ban log take care of this action? Then the plugin doesn't send its normal webhook message. */
    boolean handles(String action) {
        if (!this.enabled()) {
            return false;
        }
        if (action.equals("UNBAN")) {
            return this.lastUnbanEdited;
        }
        return (action.equals("BAN") || action.equals("TEMPBAN") || action.equals("IPBAN")) && this.eventOn(action);
    }

    // =====================================================================================
    // called by the plugin whenever something is written to a player's punishment history
    // =====================================================================================

    void onRecord(OfflinePlayer t, String name, String action, String duration, String staff, String reason, String banId) {
        if (!this.enabled()) {
            return;
        }
        if ((action.equals("BAN") || action.equals("TEMPBAN") || action.equals("IPBAN")) && banId != null && !banId.equals("-")
                && this.eventOn(action)) {
            final UUID id = t.getUniqueId();
            // one tick later, so the ban itself (and its end date) is already saved by Minecraft
            Bukkit.getScheduler().runTask(this.plugin, () -> this.postBan(banId, id, name, action, duration, staff, reason, System.currentTimeMillis()));
        } else if (action.equals("UNBAN")) {
            this.markUnbanned(t.getUniqueId(), staff, reason);
        }
    }

    // =====================================================================================
    // posting / editing
    // =====================================================================================

    private void postBan(String banId, UUID id, String name, String action, String duration, String staff, String reason, long when) {
        String url = this.url();
        if (url == null) {
            return;
        }
        Date expires = null;
        try {
            BanEntry entry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
            if (entry != null) {
                expires = entry.getExpiration();
            }
        } catch (Exception ignored) {
        }
        String history = this.plugin.historyLine(id);
        String json = this.embed(banId, id, name, action, duration, staff, reason, when, expires, history, null, null);

        String base = "banlog." + banId;
        this.plugin.data().set(base + ".uuid", id.toString());
        this.plugin.data().set(base + ".name", name);
        this.plugin.data().set(base + ".action", action);
        this.plugin.data().set(base + ".duration", duration);
        this.plugin.data().set(base + ".staff", staff);
        this.plugin.data().set(base + ".reason", reason);
        this.plugin.data().set(base + ".time", when);
        this.plugin.data().set(base + ".expires", expires == null ? null : expires.getTime());
        this.plugin.data().set(base + ".status", "ACTIVE");
        this.plugin.saveData();

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            String resp = this.send("POST", url + "?wait=true", json);
            if (resp == null) {
                return;
            }
            Matcher m = MSG_ID.matcher(resp);
            if (m.find()) {
                final String msgId = m.group(1);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.plugin.data().set(base + ".message", msgId);
                    this.plugin.saveData();
                });
            }
        });
    }

    // Edits every still-active ban embed of this player to "UNBANNED".
    private void markUnbanned(UUID id, String staff, String reason) {
        this.lastUnbanEdited = false;
        ConfigurationSection bl = this.plugin.data().getConfigurationSection("banlog");
        String url = this.url();
        if (bl == null || url == null) {
            return;
        }
        for (String banId : bl.getKeys(false)) {
            String b = banId + ".";
            if (!id.toString().equals(bl.getString(b + "uuid")) || !"ACTIVE".equals(bl.getString(b + "status"))) {
                continue;
            }
            bl.set(b + "status", "UNBANNED");
            bl.set(b + "unbanned-by", staff);
            String msgId = bl.getString(b + "message");
            if (msgId == null) {
                continue;
            }
            this.lastUnbanEdited = true;
            Long exp = bl.contains(b + "expires") ? bl.getLong(b + "expires") : null;
            final String json = this.embed(banId, id, bl.getString(b + "name", "?"), bl.getString(b + "action", "BAN"),
                    bl.getString(b + "duration"), bl.getString(b + "staff", "?"), bl.getString(b + "reason", ""),
                    bl.getLong(b + "time"), exp == null ? null : new Date(exp), this.plugin.historyLine(id), staff,
                    reason == null || reason.equals("N/A") ? null : reason);
            final String target = url + "/messages/" + msgId;
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> this.send("PATCH", target, json));
        }
        this.plugin.saveData();
    }

    private String embed(String banId, UUID id, String name, String action, String duration, String staff, String reason,
                         long when, Date expires, String history, String unbannedBy, String unbanReason) {
        FileConfiguration c = this.cfg();
        boolean unbanned = unbannedBy != null;
        String type = action.equals("IPBAN") ? "IP Ban" : (action.equals("TEMPBAN") ? "Temporary Ban" : "Permanent Ban");
        int color;
        if (unbanned) {
            color = this.color("unban", 0x2ECC71);
        } else if (action.equals("TEMPBAN")) {
            color = this.color("tempban", 0xE67E22);
        } else if (action.equals("IPBAN")) {
            color = this.color("ipban", 0x992D22);
        } else {
            color = this.color("ban", 0xE74C3C);
        }
        String title = (unbanned ? "✅ " : "🔨 ") + "Ban #" + banId + " — " + name + (unbanned ? " (UNBANNED)" : "");

        List<String> f = new ArrayList<>();
        f.add(field("Ban ID", "`#" + banId + "`", true));
        f.add(field("Player", name, true));
        f.add(field("Type", type, true));
        f.add(field("Banned by", staff == null ? "?" : staff, true));
        f.add(field("Duration", duration == null || duration.isEmpty() || duration.equals("-") ? (action.equals("TEMPBAN") ? "?" : "Permanent") : duration, true));
        f.add(field("Date", "<t:" + (when / 1000L) + ":f>", true));
        if (expires != null) {
            f.add(field(unbanned ? "Would have ended" : "Ends", "<t:" + (expires.getTime() / 1000L) + ":R>", true));
        }
        f.add(field("Reason", reason == null || reason.isEmpty() ? "No reason" : reason, false));
        if (c.getBoolean("discord-webhook.ban-embeds.show-history", true) && history != null && !history.isEmpty()) {
            f.add(field("History", history, false));
        }
        if (c.getBoolean("discord-webhook.ban-embeds.show-uuid", true)) {
            f.add(field("UUID", "`" + id + "`", false));
        }
        if (unbanned) {
            f.add(field("Unbanned", "by **" + unbannedBy + "** <t:" + (System.currentTimeMillis() / 1000L) + ":R>"
                    + (unbanReason == null ? "" : "\n" + unbanReason), false));
        }

        StringBuilder e = new StringBuilder("{");
        e.append("\"title\":\"").append(esc(title)).append("\",");
        e.append("\"color\":").append(color).append(",");
        if (c.getBoolean("discord-webhook.ban-embeds.show-head", true)) {
            e.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(id).append("/64\"},");
        }
        e.append("\"fields\":[").append(String.join(",", f)).append("],");
        e.append("\"timestamp\":\"").append(Instant.ofEpochMilli(when)).append("\",");
        e.append("\"footer\":{\"text\":\"").append(esc(c.getString("discord-webhook.ban-embeds.footer", "Players: make a ticket with your Ban ID to appeal"))).append("\"}");
        e.append("}");

        StringBuilder body = new StringBuilder("{");
        String user = c.getString("discord-webhook.username", "");
        String avatar = c.getString("discord-webhook.avatar-url", "");
        if (user != null && !user.isEmpty()) {
            body.append("\"username\":\"").append(esc(user)).append("\",");
        }
        if (avatar != null && avatar.startsWith("http")) {
            body.append("\"avatar_url\":\"").append(esc(avatar)).append("\",");
        }
        body.append("\"allowed_mentions\":{\"parse\":[]},");
        body.append("\"embeds\":[").append(e).append("]}");
        return body.toString();
    }

    private static String field(String name, String value, boolean inline) {
        String v = value == null || value.isEmpty() ? "-" : value;
        if (v.length() > 1024) {
            v = v.substring(0, 1020) + "...";
        }
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(v) + "\",\"inline\":" + inline + "}";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : s.replaceAll("§[0-9a-fk-orx]", "").toCharArray()) {
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    // Runs off the main thread. Returns the response body, or null on error.
    private String send(String method, String url, String json) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
                HttpResponse<String> r = this.http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429) { // too fast: wait as long as Discord asks
                    Matcher m = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)").matcher(r.body());
                    long wait = m.find() ? (long) (Double.parseDouble(m.group(1)) * 1000) + 100 : 2000;
                    Thread.sleep(Math.min(wait, 10000));
                    continue;
                }
                if (r.statusCode() >= 400) {
                    this.plugin.getLogger().warning("[BanLog] Discord answered " + r.statusCode() + ": " + r.body());
                    return null;
                }
                return r.body();
            } catch (Exception ex) {
                this.plugin.getLogger().warning("[BanLog] Could not reach Discord: " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    // =====================================================================================
    // /banlog
    // =====================================================================================

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.banlog")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (args.length == 0) {
            s.sendMessage(Announcer.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/banlog sync &7| &f/banlog <banid>"));
            s.sendMessage(Announcer.color("&8» &7Ban embeds: " + (this.enabled() ? "&#FF9147on" : "&#D9381Eoff &8(set discord-webhook.enabled + url in config.yml)")));
            return;
        }
        if (args[0].equalsIgnoreCase("sync")) {
            this.sync(s);
            return;
        }
        String banId = args[0].replace("#", "").toUpperCase();
        ConfigurationSection b = this.plugin.data().getConfigurationSection("banlog." + banId);
        if (b == null) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7No ban with ID &f#" + banId + " &7in the ban log. &8(try /history #" + banId + ")"));
            return;
        }
        s.sendMessage(Announcer.color("&8» &#FF791F&lBan #" + banId + " &8• &f" + b.getString("name") + " &7by &f" + b.getString("staff")
                + " &8(" + b.getString("status") + ")"));
        s.sendMessage(Announcer.color("&8» &7Reason: &f" + b.getString("reason")));
    }

    // Posts an embed for every player who is banned right now and doesn't have one yet.
    private void sync(CommandSender s) {
        if (!this.enabled()) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Turn on &fdiscord-webhook &7and set its &furl &7in config.yml first."));
            return;
        }
        List<Object[]> todo = new ArrayList<>(); // {banId, uuid, name, action, duration, staff, reason, time}
        for (BanEntry entry : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            String name = entry.getTarget();
            OfflinePlayer op = this.plugin.offline(name);
            List<String> log = this.plugin.data().getStringList("punishlog." + op.getUniqueId());
            for (int i = log.size() - 1; i >= 0; i--) {
                String[] p = log.get(i).split("\\|", -1); // time|action|duration|staff|reason|banId|removed
                if (p.length < 6 || !(p[1].equals("BAN") || p[1].equals("TEMPBAN") || p[1].equals("IPBAN"))) {
                    continue;
                }
                if (!p[5].equals("-") && !this.plugin.data().contains("banlog." + p[5])) {
                    long time;
                    try {
                        time = Long.parseLong(p[0]);
                    } catch (NumberFormatException ex) {
                        time = System.currentTimeMillis();
                    }
                    todo.add(new Object[]{p[5], op.getUniqueId(), name, p[1], p[2], p[3], p[4], time});
                }
                break; // only the newest ban of each player
            }
        }
        if (todo.isEmpty()) {
            s.sendMessage(Announcer.color("&8» &7Every active ban already has an embed."));
            return;
        }
        s.sendMessage(Announcer.color("&8» &#FF9147&lSuccess! &7Posting &f" + todo.size() + " &7ban(s) to Discord &8(1 per second)&7..."));
        for (int i = 0; i < todo.size(); i++) {
            final Object[] r = todo.get(i);
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.postBan((String) r[0], (UUID) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6], (Long) r[7]), 20L * i);
        }
        if (s instanceof Player) {
            final Player p = (Player) s;
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> p.sendMessage(Announcer.color("&8» &#FF9147Done posting the ban log.")), 20L * todo.size() + 40L);
        }
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (s.hasPermission("unstableffa.banlog") && args.length == 1 && "sync".startsWith(args[0].toLowerCase())) {
            out.add("sync");
        }
        return out;
    }
}
