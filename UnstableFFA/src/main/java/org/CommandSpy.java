package org;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * /commandspy  -  staff see the commands other players type (like social spy, but for commands).
 *
 * Permission:  unstableffa.commandspy          use /commandspy
 *              unstableffa.commandspy.exempt   your commands are never shown (owners / admins)
 * Settings:    config.yml -> command-spy   (ignored commands, format)
 * Who has it turned on is saved in plugins/UnstableFFA/commandspy.yml (kept over restarts).
 * Works on its own: it only needs the plugin's config.yml.
 */
final class CommandSpy implements Listener {

    private final zenithEssentials plugin;
    private final Set<UUID> spies = new HashSet<>();
    private final File file;

    CommandSpy(zenithEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "commandspy.yml");
        FileConfiguration f = YamlConfiguration.loadConfiguration(this.file);
        for (String s : f.getStringList("spies")) {
            try {
                this.spies.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        FileConfiguration f = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (UUID u : this.spies) {
            out.add(u.toString());
        }
        f.set("spies", out);
        try {
            f.save(this.file);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save commandspy.yml: " + ex.getMessage());
        }
    }

    void handle(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Only players can use /commandspy.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.commandspy")) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        boolean on = this.spies.add(p.getUniqueId());
        if (!on) {
            this.spies.remove(p.getUniqueId());
        }
        this.save();
        p.sendMessage(Announcer.color(on
                ? "&8» <gradient:#FF7518:#FFFFFF>&lComman</gradient><gradient:#FFFFFF:#FF7518>&ld Spy</gradient> &8• <gradient:#FF7518:#FFFFFF>&lENAB</gradient><gradient:#FFFFFF:#FF7518>&lLED</gradient> &7- you'll see the commands players use."
                : "&8» <gradient:#FF7518:#FFFFFF>&lComman</gradient><gradient:#FFFFFF:#FF7518>&ld Spy</gradient> &8• <gradient:#FF7518:#FFFFFF>&lDISA</gradient><gradient:#FFFFFF:#FF7518>&lBLED</gradient>"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (this.spies.isEmpty()) {
            return;
        }
        Player sender = e.getPlayer();
        if (sender.hasPermission("unstableffa.commandspy.exempt")) {
            return;
        }
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("command-spy.enabled", true)) {
            return;
        }
        String msg = e.getMessage();
        String base = msg.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon != -1) {
            base = base.substring(colon + 1);
        }
        List<String> ignored = c.getStringList("command-spy.ignored-commands");
        if (ignored.isEmpty()) {
            ignored = java.util.Arrays.asList("login", "l", "register", "reg", "changepassword", "pin");
        }
        if (ignored.contains(base)) {
            return; // never show passwords
        }
        String line = Announcer.color(c.getString("command-spy.format", "&8[<gradient:#FF7518:#FFFFFF>&lCmd</gradient><gradient:#FFFFFF:#FF7518>&lSpy</gradient>&8] &7{player}&8: &f{command}")
                .replace("{player}", sender.getName())) .replace("{command}", msg)
                + (e.isCancelled() ? Announcer.color(" &8(blocked)") : "");
        for (UUID id : this.spies) {
            Player spy = Bukkit.getPlayer(id);
            if (spy != null && !spy.equals(sender) && spy.hasPermission("unstableffa.commandspy")) {
                spy.sendMessage(line);
            }
        }
    }
}
