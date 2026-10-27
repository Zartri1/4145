package org;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * /livelink <link>  -  announce that you're live (TikTok, Twitch, YouTube, Kick) with a
 * clickable message for the whole server.
 *
 * Permission: unstableffa.livelink   (bypass the cooldown: unstableffa.livelink.bypass)
 * Settings:   config.yml -> livelink
 */
public final class LiveLink {

    private final zenithEssentials plugin;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public LiveLink(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // {name, hex color}
    private String[] platform(String host) {
        if (host.endsWith("tiktok.com")) {
            return new String[]{"TikTok", "#FE2C55"};
        }
        if (host.endsWith("twitch.tv")) {
            return new String[]{"Twitch", "#9146FF"};
        }
        if (host.endsWith("youtube.com") || host.endsWith("youtu.be")) {
            return new String[]{"YouTube", "#FF0033"};
        }
        if (host.endsWith("kick.com")) {
            return new String[]{"Kick", "#53FC18"};
        }
        return new String[]{"Stream", "#FFD21F"};
    }

    public void handle(CommandSender s, String[] args) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("livelink.enabled", true)) {
            s.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Live links are turned off."));
            return;
        }
        if (!(s instanceof Player)) {
            this.plugin.error(s, "Only players can use /livelink.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.livelink")) {
            p.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", new HashMap<>()));
            return;
        }
        if (args.length < 1) {
            p.sendMessage(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/livelink <link to your stream>"));
            return;
        }

        String url = args[0].trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            url = "https://" + url;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception ex) {
            host = null;
        }
        if (host == null) {
            p.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7That isn't a valid link."));
            return;
        }
        host = host.toLowerCase(Locale.ROOT);
        List<String> allowed = c.getStringList("livelink.allowed-sites");
        boolean ok = allowed.isEmpty();
        for (String a : allowed) {
            String d = a.toLowerCase(Locale.ROOT).trim();
            if (host.equals(d) || host.endsWith("." + d)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            p.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7Only these sites are allowed: &f" + String.join(", ", allowed)));
            return;
        }

        long cd = c.getLong("livelink.cooldown-minutes", 15L) * 60000L;
        Long last = this.lastUse.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < cd && !p.hasPermission("unstableffa.livelink.bypass")) {
            p.sendMessage(this.plugin.color("&8» &#D9381E&lCooldown! &7You can share your live again in &f"
                    + this.plugin.duration(cd - (now - last) + 59999L) + "&7."));
            return;
        }
        this.lastUse.put(p.getUniqueId(), now);

        String[] pf = this.platform(host);
        String name = p.getName();
        String line = this.plugin.color("&8&m                                                ");
        String head = this.plugin.color(c.getString("livelink.title",
                "  &#D9381E&l● LIVE &8» &f&l{player} &7is live on &{color}&l{platform}&7!")
                .replace("{player}", name).replace("{platform}", pf[0]).replace("{color}", pf[1]));
        String buttonText = this.plugin.color(c.getString("livelink.button",
                "  &{color}&l[ ▶ CLICK HERE TO WATCH ]").replace("{color}", pf[1]).replace("{platform}", pf[0]));

        TextComponent button = new TextComponent(TextComponent.fromLegacyText(buttonText));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(this.plugin.color("&7Open &f" + pf[0] + " &7and watch &f" + name)))));

        boolean sound = c.getBoolean("livelink.sound", true);
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.sendMessage(line);
            o.sendMessage(head);
            o.spigot().sendMessage(new BaseComponent[]{button});
            o.sendMessage(line);
            if (sound) {
                o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6F, 1.6F);
            }
        }
        Bukkit.getConsoleSender().sendMessage(name + " is live on " + pf[0] + ": " + url);

        if (c.getBoolean("livelink.discord", false)) {
            int color;
            try {
                color = Integer.parseInt(pf[1].substring(1), 16);
            } catch (Exception ex) {
                color = 0xFFD21F;
            }
            this.plugin.discordEmbed("livelink", name + " is live on " + pf[0] + "!", color,
                    new String[][]{{"Watch", url}});
        }
    }
}
