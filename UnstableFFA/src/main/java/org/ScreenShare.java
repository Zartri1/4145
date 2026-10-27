package org;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * /ss - screenshare a player you think is cheating.
 *
 *   /ss <player>                         start: they're frozen, taken to the SS room, and get step 1
 *   /ss next|back|repeat <player>        send the next / previous / same step
 *   /ss end <player> <clean|ban|admit>   finish: clean = let them go, ban/admit = punish (config.yml)
 *   /ss setlocation                      set the SS room to where you stand
 *   /ss list                             who is being screenshared
 *
 * While being screenshared the player can't move, use commands, break/place blocks, drop items or
 * take damage. Their chat only goes to staff, and they don't see public chat.
 * Logging out during a screenshare runs the logout punishment (default: permanent ban).
 * Settings: config.yml -> screenshare
 */
public final class ScreenShare implements Listener {

    private static final class Session {
        UUID staff;
        String staffName;
        String targetName;
        int step = 0;
        Location back;
        long start = System.currentTimeMillis();
    }

    private final zenithEssentials plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>(); // target -> session
    private final Set<UUID> teleporting = new HashSet<>();
    private BukkitTask ticker;

    public ScreenShare(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        if (this.ticker == null) {
            this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L, 20L);
        }
    }

    public boolean inSession(UUID id) {
        return this.sessions.containsKey(id);
    }

    private Session sessionOfStaff(UUID staff) {
        for (Session s : this.sessions.values()) {
            if (s.staff.equals(staff)) {
                return s;
            }
        }
        return null;
    }

    private List<String> steps() {
        List<String> s = this.plugin.getConfig().getStringList("screenshare.steps");
        if (s.isEmpty()) {
            s = new ArrayList<>();
            s.add("&#FF8330You are being screenshared by &f{staff}&#FF8330. &#D9381EDo NOT log out, close Minecraft or delete files - that is an instant ban.");
            s.add("&#FF8330Join our Discord and go to the &f#screenshare &#FF8330voice channel: &#B15CFFdiscord.gg/yourserver");
            s.add("&eDownload &fAnyDesk &eat &bhttps://anydesk.com &eand open it.");
            s.add("&#FF8330Type your &fAnyDesk address &#FF8330in chat. &7(Only staff can see your messages right now.)");
            s.add("&#FF8330Accept the connection from staff and wait. Thank you for cooperating!");
        }
        return s;
    }

    private Location room() {
        FileConfiguration d = this.plugin.data();
        if (!d.contains("ss-location.world")) {
            return null;
        }
        World w = Bukkit.getWorld(d.getString("ss-location.world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, d.getDouble("ss-location.x"), d.getDouble("ss-location.y"), d.getDouble("ss-location.z"),
                (float) d.getDouble("ss-location.yaw"), (float) d.getDouble("ss-location.pitch"));
    }

    private void tp(Player p, Location l) {
        this.teleporting.add(p.getUniqueId());
        p.teleport(l);
        this.teleporting.remove(p.getUniqueId());
    }

    // =====================================================================================
    // commands
    // =====================================================================================

    public void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.ss")) {
            s.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", Collections.emptyMap()));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("screenshare.enabled", true)) {
            s.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Screenshare is turned off."));
            return;
        }
        if (args.length == 0) {
            s.sendMessage(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/ss <player> &7| &f/ss next|back|repeat <player> &7| &f/ss end <player> <clean|ban|admit> &7| &f/ss setlocation &7| &f/ss list"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setlocation": {
                if (!(s instanceof Player) || !s.hasPermission("unstableffa.ss.setlocation")) {
                    s.sendMessage(this.plugin.fmt("no-permission", "&#D9381EYou don't have permission.", Collections.emptyMap()));
                    return;
                }
                Location l = ((Player) s).getLocation();
                FileConfiguration d = this.plugin.data();
                d.set("ss-location.world", l.getWorld().getName());
                d.set("ss-location.x", l.getX());
                d.set("ss-location.y", l.getY());
                d.set("ss-location.z", l.getZ());
                d.set("ss-location.yaw", l.getYaw());
                d.set("ss-location.pitch", l.getPitch());
                this.plugin.saveData();
                s.sendMessage(this.plugin.color("&8» <gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient> &8• &7Screenshare room set to your location."));
                return;
            }
            case "list": {
                if (this.sessions.isEmpty()) {
                    s.sendMessage(this.plugin.color("&8» &7Nobody is being screenshared."));
                    return;
                }
                for (Session se : this.sessions.values()) {
                    s.sendMessage(this.plugin.color("&8» &f" + se.targetName + " &7by &f" + se.staffName + " &8(step " + (se.step + 1)
                            + ", " + this.plugin.duration(Math.max(60000L, System.currentTimeMillis() - se.start)) + ")"));
                }
                return;
            }
            case "next":
            case "back":
            case "repeat":
            case "end": {
                if (args.length < 2) {
                    s.sendMessage(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/ss " + sub + " <player>" + (sub.equals("end") ? " <clean|ban|admit>" : "")));
                    return;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                Session se = t == null ? null : this.sessions.get(t.getUniqueId());
                if (se == null) {
                    s.sendMessage(this.plugin.color("&8» &#D9381E&lError! &f" + args[1] + " &7isn't being screenshared."));
                    return;
                }
                if (sub.equals("end")) {
                    String result = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "clean";
                    if (!result.equals("clean") && !result.equals("ban") && !result.equals("admit")) {
                        s.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7Use clean, ban or admit."));
                        return;
                    }
                    this.end(t, se, result, s.getName());
                    return;
                }
                int max = this.steps().size() - 1;
                if (sub.equals("next")) {
                    se.step = Math.min(max, se.step + 1);
                } else if (sub.equals("back")) {
                    se.step = Math.max(0, se.step - 1);
                }
                this.sendStep(t, se);
                if (s instanceof Player) {
                    this.panel((Player) s, se);
                }
                return;
            }
            default:
                this.start(s, args[0]);
        }
    }

    private void start(CommandSender s, String name) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can start a screenshare.");
            return;
        }
        Player staff = (Player) s;
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) {
            staff.sendMessage(this.plugin.fmt("player-not-found", "&#D9381EPlayer not found.", Collections.singletonMap("player", name)));
            return;
        }
        if (t.equals(staff)) {
            staff.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7You can't screenshare yourself."));
            return;
        }
        if (t.hasPermission("unstableffa.ss.exempt")) {
            staff.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7You can't screenshare that player."));
            return;
        }
        if (this.sessions.containsKey(t.getUniqueId())) {
            staff.sendMessage(this.plugin.color("&8» &#D9381E&lError! &f" + t.getName() + " &7is already being screenshared."));
            return;
        }
        if (this.sessionOfStaff(staff.getUniqueId()) != null) {
            staff.sendMessage(this.plugin.color("&8» &#D9381E&lError! &7Finish your current screenshare first (&f/ss list&7)."));
            return;
        }
        Session se = new Session();
        se.staff = staff.getUniqueId();
        se.staffName = staff.getName();
        se.targetName = t.getName();
        se.back = t.getLocation();
        this.sessions.put(t.getUniqueId(), se);
        this.saveRestore(t, se.back);

        Location room = this.room();
        if (room != null) {
            this.tp(t, room);
            if (this.plugin.getConfig().getBoolean("screenshare.teleport-staff", true)) {
                staff.teleport(room);
            }
        }
        this.plugin.setFrozen(t, true, true); // the normal /freeze; the screenshare title is the only message
        t.sendTitle(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lSCREEN</gradient><gradient:#FFFFFF:#FF7518>&lSHARE</gradient>"), this.plugin.color("&7Follow the steps in chat. &#D9381EDon't log out!"), 10, 100, 20);
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0F, 0.6F);
        this.sendStep(t, se);

        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("unstableffa.ss") && this.plugin.staffAlerts(o)) {
                o.sendMessage(this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient>&8] &f" + staff.getName() + " &7started a screenshare on &f" + t.getName()));
            }
        }
        if (room == null) {
            staff.sendMessage(this.plugin.color("&8» &7Tip: set a screenshare room with &f/ss setlocation&7. They stay where they are for now."));
        }
        this.panel(staff, se);
    }

    private void sendStep(Player t, Session se) {
        List<String> steps = this.steps();
        int n = Math.min(se.step, steps.size() - 1);
        String text = steps.get(n).replace("{staff}", se.staffName).replace("{player}", t.getName());
        t.sendMessage("");
        t.sendMessage(this.plugin.color("&8&m                                                "));
        t.sendMessage(this.plugin.color("  <gradient:#FF7518:#FFFFFF>&lSCREEN</gradient><gradient:#FFFFFF:#FF7518>&lSHARE</gradient> &8» &fStep " + (n + 1) + "/" + steps.size()));
        for (String line : text.split("\n")) {
            t.spigot().sendMessage(TextComponent.fromLegacyText(this.plugin.color("  " + line)));
        }
        t.sendMessage(this.plugin.color("&8&m                                                "));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8F, 1.4F);
    }

    // Clickable control buttons for the staff member.
    private void panel(Player staff, Session se) {
        String n = se.targetName;
        int total = this.steps().size();
        staff.sendMessage(this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient>&8] &f" + n + " &7is on step &f" + (se.step + 1) + "/" + total));
        TextComponent row1 = new TextComponent("  ");
        row1.addExtra(this.button("&#FF8330[◀ Back]", "/ss back " + n));
        row1.addExtra(" ");
        row1.addExtra(this.button("&#FF9147[Next ▶]", "/ss next " + n));
        row1.addExtra(" ");
        row1.addExtra(this.button("&7[Repeat]", "/ss repeat " + n));
        TextComponent row2 = new TextComponent("  ");
        row2.addExtra(this.button("&#FF9147&l[✔ CLEAN]", "/ss end " + n + " clean"));
        row2.addExtra(" ");
        row2.addExtra(this.button("&#FF7518&l[ADMITTED]", "/ss end " + n + " admit"));
        row2.addExtra(" ");
        row2.addExtra(this.button("&#D9381E&l[✖ CHEATING]", "/ss end " + n + " ban"));
        staff.spigot().sendMessage(row1);
        staff.spigot().sendMessage(row2);
    }

    private TextComponent button(String text, String cmd) {
        TextComponent b = new TextComponent(TextComponent.fromLegacyText(this.plugin.color(text)));
        b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        return b;
    }

    private void end(Player t, Session se, String result, String staffName) {
        this.sessions.remove(t.getUniqueId());
        this.plugin.data().set("ss-restore." + t.getUniqueId(), null);
        this.plugin.saveData();
        this.plugin.setFrozen(t, false, true);
        if (se.back != null) {
            this.tp(t, se.back);
        }
        String label;
        switch (result) {
            case "ban":
                label = "&#D9381Echeating found";
                this.punish(t.getName(), this.plugin.getConfig().getString("screenshare.ban-command", "punish {player} Unfair_Advantage"), staffName);
                break;
            case "admit":
                label = "&#FF7518admitted";
                this.punish(t.getName(), this.plugin.getConfig().getString("screenshare.admit-command", "tempban {player} 14d Admitted to cheating during screenshare"), staffName);
                break;
            default:
                label = "&#FF9147clean";
                t.sendTitle(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lYOU'RE</gradient><gradient:#FFFFFF:#FF7518>&l CLEAN</gradient>"), this.plugin.color("&7Thanks for your patience!"), 10, 60, 20);
                t.sendMessage(this.plugin.color("&8» &#FF9147&lScreenshare finished. &7You're free to go - thanks for cooperating!"));
                break;
        }
        String msg = this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient>&8] &f" + t.getName() + "&7's screenshare ended: " + label + " &7(by " + staffName + ")");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("unstableffa.ss")) {
                o.sendMessage(msg);
            }
        }
        this.plugin.discordEmbed("screenshare", "Screenshare: " + t.getName(), result.equals("clean") ? 0x2ECC71 : 0xE74C3C,
                new String[][]{{"Player", t.getName()}, {"Staff", staffName}, {"Result", result},
                        {"Took", this.plugin.duration(Math.max(60000L, System.currentTimeMillis() - se.start))}});
    }

    // "punish {player} X" / "ban {player} perm reason" go through the plugin so history shows the staff name.
    private void punish(String name, String template, String staffName) {
        String cmd = template.replace("{player}", name).trim();
        String[] parts = cmd.split("\\s+");
        String base = parts[0].toLowerCase(Locale.ROOT);
        if (base.matches("punish|ban|tempban|mute|kick|warn")) {
            String[] rest = new String[parts.length - 1];
            System.arraycopy(parts, 1, rest, 0, rest.length);
            this.plugin.runPunishment(base, rest, staffName);
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    private void saveRestore(Player t, Location l) {
        String b = "ss-restore." + t.getUniqueId();
        this.plugin.data().set(b + ".world", l.getWorld().getName());
        this.plugin.data().set(b + ".x", l.getX());
        this.plugin.data().set(b + ".y", l.getY());
        this.plugin.data().set(b + ".z", l.getZ());
        this.plugin.saveData();
    }

    // =====================================================================================
    // events
    // =====================================================================================

    private void tick() {
        if (this.sessions.isEmpty()) {
            return;
        }
        int total = this.steps().size();
        for (Map.Entry<UUID, Session> en : this.sessions.entrySet()) {
            Player t = Bukkit.getPlayer(en.getKey());
            if (t != null) {
                t.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(this.plugin.color(
                        "<gradient:#FF7518:#FFFFFF>&lSCREEN</gradient><gradient:#FFFFFF:#FF7518>&lSHARE</gradient> &8» &7Step &f" + (en.getValue().step + 1) + "/" + total + " &8| &#D9381EDo NOT log out!")));
            }
        }
    }

    /** Called from the main chat handler. Returns true if the message was handled here. */
    public boolean onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Session asTarget = this.sessions.get(p.getUniqueId());
        Session asStaff = asTarget == null ? this.sessionOfStaff(p.getUniqueId()) : null;
        if (asTarget == null && asStaff == null) {
            return false;
        }
        e.setCancelled(true);
        Session se = asTarget != null ? asTarget : asStaff;
        UUID targetId = asTarget != null ? p.getUniqueId() : this.targetOf(asStaff);
        String line = this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient>&8] " + (asTarget != null ? "&f" : "&#B15CFF") + p.getName() + " &7» &f") + e.getMessage();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Player t = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (t != null) {
                t.sendMessage(line);
            }
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.hasPermission("unstableffa.ss") && (o.getUniqueId().equals(se.staff) || this.plugin.staffAlerts(o))) {
                    o.sendMessage(line);
                }
            }
        });
        return true;
    }

    private UUID targetOf(Session s) {
        for (Map.Entry<UUID, Session> en : this.sessions.entrySet()) {
            if (en.getValue() == s) {
                return en.getKey();
            }
        }
        return null;
    }

    /** Players being screenshared don't see public chat. */
    public void filterRecipients(AsyncPlayerChatEvent e) {
        if (!this.sessions.isEmpty()) {
            e.getRecipients().removeIf(r -> this.sessions.containsKey(r.getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!this.sessions.containsKey(e.getPlayer().getUniqueId())) {
            return;
        }
        String base = e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (this.plugin.getConfig().getStringList("screenshare.allowed-commands").contains(base)) {
            return;
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage(this.plugin.color("&8» &#D9381E&lScreenshare! &7You can't use commands right now. Just type in chat to talk to staff."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!this.sessions.containsKey(e.getPlayer().getUniqueId()) || this.teleporting.contains(e.getPlayer().getUniqueId())) {
            return;
        }
        // Small corrections (the freeze putting them back, the server or an anticheat fixing their position)
        // must go through, otherwise the client and server fight and the player stutters.
        // Only real escapes are blocked: ender pearls, portals, chorus fruit, commands, other worlds.
        Location f = e.getFrom();
        Location to = e.getTo();
        boolean small = to != null && f.getWorld() != null && f.getWorld().equals(to.getWorld()) && f.distanceSquared(to) < 25.0;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (!small || (c != PlayerTeleportEvent.TeleportCause.PLUGIN && c != PlayerTeleportEvent.TeleportCause.UNKNOWN)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (this.sessions.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (this.sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (this.sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (this.sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (this.sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /** Called from the main quit handler. */
    public void onQuit(Player p) {
        Session se = this.sessions.remove(p.getUniqueId());
        if (se != null) {
            this.plugin.setFrozen(p, false, true); // speed is saved with the player, so give it back before they leave
            // Logged out during a screenshare: punish. They go back to where they were on their next join.
            final String name = p.getName();
            final String staffName = se.staffName;
            Bukkit.getScheduler().runTask(this.plugin, () -> this.punish(name,
                    this.plugin.getConfig().getString("screenshare.logout-command", "ban {player} perm Logged out during a screenshare"), staffName));
            String msg = this.plugin.color("&8[<gradient:#FF7518:#FFFFFF>&lS</gradient><gradient:#FFFFFF:#FF7518>&lS</gradient>&8] &f" + name + " &#D9381Elogged out during their screenshare and was punished.");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.hasPermission("unstableffa.ss")) {
                    o.sendMessage(msg);
                }
            }
            this.plugin.discordEmbed("screenshare", "Screenshare: " + name, 0xE74C3C,
                    new String[][]{{"Player", name}, {"Staff", staffName}, {"Result", "Logged out during screenshare"}});
            return;
        }
        Session asStaff = this.sessionOfStaff(p.getUniqueId());
        if (asStaff != null) {
            UUID tid = this.targetOf(asStaff);
            Player t = tid == null ? null : Bukkit.getPlayer(tid);
            if (t != null) {
                this.end(t, asStaff, "clean", asStaff.staffName + " (left)");
            }
        }
    }

    /** Called on join: someone who logged out mid-screenshare goes back to where they were. */
    public void onJoin(Player p) {
        String b = "ss-restore." + p.getUniqueId();
        if (!this.plugin.data().contains(b + ".world")) {
            return;
        }
        // The server stopped mid-screenshare: make sure they can walk again.
        if (p.getWalkSpeed() == 0F) {
            p.setWalkSpeed(0.2F);
        }
        if (p.getFlySpeed() == 0F) {
            p.setFlySpeed(0.1F);
        }
        World w = Bukkit.getWorld(this.plugin.data().getString(b + ".world", ""));
        if (w != null) {
            final Location l = new Location(w, this.plugin.data().getDouble(b + ".x"), this.plugin.data().getDouble(b + ".y"), this.plugin.data().getDouble(b + ".z"));
            Bukkit.getScheduler().runTask(this.plugin, () -> p.teleport(l));
        }
        this.plugin.data().set(b, null);
        this.plugin.saveData();
    }

    public List<String> targets() {
        List<String> out = new ArrayList<>();
        for (Session s : this.sessions.values()) {
            out.add(s.targetName);
        }
        return out;
    }
}
