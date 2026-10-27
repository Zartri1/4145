package org;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * Lets you decide which commands are UnstableFFA' and which belong to other plugins.
 *
 *   commands.disabled   UnstableFFA' command is turned OFF completely: it can't be used and it
 *                       doesn't show up when a player types "/". Use it when another plugin
 *                       should handle that command (for example tpa, home, warp).
 *   commands.override   the command is ALWAYS UnstableFFA', even if another plugin has a command
 *                       with the same name. The other plugin's one still works as /otherplugin:name.
 *
 * Changes apply on /ze reload and on restart. A command taken off the disabled list comes back,
 * unless another plugin took the name in the meantime.
 * Works on its own: it only needs the plugin's config.yml.
 */
final class CommandManager implements Listener {

    private final zenithEssentials plugin;
    private final Set<String> warned = new HashSet<>();
    /** Every UnstableFFA command by name and alias, saved before anything is removed from the server's list. */
    private final Map<String, PluginCommand> own = new HashMap<>();

    CommandManager(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    /** True when this UnstableFFA command is switched off in commands.disabled. */
    boolean isDisabled(String name) {
        // combat.enabled: false removes /combat completely
        if (name.equalsIgnoreCase("combat") && !this.plugin.getConfig().getBoolean("combat.enabled", true)) {
            return true;
        }
        for (String entry : this.plugin.getConfig().getStringList("commands.disabled")) {
            PluginCommand pc = this.own.get(clean(entry));
            if (pc != null && pc.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** First pass, right after our commands are registered (the server sends commands to players later). */
    void apply() {
        this.own.clear();
        for (String name : this.plugin.getDescription().getCommands().keySet()) {
            PluginCommand pc = this.plugin.getCommand(name);
            if (pc != null) {
                for (String label : this.labels(pc)) {
                    this.own.put(label, pc);
                }
            }
        }
        this.change();
    }

    /** /ze reload: apply the (new) lists right away and send the new command list to everyone. */
    void reload() {
        this.warned.clear();
        this.change();
        this.sync();
    }

    /** Second pass, once every plugin has loaded: this catches commands other plugins registered after us. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        if (this.change()) {
            this.sync();
        }
    }

    private boolean change() {
        Map<String, Command> known = this.knownCommands();
        if (known == null) {
            this.warnOnce("Could not reach the command list, so commands.disabled / commands.override do nothing.");
            return false;
        }
        boolean changed = false;
        String prefix = this.plugin.getDescription().getName().toLowerCase(Locale.ROOT);

        // ---- disabled: remove ours (name, aliases and the "unstableffa:name" form) ----
        List<String> off = new ArrayList<>(this.plugin.getConfig().getStringList("commands.disabled"));
        if (!this.plugin.getConfig().getBoolean("combat.enabled", true)) {
            off.add("combat");
        }
        for (String entry : off) {
            PluginCommand pc = this.own.get(clean(entry));
            if (pc == null) {
                this.warnOnce("commands.disabled: UnstableFFA has no command called '" + entry + "'.");
                continue;
            }
            for (String label : this.labels(pc)) {
                changed |= this.removeIfOurs(known, label, pc);
                changed |= this.removeIfOurs(known, prefix + ":" + label, pc);
            }
        }

        // ---- not disabled any more: put ours back where nobody else has the name ----
        for (PluginCommand pc : new HashSet<>(this.own.values())) {
            if (this.isDisabled(pc.getName())) {
                continue;
            }
            for (String label : this.labels(pc)) {
                if (!known.containsKey(prefix + ":" + label)) {
                    known.put(prefix + ":" + label, pc);
                    changed = true;
                }
                if (!known.containsKey(label)) {
                    known.put(label, pc);
                    changed = true;
                }
            }
        }

        // ---- override: ours takes the plain name, whoever had it before ----
        for (String entry : this.plugin.getConfig().getStringList("commands.override")) {
            PluginCommand pc = this.own.get(clean(entry));
            if (pc == null) {
                this.warnOnce("commands.override: UnstableFFA has no command called '" + entry + "'.");
                continue;
            }
            if (this.isDisabled(pc.getName())) {
                continue; // listed in both: disabled wins
            }
            for (String label : this.labels(pc)) {
                Command current = known.get(label);
                if (current != pc) {
                    String was = current instanceof PluginCommand ? ((PluginCommand) current).getPlugin().getDescription().getName()
                            : (current == null ? "nobody" : "the server");
                    known.put(label, pc);
                    changed = true;
                    this.warnOnce("/" + label + " now belongs to UnstableFFA (it was " + was + "'s).");
                }
            }
        }
        return changed;
    }

    private boolean removeIfOurs(Map<String, Command> known, String key, PluginCommand pc) {
        if (known.get(key) == pc) {
            known.remove(key);
            return true;
        }
        return false;
    }

    private List<String> labels(PluginCommand pc) {
        List<String> l = new ArrayList<>();
        l.add(pc.getName().toLowerCase(Locale.ROOT));
        for (String a : pc.getAliases()) {
            l.add(a.toLowerCase(Locale.ROOT));
        }
        return l;
    }

    private static String clean(String s) {
        String x = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return x.startsWith("/") ? x.substring(1) : x;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            Object map = m.invoke(Bukkit.getServer());
            return ((CommandMap) map).getKnownCommands();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Sends the new command list to the server's command tree and to every online player. */
    private void sync() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (Exception ignored) {
            // Older servers do it by themselves when a player updates their commands.
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    private void warnOnce(String msg) {
        if (this.warned.add(msg)) {
            this.plugin.getLogger().info(msg);
        }
    }
}
