package org;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Extra chat options (chat.yml -> chat-extras). Every one is off or harmless by default.
 *
 *   caps       too many CAPITALS: lower them or block the message
 *   links      block links (except allowed sites)
 *   mentions   typing @Name pings that player (title + sound), with cooldowns; typing @ suggests players
 *   format     your own chat format, empty = leave the chat exactly as it is
 *
 * Bypass: unstableffa.chat.bypass (caps and links)
 * Works on its own: it only needs the plugin's config and Announcer.color().
 */
final class ChatExtras implements Listener {

    private static final Pattern LINK = Pattern.compile("(?i)\\b((?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?)");

    private final zenithEssentials plugin;

    private final Map<UUID, Long> senderLast = new ConcurrentHashMap<>();
    private final Map<UUID, Long> targetLast = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> suggested = new HashMap<>();
    private Method addCompl;
    private Method removeCompl;

    ChatExtras(zenithEssentials plugin) {
        this.plugin = plugin;
        for (Method m : Player.class.getMethods()) {
            if (m.getParameterCount() == 1 && Collection.class.isAssignableFrom(m.getParameterTypes()[0])) {
                String n = m.getName();
                if (n.equals("addAdditionalChatCompletions") || n.equals("addCustomChatCompletions")) {
                    this.addCompl = m;
                } else if (n.equals("removeAdditionalChatCompletions") || n.equals("removeCustomChatCompletions")) {
                    this.removeCompl = m;
                }
            }
        }
        // keeps the "@" suggestions in sync (vanish, nicks, players coming and going)
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSuggestions, 40L, 100L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this.plugin, this::refreshSuggestions, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        this.suggested.remove(id);
        this.senderLast.remove(id);
        this.targetLast.remove(id);
        Bukkit.getScheduler().runTaskLater(this.plugin, this::refreshSuggestions, 5L);
    }

