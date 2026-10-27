package org;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Checks every player who joins: do they share an IP with a banned account?
 *
 * action: alert -> tell staff (+ Discord)
 *         kick  -> also kick them
 *         ban   -> also ban them permanently for ban evasion
 *
 * Family/school/shared-IP players can be allowed with /banevasion allow <player>.
 * Settings: config.yml -> ban-evasion      Allowed list: data.yml -> ban-evasion-allowed
 */
public final class BanEvasion {

    private final zenithEssentials plugin;

    public BanEvasion(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private List<String> allowed() {
        return this.plugin.data().getStringList("ban-evasion-allowed");
    }

    /** Called on join (after the player's IP was saved). */
    public void check(Player p) {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("ban-evasion.enabled", true)
                || p.hasPermission("unstableffa.banevasion.exempt")
                || this.allowed().contains(p.getUniqueId().toString())
                || Bukkit.getBanList(BanList.Type.NAME).isBanned(p.getName())) {
            return;
        }
        List<String> banned = new ArrayList<>();
        for (Map.Entry<UUID, String> en : this.plugin.altsOf(p.getUniqueId()).entrySet()) {
            if (Bukkit.getBanList(BanList.Type.NAME).isBanned(en.getValue())) {
                banned.add(en.getValue());
            }
        }
        if (banned.isEmpty()) {
            return;
        }
        String list = String.join(", ", banned);
        String action = c.getString("ban-evasion.action", "alert").toLowerCase();

        // staff alert - names of the banned accounts only for staff who may see alts
        String full = this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lEVAS</gradient><gradient:#FFFFFF:#FF7518>&lION</gradient>&8] &f" + p.getName() + " &7shares an IP with banned: &#D9381E" + list
                + " &8(" + (action.equals("ban") ? "auto-banned" : action.equals("kick") ? "kicked" : "/alts " + p.getName()) + ")");
        String basic = this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lEVAS</gradient><gradient:#FFFFFF:#FF7518>&lION</gradient>&8] &f" + p.getName() + " &7may be evading a ban &8(ask an admin)");
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission("unstableffa.alerts") && this.plugin.staffAlerts(st)) {
                st.sendMessage(st.hasPermission("unstableffa.alts") ? full : basic);
            }
        }
        this.plugin.getLogger().info("[Ban evasion] " + p.getName() + " shares an IP with banned: " + list);

        // one note in their history (only the first time it's noticed)
        String flagPath = "ban-evasion-flagged." + p.getUniqueId();
        if (!this.plugin.data().contains(flagPath)) {
            this.plugin.data().set(flagPath, System.currentTimeMillis());
            this.plugin.addNote(p, "Possible ban evasion - shares an IP with banned: " + list, "AutoMod");
        }

        if (c.getBoolean("ban-evasion.discord", true)) {
            this.plugin.discordEmbed("banevasion", "Possible ban evasion", 0xE67E22, new String[][]{
                    {"Player", p.getName()}, {"Banned accounts on same IP", list}, {"Action", action}});
        }

        final String name = p.getName();
        final String first = banned.get(0);
        if (action.equals("kick")) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                Player still = Bukkit.getPlayerExact(name);
                if (still != null) {
                    still.kickPlayer(this.plugin.color(c.getString("ban-evasion.kick-message",
                            "&#D9381E&lBAN EVASION\n&7This connection is linked to a banned account.\n&7Appeal on our Discord if this is a mistake.")));
                }
            });
        } else if (action.equals("ban")) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.runPunishment("ban",
                    new String[]{name, "perm", "Ban", "evasion", "(alt", "of", first + ")"}, "AutoMod"));
        }
    }

    // /banevasion <allow|unallow|list> [player]
    public void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.banevasion")) {
            s.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", Collections.emptyMap()));
            return;
        }
        List<String> allowed = new ArrayList<>(this.allowed());
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (allowed.isEmpty()) {
                s.sendMessage(this.plugin.color("&8» &7Nobody is on the ban evasion allow list."));
                return;
            }
            List<String> names = new ArrayList<>();
            for (String u : allowed) {
                try {
                    names.add(this.plugin.nameFor(UUID.fromString(u), u.substring(0, 8)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            s.sendMessage(this.plugin.color("&8» <gradient:#FF7518:#FFFFFF>&lAllo</gradient><gradient:#FFFFFF:#FF7518>&lwed</gradient> &8• &f" + String.join("&7, &f", names)));
            return;
        }
        if (args.length < 2 || !(args[0].equalsIgnoreCase("allow") || args[0].equalsIgnoreCase("unallow"))) {
            s.sendMessage(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/banevasion <allow|unallow|list> [player]"));
            return;
        }
        OfflinePlayer t = this.plugin.offline(args[1]);
        String id = t.getUniqueId().toString();
        String name = this.plugin.nameFor(t.getUniqueId(), args[1]);
        if (args[0].equalsIgnoreCase("allow")) {
            if (!allowed.contains(id)) {
                allowed.add(id);
            }
            s.sendMessage(this.plugin.color("&8» &#FF9147" + name + " &7won't be flagged for ban evasion anymore."));
        } else {
            allowed.remove(id);
            s.sendMessage(this.plugin.color("&8» &f" + name + " &7will be checked for ban evasion again."));
        }
        this.plugin.data().set("ban-evasion-allowed", allowed);
        this.plugin.saveData();
    }
}
