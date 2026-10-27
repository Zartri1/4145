package org;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /discord  -  shows the lines from features.yml -> discord-command -> lines.
 * Any line that contains a link (or {link}) can be clicked to open it.
 * Add as many lines as you like. Colors: &a, &#RRGGBB and <gradient:#AAAAAA:#BBBBBB>text</gradient>.
 *
 * Permission: unstableffa.discord (everyone by default)
 * Works on its own: it only needs the plugin's config and Announcer.color().
 */
final class DiscordCommand {

    private static final Pattern URL = Pattern.compile("(?i)(https?://\\S+)");

    private final zenithEssentials plugin;

    DiscordCommand(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.discord")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("discord-command.enabled", true)) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7That command is turned off."));
            return;
        }
        String link = this.plugin.getConfig().getString("discord-command.link", "https://discord.gg/your-invite");
        List<String> lines = this.plugin.getConfig().getStringList("discord-command.lines");
        if (lines.isEmpty()) {
            lines = new ArrayList<>(Arrays.asList("&8» <gradient:#FF7518:#FFFFFF>&lDisc</gradient><gradient:#FFFFFF:#FF7518>&lord</gradient> &8• &7Join our community: &#B15CFF{link}"));
        }
        String hint = Announcer.color(this.plugin.getConfig().getString("discord-command.hover", "&7Click to open"));
        for (String raw : lines) {
            String line = raw.replace("{link}", link).replace("{player}", s.getName());
            String colored = Announcer.color(line);
            Matcher m = URL.matcher(org.bukkit.ChatColor.stripColor(colored));
            if (s instanceof Player && m.find()) {
                String url = m.group(1).replaceAll("[.,;!)]+$", "");
                TextComponent comp = new TextComponent(TextComponent.fromLegacyText(colored));
                comp.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                comp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(hint)));
                ((Player) s).spigot().sendMessage(comp);
            } else {
                s.sendMessage(colored);
            }
        }
    }
}
