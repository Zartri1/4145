package org;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Everything that talks to Discord webhooks, in one place.
 *
 *   post(...)             fire-and-forget embed (punishments, reports, ban evasion, livelink, screenshare)
 *   punishment(...)       the punishment embed (warn, mute, kick, ...)
 *   embed(...)            a simple embed with fields, for the other systems
 *   updateStatus(...)     the server status embed: posts it ONCE, then edits that same message
 *
 * The status message id is kept in plugins/UnstableFFA/discord.yml (not data.yml),
 * together with the webhook it belongs to. A new message is only posted when Discord says
 * the old one is gone (404) or when you switch to another webhook. Rate limits and other
 * errors are logged and retried on the next update, they never post a duplicate.
 */
final class DiscordWebhooks {

    private static final Pattern WEBHOOK_ID = Pattern.compile("/webhooks/(\\d+)/");

    private final zenithEssentials plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final File file;
    private final FileConfiguration store;
    private final Object statusLock = new Object();
    private String lastStatusError = "";

    DiscordWebhooks(zenithEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "discord.yml");
        this.store = YamlConfiguration.loadConfiguration(this.file);
    }

    // =====================================================================
    //  Simple embeds
    // =====================================================================

    /** The punishment embed (warn, unwarn, mute, kick, ...). Bans go through BanLog. */
    void punishment(String actionKey, String title, String target, String staff, String reason, String duration, String history) {
        FileConfiguration c = this.plugin.getConfig();
        FileConfiguration m = this.plugin.msgs();
        List<String> f = new ArrayList<>();
        f.add(field(m.getString("discord-embed-field-player", "Player"), target, true));
        f.add(field(m.getString("discord-embed-field-staff", "Staff"), staff, true));
        if (duration != null && !duration.isEmpty()) {
            f.add(field(m.getString("discord-embed-field-duration", "Duration"), duration, true));
        }
        f.add(field(m.getString("discord-embed-field-reason", "Reason"), reason == null || reason.isEmpty() ? "N/A" : reason, false));
        if (history != null && !history.isEmpty()) {
            f.add(field(m.getString("discord-embed-field-history", "History"), history, false));
        }
        this.post(actionKey, title, color(c.getString("discord-webhook.colors." + actionKey, "5865F2"), 0x5865F2), f);
    }

    /** A simple embed. fields = {{name, value}, ...}; short values are shown side by side. */
    void embed(String eventKey, String title, int color, String[][] fields) {
        List<String> f = new ArrayList<>();
        for (String[] x : fields) {
            f.add(field(x[0], x[1], x[1] != null && x[1].length() < 40));
        }
        this.post(eventKey, title, color, f);
    }

    /** Posts one embed to discord-webhook.url if the webhook and that event are turned on. */
    void post(String eventKey, String title, int color, List<String> fields) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("discord-webhook.enabled", false) || !c.getBoolean("discord-webhook.events." + eventKey, true)) {
            return;
        }
        final String url = c.getString("discord-webhook.url", "");
        if (url == null || !url.startsWith("http")) {
            return;
        }
        String embed = "{\"title\":\"" + esc(title) + "\",\"color\":" + color
                + ",\"fields\":[" + String.join(",", fields) + "]"
                + ",\"timestamp\":\"" + Instant.now() + "\""
                + ",\"footer\":{\"text\":\"" + esc(this.footer()) + "\"}}";
        final String body = this.withIdentity("{\"embeds\":[" + embed + "]}");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                Resp r = this.request("POST", url, body);
                if (r.code >= 400) {
                    this.plugin.getLogger().warning("Discord webhook (" + eventKey + ") failed: HTTP " + r.code + " " + r.body);
                }
            } catch (Exception ex) {
                this.plugin.getLogger().warning("Discord webhook (" + eventKey + ") failed: " + ex.getMessage());
            }
        });
    }

    // =====================================================================
    //  Server status embed (edits ONE message instead of posting new ones)
    // =====================================================================

    /** The webhook the status embed uses: status-embed.url, or discord-webhook.url when that is empty. */
    String statusUrl() {
        FileConfiguration c = this.plugin.getConfig();
        String url = c.getString("discord-webhook.status-embed.url", "");
        if (url == null || url.trim().isEmpty()) {
            url = c.getString("discord-webhook.url", "");
        }
        return url == null || !url.trim().startsWith("http") ? null : url.trim();
    }

    /** Sends the status embed. sync = true only from onDisable (no scheduler there). */
    void updateStatus(String embedJson, boolean sync) {
        final String url = this.statusUrl();
        if (url == null) {
            return;
        }
        if (sync) {
            this.sendStatus(url, embedJson);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> this.sendStatus(url, embedJson));
        }
    }

    /** Forget the saved status message, so the next update posts a fresh one (/serverstatus reset). */
    void resetStatus() {
        synchronized (this.statusLock) {
            this.store.set("status", null);
            this.save();
        }
    }

    private void sendStatus(String url, String embedJson) {
        // Only one status update at a time, so two updates can never both post a new message.
        synchronized (this.statusLock) {
            String[] parts = splitUrl(url);
            String base = parts[0];
            String query = parts[1];
            String webhookId = webhookId(base);

            String msgId = this.store.getString("status.message-id", null);
            String savedHook = this.store.getString("status.webhook-id", null);
            if (msgId == null) {
                // Move over the id the old code kept in data.yml (only once).
                String old = this.plugin.data().getString("discord-status-message-id", null);
                if (old != null) {
                    msgId = old;
                    savedHook = webhookId;
                    this.plugin.data().set("discord-status-message-id", null);
                }
            }
            if (msgId != null && savedHook != null && webhookId != null && !savedHook.equals(webhookId)) {
                msgId = null; // the webhook was changed: its old message can't be edited
            }

            try {
                if (msgId != null) {
                    // Edits only send what Discord allows on an edit (no username / avatar).
                    String editUrl = base + "/messages/" + msgId + (query.isEmpty() ? "" : "?" + query);
                    Resp r = this.request("PATCH", editUrl, "{\"embeds\":[" + embedJson + "]}");
                    if (r.code < 300) {
                        this.lastStatusError = "";
                        return;
                    }
                    if (r.code != 404) {
                        // Rate limited, bad request, Discord down... keep the message, try again next time.
                        this.warnOnce("Could not edit the status embed: HTTP " + r.code + " " + shorten(r.body));
                        return;
                    }
                    // 404 = the message was deleted in Discord: post a new one below.
                }

                String postUrl = base + "?wait=true" + (query.isEmpty() ? "" : "&" + query);
                Resp r = this.request("POST", postUrl, this.withIdentity("{\"embeds\":[" + embedJson + "]}"));
                if (r.code >= 300) {
                    this.warnOnce("Could not post the status embed: HTTP " + r.code + " " + shorten(r.body));
                    return;
                }
                String newId = topLevelId(r.body);
                if (newId == null) {
                    this.warnOnce("Posted the status embed but Discord sent back no message id.");
                    return;
                }
                this.store.set("status.message-id", newId);
                this.store.set("status.webhook-id", webhookId);
                this.save();
                this.lastStatusError = "";
            } catch (Exception ex) {
                this.warnOnce("Status embed failed: " + ex.getMessage());
            }
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private Resp request(String method, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "UnstableFFA");
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = this.http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    /** Adds the webhook name and avatar from config (only allowed when posting, not when editing). */
    private String withIdentity(String json) {
        FileConfiguration c = this.plugin.getConfig();
        String username = c.getString("discord-webhook.username", "UnstableFFA");
        String avatar = c.getString("discord-webhook.avatar-url", "");
        StringBuilder sb = new StringBuilder("{");
        if (username != null && !username.isEmpty()) {
            sb.append("\"username\":\"").append(esc(username)).append("\",");
        }
        if (avatar != null && avatar.startsWith("http")) {
            sb.append("\"avatar_url\":\"").append(esc(avatar)).append("\",");
        }
        return sb.append(json, 1, json.length()).toString();
    }

    private String footer() {
        return this.plugin.msgs().getString("discord-embed-footer", "UnstableFFA");
    }

    private void save() {
        try {
            this.store.save(this.file);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save discord.yml: " + ex.getMessage());
        }
    }

    /** Logs a problem once instead of every 5 minutes. */
    private void warnOnce(String msg) {
        if (!msg.equals(this.lastStatusError)) {
            this.lastStatusError = msg;
            this.plugin.getLogger().warning(msg);
        }
    }

    /** "https://discord.com/api/webhooks/1/abc?thread_id=5&wait=true" -> {base, "thread_id=5"} */
    private static String[] splitUrl(String url) {
        String base = url;
        String query = "";
        int q = url.indexOf('?');
        if (q != -1) {
            base = url.substring(0, q);
            List<String> keep = new ArrayList<>();
            for (String p : url.substring(q + 1).split("&")) {
                if (!p.isEmpty() && !p.toLowerCase().startsWith("wait=")) {
                    keep.add(p);
                }
            }
            query = String.join("&", keep);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new String[]{base, query};
    }

    private static String webhookId(String base) {
        Matcher m = WEBHOOK_ID.matcher(base + "/");
        return m.find() ? m.group(1) : null;
    }

    /** The message's own "id" (top level of the JSON), not the author's or an embed's id. */
    static String topLevelId(String json) {
        if (json == null) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
            } else if (ch == '"') {
                if (depth == 1 && json.startsWith("\"id\"", i)) {
                    Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"").matcher(json);
                    if (m.find(i)) {
                        return m.group(1);
                    }
                }
                inString = true;
            }
        }
        return null;
    }

    private static String shorten(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }

    static int color(String hex, int def) {
        try {
            return Integer.parseInt(hex.replace("#", "").trim(), 16);
        } catch (Exception ex) {
            return def;
        }
    }

    static String field(String name, String value, boolean inline) {
        String v = value == null || value.trim().isEmpty() ? "-" : value;
        if (v.length() > 1024) {
            v = v.substring(0, 1020) + "...";
        }
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(v) + "\",\"inline\":" + inline + "}";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
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
}