    /** Typing "@" in chat suggests the online players (vanished staff are hidden from players who can't see them). */
    private void refreshSuggestions() {
        if (this.addCompl == null) {
            return;
        }
        boolean on = this.c().getBoolean("chat-extras.mentions.enabled", false)
                && this.c().getBoolean("chat-extras.mentions.suggest", true);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<String> want = new HashSet<>();
            if (on) {
                boolean seeAll = viewer.hasPermission("unstableffa.vanish");
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.equals(viewer) || (!seeAll && this.plugin.isVanishedUuid(o.getUniqueId()))) {
                        continue;
                    }
                    want.add("@" + o.getName());
                }
            }
            Set<String> have = this.suggested.getOrDefault(viewer.getUniqueId(), new HashSet<>());
            List<String> add = new ArrayList<>();
            for (String w : want) {
                if (!have.contains(w)) {
                    add.add(w);
                }
            }
            List<String> rem = new ArrayList<>();
            for (String h : have) {
                if (!want.contains(h)) {
                    rem.add(h);
                }
            }
            try {
                if (!add.isEmpty()) {
                    this.addCompl.invoke(viewer, add);
                }
                if (!rem.isEmpty() && this.removeCompl != null) {
                    this.removeCompl.invoke(viewer, rem);
                }
            } catch (Exception | LinkageError ignored) {
                this.addCompl = null; // not supported on this server version
                return;
            }
            this.suggested.put(viewer.getUniqueId(), want);
        }
    }

    private FileConfiguration c() {
        return this.plugin.getConfig();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();

        if (!p.hasPermission("unstableffa.chat.bypass")) {
            // ---- links
            if (this.c().getBoolean("chat-extras.links.enabled", false) && this.hasBadLink(msg)) {
                e.setCancelled(true);
                p.sendMessage(Announcer.color(this.c().getString("chat-extras.links.message", "&#D9381EYou can't send links in chat.")));
                return;
            }
            // ---- caps
            if (this.c().getBoolean("chat-extras.caps.enabled", false)) {
                int letters = 0;
                int upper = 0;
                for (char ch : msg.toCharArray()) {
                    if (Character.isLetter(ch)) {
                        letters++;
                        if (Character.isUpperCase(ch)) {
                            upper++;
                        }
                    }
                }
                int min = this.c().getInt("chat-extras.caps.min-letters", 8);
                int max = this.c().getInt("chat-extras.caps.max-percent", 60);
                if (letters >= min && upper * 100 / letters > max) {
                    if (this.c().getString("chat-extras.caps.action", "lower").equalsIgnoreCase("block")) {
                        e.setCancelled(true);
                        p.sendMessage(Announcer.color(this.c().getString("chat-extras.caps.message", "&#D9381EPlease don't shout.")));
                        return;
                    }
                    msg = msg.toLowerCase(Locale.ROOT);
                    e.setMessage(msg);
                }
            }
        }

        // ---- format
        String format = this.c().getString("chat-extras.format", "");
        if (format != null && !format.isEmpty()) {
            String f = Announcer.color(format.replace("{world}", p.getWorld().getName())).replace("%", "%%");
            f = f.replace("{player}", "%1$s").replace("{displayname}", "%1$s").replace("{message}", "%2$s");
            e.setFormat(f);
        }

        // ---- mentions
        if (this.c().getBoolean("chat-extras.mentions.enabled", false)) {
            msg = this.handleMentions(e, p, msg);
        }
    }

    private boolean hasBadLink(String msg) {
        Matcher m = LINK.matcher(msg);
        while (m.find()) {
            String found = m.group(1).toLowerCase(Locale.ROOT);
            boolean allowed = false;
            for (String a : this.c().getStringList("chat-extras.links.allowed")) {
                if (!a.isEmpty() && found.contains(a.toLowerCase(Locale.ROOT))) {
                    allowed = true;
                }
            }
            if (!allowed) {
                return true;
            }
        }
        return false;
    }

    /** Finds @Name in the message, highlights it and (on the main thread) pings the player. */
    private String handleMentions(AsyncPlayerChatEvent e, Player p, String msg) {
        boolean needAt = this.c().getBoolean("chat-extras.mentions.require-at", true);
        String low = msg.toLowerCase(Locale.ROOT);
        List<Player> hit = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || this.plugin.isVanishedUuid(o.getUniqueId())) {
                continue;
            }
            String pat = (needAt ? "(?<![a-z0-9_])@" : "(?<![a-z0-9_])@?") + Pattern.quote(o.getName().toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])";
            if (Pattern.compile(pat).matcher(low).find()) {
                hit.add(o);
            }
        }
        if (hit.isEmpty()) {
            return msg;
        }
        int max = Math.max(1, this.c().getInt("chat-extras.mentions.max-per-message", 3));
        if (hit.size() > max) {
            hit = new ArrayList<>(hit.subList(0, max));
        }

        // highlight @Name in the chat line
        String hl = this.c().getString("chat-extras.mentions.highlight", "&#FF8330@{name}&r");
        if (hl != null && !hl.isEmpty()) {
            for (Player o : hit) {
                Matcher m = Pattern.compile("(?i)(?<![a-z0-9_])@?" + Pattern.quote(o.getName()) + "(?![a-z0-9_])").matcher(msg);
                msg = m.replaceAll(Matcher.quoteReplacement(Announcer.color(hl.replace("{name}", o.getName()))));
            }
            e.setMessage(msg);
        }

        // sender cooldown (the message is still sent, it just doesn't ping)
        long now = System.currentTimeMillis();
        long sCd = this.c().getInt("chat-extras.mentions.sender-cooldown", 10) * 1000L;
        boolean bypass = p.hasPermission("unstableffa.mention.bypass");
        Long last = this.senderLast.get(p.getUniqueId());
        if (!bypass && last != null && now - last < sCd) {
            long left = (sCd - (now - last) + 999L) / 1000L;
            String note = this.c().getString("chat-extras.mentions.sender-cooldown-message", "&7You can ping again in &f{time}s&7.");
            if (note != null && !note.isEmpty()) {
                final String out = Announcer.color(note.replace("{time}", String.valueOf(left)));
                Bukkit.getScheduler().runTask(this.plugin, () -> p.sendMessage(out));
            }
            return msg;
        }
        this.senderLast.put(p.getUniqueId(), now);

        final List<Player> targets = hit;
        final String sender = p.getName();
        Bukkit.getScheduler().runTask(this.plugin, () -> this.ping(sender, targets));
        return msg;
    }

    private void ping(String sender, List<Player> targets) {
        long now = System.currentTimeMillis();
        long tCd = this.c().getInt("chat-extras.mentions.target-cooldown", 30) * 1000L;
        String sound = this.c().getString("chat-extras.mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        String title = this.c().getString("chat-extras.mentions.title", "&#FF8330&l@{player}");
        String sub = this.c().getString("chat-extras.mentions.subtitle", "&7mentioned you in chat");
        String bar = this.c().getString("chat-extras.mentions.actionbar", "");
        int fi = this.c().getInt("chat-extras.mentions.fade-in", 5);
        int stay = this.c().getInt("chat-extras.mentions.stay", 40);
        int fo = this.c().getInt("chat-extras.mentions.fade-out", 10);
        for (Player o : targets) {
            if (!o.isOnline()) {
                continue;
            }
            Long last = this.targetLast.get(o.getUniqueId());
            if (last != null && now - last < tCd) {
                continue; // that player was pinged recently
            }
            this.targetLast.put(o.getUniqueId(), now);
            if (title != null && (!title.isEmpty() || (sub != null && !sub.isEmpty()))) {
                o.sendTitle(Announcer.color(title.replace("{player}", sender)),
                        sub == null ? "" : Announcer.color(sub.replace("{player}", sender)), fi, stay, fo);
            }
            if (sound != null && !sound.isEmpty()) {
                try {
                    o.playSound(o.getLocation(), Sound.valueOf(sound.toUpperCase(Locale.ROOT)), 1.0F, 1.5F);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (bar != null && !bar.isEmpty()) {
                o.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                Announcer.color(bar.replace("{player}", sender)))));
            }
        }
    }
}
