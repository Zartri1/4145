package org;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Saves every chat message and command a player sends.
 *   plugins/UnstableFFA/chatlogs/players/<uuid>.log   (last N lines per player, for /chatlog)
 *   plugins/UnstableFFA/chatlogs/daily/<date>.log     (everything, deleted after X days)
 *
 * /chatlog <player> [page]       permission: unstableffa.chatlogs
 * Files are written on a background thread, so logging never lags the server.
 * Settings: config.yml -> chat-logs
 */
public final class ChatLogger implements Listener {

    private static final int PER_PAGE = 10;

    private final zenithEssentials plugin;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UnstableFFA-ChatLogs");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean enabled = true;
    private volatile boolean logCommands = true;
    private volatile int keepPerPlayer = 500;
    private volatile Set<String> ignored = Collections.emptySet();

    public ChatLogger(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private File dir(String sub) {
        return new File(new File(this.plugin.getDataFolder(), "chatlogs"), sub);
    }

    public void reload() {
        FileConfiguration c = this.plugin.getConfig();
        this.enabled = c.getBoolean("chat-logs.enabled", true);
        this.logCommands = c.getBoolean("chat-logs.log-commands", true);
        this.keepPerPlayer = Math.max(50, c.getInt("chat-logs.lines-per-player", 500));
        Set<String> ig = new HashSet<>();
        for (String s : c.getStringList("chat-logs.ignored-commands")) {
            ig.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        }
        if (ig.isEmpty()) {
            ig.add("login");
            ig.add("l");
            ig.add("register");
            ig.add("reg");
            ig.add("changepassword");
        }
        this.ignored = ig;
        final int days = c.getInt("chat-logs.keep-days", 30);
        this.io.execute(() -> this.deleteOldDaily(days));
    }

    public void shutdown() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignoredEx) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- logging ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!this.enabled) {
            return;
        }
        this.log(e.getPlayer(), e.isCancelled() ? "BLOCKED" : "CHAT", e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!this.enabled || !this.logCommands) {
            return;
        }
        String msg = e.getMessage();
        String base = msg.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon != -1) {
            base = base.substring(colon + 1);
        }
        if (this.ignored.contains(base)) {
            return;
        }
        this.log(e.getPlayer(), "CMD", msg);
    }

    private void log(Player p, String type, String text) {
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        final long now = System.currentTimeMillis();
        final String clean = text.replace('\n', ' ').replace('\r', ' ');
        this.io.execute(() -> {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now));
            String line = time + "|" + type + "|" + clean + "\n";
            String day = time.substring(0, 10);
            this.append(new File(this.dir("players"), id + ".log"), line);
            this.append(new File(this.dir("daily"), day + ".log"), time.substring(11) + " [" + type + "] " + name + ": " + clean + "\n");
        });
    }

    private void append(File f, String line) {
        try {
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not write chat log: " + ex.getMessage());
        }
    }

    private void deleteOldDaily(int days) {
        if (days <= 0) {
            return;
        }
        File[] list = this.dir("daily").listFiles();
        if (list == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 86400000L;
        for (File f : list) {
            if (f.lastModified() < cutoff && !f.delete()) {
                this.plugin.getLogger().warning("Could not delete old chat log " + f.getName());
            }
        }
    }

    // ---------- /chatlog ----------

    public void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.chatlogs")) {
            s.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", Collections.emptyMap()));
            return;
        }
        if (!this.enabled) {
            s.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Chat logs are turned off."));
            return;
        }
        if (args.length < 1) {
            s.sendMessage(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/chatlog <player> [page]"));
            return;
        }
        final OfflinePlayer t = this.plugin.offline(args[0]);
        final String name = this.plugin.nameFor(t.getUniqueId(), args[0]);
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignoredEx) {
            }
        }
        final int fPage = page;
        final File f = new File(this.dir("players"), t.getUniqueId() + ".log");
        final int keep = this.keepPerPlayer;
        this.io.execute(() -> {
            List<String> lines;
            try {
                lines = f.exists() ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8) : new ArrayList<>();
                if (lines.size() > keep * 2) { // trim the file now and then
                    lines = new ArrayList<>(lines.subList(lines.size() - keep, lines.size()));
                    Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                lines = new ArrayList<>();
            }
            final List<String> fl = lines;
            Bukkit.getScheduler().runTask(this.plugin, () -> this.show(s, name, fl, fPage));
        });
    }

    private void show(CommandSender s, String name, List<String> lines, int page) {
        if (lines.isEmpty()) {
            s.sendMessage(this.plugin.color("&8» &7No chat logs for &f" + name + "&7."));
            return;
        }
        List<String> newest = new ArrayList<>(lines);
        Collections.reverse(newest);
        int pages = Math.max(1, (newest.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages);
        s.sendMessage(this.plugin.color("&8&m          &r <gradient:#FF7518:#FFFFFF>&lChat</gradient><gradient:#FFFFFF:#FF7518>&l log</gradient> &8» &f" + name + " &7(page " + page + "/" + pages + ") &8&m          "));
        int start = (page - 1) * PER_PAGE;
        List<String> out = new ArrayList<>();
        for (int i = start; i < Math.min(start + PER_PAGE, newest.size()); i++) {
            String[] p = newest.get(i).split("\\|", 3);
            if (p.length < 3) {
                continue;
            }
            String when = p[0].length() >= 16 ? p[0].substring(5, 16) : p[0];
            String color = p[1].equals("CMD") ? "&#FF8330" : (p[1].equals("BLOCKED") ? "&#D9381E" : "&f");
            String tag = p[1].equals("BLOCKED") ? " &8(blocked)" : "";
            out.add(this.plugin.color("&8[" + when + "] " + color) + p[2] + this.plugin.color(tag));
        }
        Collections.reverse(out); // oldest at the top of the page, like real chat
        for (String l : out) {
            s.sendMessage(l);
        }
        if (s instanceof Player) {
            TextComponent nav = new TextComponent("");
            if (page < pages) {
                TextComponent older = new TextComponent(TextComponent.fromLegacyText(this.plugin.color("&#FF8330« Older")));
                older.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatlog " + name + " " + (page + 1)));
                nav.addExtra(older);
                nav.addExtra("   ");
            }
            if (page > 1) {
                TextComponent newer = new TextComponent(TextComponent.fromLegacyText(this.plugin.color("&#FF8330Newer »")));
                newer.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatlog " + name + " " + (page - 1)));
                nav.addExtra(newer);
            }
            ((Player) s).spigot().sendMessage(nav);
        } else if (page < pages) {
            s.sendMessage("Older: /chatlog " + name + " " + (page + 1));
        }
    }
}
