package org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Combat system (same kind of features as CelestCombat, written for UnstableFFA).
 *
 *   Tagging        hit a player (or get hit) and both of you are in combat for combat.duration.
 *                  Arrows, tridents and tamed pets count. Only unstableffa.combat.bypass skips it.
 *   Display        time left above the hotbar (and/or a boss bar), with pearl / trident cooldowns.
 *   Commands       whitelist mode (only allowed ones work) or blacklist mode (listed ones are blocked).
 *   Restrictions   no flying, no teleporting, item restrictions, ender pearl and trident cooldowns,
 *                  tridents banned per world, optional no block placing.
 *   Logging out    BODY: a frozen zombie stays for logout.body-seconds. Rejoin in time and you take
 *                  over again; if someone kills it, your items drop and you come back dead.
 *                  KILL: you die on the spot. NONE: nothing. Admin kicks are never punished.
 *   Extras         death effects (lightning / particles), kill rewards (commands with cooldowns),
 *                  newbie PvP protection with a boss bar.
 *
 * combat.enabled: false turns off EVERYTHING above, /combat included. Works with /ze reload.
 *
 * Permissions: unstableffa.combat (status, everyone), unstableffa.combat.admin (admin commands),
 *              unstableffa.combat.bypass (never tagged), unstableffa.combat.commands.bypass
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class CombatTag implements Listener {

    /** A logged-out player's body. */
    private static final class Body {
        UUID owner;
        String name;
        UUID entity;
        ItemStack[] contents;
        int level;
        long until;
        Chunk chunk;
        UUID lastAttacker;
    }

    private final zenithEssentials plugin;
    private final NamespacedKey bodyKey;

    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> opponent = new HashMap<>();
    private final Map<UUID, Long> pearlUntil = new HashMap<>();
    private final Map<UUID, Long> tridentUntil = new HashMap<>();
    private final Map<UUID, BossBar> combatBars = new HashMap<>();
    private final Map<UUID, BossBar> newbieBars = new HashMap<>();
    private final Map<UUID, Long> newbieUntil = new HashMap<>();
    private final Map<UUID, Long> rewardGlobal = new HashMap<>();
    private final Map<String, Long> rewardPair = new HashMap<>(); // killer|victim -> until
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, UUID> bodyByEntity = new HashMap<>();
    private BukkitTask ticker;
    private boolean nextQuitIsKick;

    CombatTag(zenithEssentials plugin) {
        this.plugin = plugin;
        this.bodyKey = new NamespacedKey(plugin, "combat-body");
    }

    // =====================================================================================
    // settings helpers
    // =====================================================================================

    private FileConfiguration c() {
        return this.plugin.getConfig();
    }

    boolean enabled() {
        return this.c().getBoolean("combat.enabled", true);
    }

    /** "20s", "5m", "1h", "1d", "1d_2h_30m", or a plain number of seconds. */
    static long parseTime(String s, long defMs) {
        if (s == null || s.trim().isEmpty()) {
            return defMs;
        }
        String in = s.trim().toLowerCase(Locale.ROOT);
        if (in.matches("\\d+")) {
            return Long.parseLong(in) * 1000L;
        }
        long total = 0L;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(mo|[smhdwy])").matcher(in);
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s": total += n * 1000L; break;
                case "m": total += n * 60000L; break;
                case "h": total += n * 3600000L; break;
                case "d": total += n * 86400000L; break;
                case "w": total += n * 604800000L; break;
                case "mo": total += n * 2592000000L; break;
                case "y": total += n * 31536000000L; break;
                default: break;
            }
        }
        return any ? total : defMs;
    }

    private long time(String path, String def) {
        return parseTime(this.c().getString(path, def), parseTime(def, 0L));
    }

    private boolean inList(String path, String value) {
        for (String s : this.c().getStringList(path)) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String color(String s) {
        return Announcer.color(s == null ? "" : s);
    }

    private void send(Player p, String key, String def, String... ph) {
        String m = this.c().getString("combat.messages." + key, def);
        if (m == null || m.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < ph.length; i += 2) {
            m = m.replace(ph[i], ph[i + 1]);
        }
        p.sendMessage(this.color(m));
    }

    private void sound(Player p, String path, String def) {
        String s = this.c().getString(path, def);
        if (s == null || s.isEmpty()) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.valueOf(s.toUpperCase(Locale.ROOT)), 0.8F, 1.0F);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static long secs(long untilMs) {
        return Math.max(0L, (untilMs - System.currentTimeMillis() + 999L) / 1000L);
    }

    // =====================================================================================
    // start / stop
    // =====================================================================================

    /** Called on enable and on /ze reload. */
    void reload() {
        if (!this.enabled()) {
            this.shutdown();
            return;
        }
        if (this.ticker == null) {
            this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 5L, 5L);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            this.loadNewbie(p);
        }
    }

    /** Plugin stops or combat is turned off: nobody stays tagged, bodies go away (owners stay safe). */
    void shutdown() {
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
        for (UUID id : new ArrayList<>(this.combatUntil.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            }
        }
        for (BossBar b : this.combatBars.values()) {
            b.removeAll();
        }
        for (Map.Entry<UUID, BossBar> en : this.newbieBars.entrySet()) {
            en.getValue().removeAll();
        }
        for (UUID id : new ArrayList<>(this.newbieUntil.keySet())) {
            this.saveNewbie(id);
        }
        this.combatBars.clear();
        this.newbieBars.clear();
        this.newbieUntil.clear();
        this.combatUntil.clear();
        this.opponent.clear();
        this.pearlUntil.clear();
        this.tridentUntil.clear();
        for (Body b : new ArrayList<>(this.bodies.values())) {
            this.removeBody(b);
        }
    }

    // =====================================================================================
    // combat state
    // =====================================================================================

    /** Milliseconds of combat left, 0 if not in combat. */
    long combatLeftMs(UUID id) {
        Long until = this.combatUntil.get(id);
        return until == null ? 0L : Math.max(0L, until - System.currentTimeMillis());
    }

    boolean inCombat(UUID id) {
        Long until = this.combatUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    /** Why a normal hit would not tag this player, or null if it would. */
    private String whyNotTagged(Player p) {
        if (p.hasPermission("unstableffa.combat.bypass")) {
            return "they have unstableffa.combat.bypass (a * permission in LuckPerms gives it too)";
        }
        if (this.inList("combat.disabled-worlds", p.getWorld().getName())) {
            return "combat is off in this world (combat.disabled-worlds)";
        }
        if (this.c().getBoolean("combat.ignore-creative", false)
                && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            return "they are in " + p.getGameMode().name().toLowerCase(Locale.ROOT) + " and combat.ignore-creative is true";
        }
        return null;
    }

    /** Tags a player. force = /combat tag (ignores bypass and worlds). */
    private void tag(Player p, Player other, boolean force) {
        if (!force && this.whyNotTagged(p) != null) {
            return;
        }
        UUID id = p.getUniqueId();
        boolean was = this.inCombat(id);
        long until = System.currentTimeMillis() + this.time("combat.duration", "20s");
        Long cur = this.combatUntil.get(id);
        if (cur == null || until > cur) {
            this.combatUntil.put(id, until);
        }
        if (other != null) {
            this.opponent.put(id, other.getUniqueId());
        }
        if (!was) {
            this.send(p, "tagged", "&#D9381EYou are in combat with &f{player}&#D9381E! Don't log out.",
                    "{player}", other == null ? "someone" : other.getName());
            this.sound(p, "combat.sounds.tagged", "BLOCK_NOTE_BLOCK_BASS");
            if (this.c().getBoolean("combat.disable-flight", true) && p.getGameMode() != GameMode.CREATIVE
                    && p.getGameMode() != GameMode.SPECTATOR && (p.isFlying() || p.getAllowFlight())) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        }
        this.display(p);
    }

    private void untag(Player p, boolean message) {
        UUID id = p.getUniqueId();
        if (this.combatUntil.remove(id) == null) {
            return;
        }
        this.opponent.remove(id);
        BossBar bar = this.combatBars.remove(id);
        if (bar != null) {
            bar.removeAll();
        }
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        if (message) {
            this.send(p, "untagged", "&#FF9147You are no longer in combat.");
            this.sound(p, "combat.sounds.untagged", "ENTITY_EXPERIENCE_ORB_PICKUP");
        }
    }

    // =====================================================================================
    // display: action bar + boss bar, 4 times a second
    // =====================================================================================

    private void tick() {
        if (!this.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Long until = this.combatUntil.get(id);
            if (until != null && until <= now) {
                this.untag(p, true);
            }
            this.display(p);
            this.newbieTick(p, now);
        }
        this.pearlUntil.values().removeIf(t -> t <= now);
        this.tridentUntil.values().removeIf(t -> t <= now);
        for (Body b : new ArrayList<>(this.bodies.values())) {
            if (b.until <= now) {
                this.expire(b);
            }
        }
    }

    private void display(Player p) {
        UUID id = p.getUniqueId();
        boolean combat = this.inCombat(id);
        Long pearl = this.pearlUntil.get(id);
        Long trident = this.tridentUntil.get(id);
        boolean hasPearl = pearl != null && pearl > System.currentTimeMillis();
        boolean hasTrident = trident != null && trident > System.currentTimeMillis();
        if (!combat && !hasPearl && !hasTrident) {
            return;
        }
        if (this.c().getBoolean("combat.display.actionbar", true)) {
            List<String> parts = new ArrayList<>();
            if (combat) {
                parts.add(this.c().getString("combat.display.combat-text", "&#D9381ECombat: {time}s")
                        .replace("{time}", String.valueOf(secs(this.combatUntil.get(id)))));
            }
            if (hasPearl) {
                parts.add(this.c().getString("combat.display.pearl-text", "&7Pearl: {time}s").replace("{time}", String.valueOf(secs(pearl))));
            }
            if (hasTrident) {
                parts.add(this.c().getString("combat.display.trident-text", "&7Trident: {time}s").replace("{time}", String.valueOf(secs(trident))));
            }
            String sep = this.c().getString("combat.display.separator", " &8| ");
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(this.color(String.join(sep, parts))));
        }
        if (combat && this.c().getBoolean("combat.display.bossbar", false)) {
            BossBar bar = this.combatBars.get(id);
            if (bar == null) {
                bar = Bukkit.createBossBar("", this.barColor("combat.display.bossbar-color", BarColor.RED),
                        this.barStyle("combat.display.bossbar-style"));
                bar.addPlayer(p);
                this.combatBars.put(id, bar);
            }
            long left = this.combatUntil.get(id) - System.currentTimeMillis();
            bar.setTitle(this.color(this.c().getString("combat.display.bossbar-title", "&#D9381ECombat: {time}s")
                    .replace("{time}", String.valueOf(secs(this.combatUntil.get(id))))));
            bar.setProgress(Math.max(0.0, Math.min(1.0, left / (double) Math.max(1L, this.time("combat.duration", "20s")))));
        }
    }

    private BarColor barColor(String path, BarColor def) {
        try {
            return BarColor.valueOf(this.c().getString(path, def.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    private BarStyle barStyle(String path) {
        try {
            return BarStyle.valueOf(this.c().getString(path, "SOLID").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BarStyle.SOLID;
        }
    }

    // =====================================================================================
    // tagging events
    // =====================================================================================

    /** The player behind a hit: the player, the shooter of a projectile, or the owner of a pet. */
    private Player attackerOf(Entity d) {
        if (d instanceof Player) {
            return (Player) d;
        }
        if (d instanceof Projectile && this.c().getBoolean("combat.tag-on.projectiles", true)) {
            Object s = ((Projectile) d).getShooter();
            return s instanceof Player ? (Player) s : null;
        }
        if (d instanceof Tameable && this.c().getBoolean("combat.tag-on.pets", true)) {
            Object o = ((Tameable) d).getOwner();
            return o instanceof Player ? (Player) o : null;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!this.enabled()) {
            return;
        }
        Player attacker = this.attackerOf(e.getDamager());
        Entity victimEnt = e.getEntity();

        // a logout body: hitting it puts you in combat
        UUID bodyOwner = this.bodyByEntity.get(victimEnt.getUniqueId());
        if (bodyOwner != null) {
            if (attacker != null) {
                Body b = this.bodies.get(bodyOwner);
                if (b != null) {
                    b.lastAttacker = attacker.getUniqueId();
                }
                this.tag(attacker, null, false);
            }
            return;
        }

        if (!(victimEnt instanceof Player)) {
            if (attacker != null && victimEnt instanceof Mob && this.c().getBoolean("combat.tag-on.hitting-mobs", false)) {
                this.tag(attacker, null, false);
            }
            return;
        }
        Player victim = (Player) victimEnt;

        // newbie protection
        if (this.hasNewbie(victim)) {
            if (attacker != null && this.c().getBoolean("combat.newbie-protection.protect-from-players", true)) {
                e.setCancelled(true);
                this.send(attacker, "newbie-target", "&#D9381E{player} is protected as a new player.", "{player}", victim.getName());
                return;
            }
            if (attacker == null && e.getDamager() instanceof Mob && this.c().getBoolean("combat.newbie-protection.protect-from-mobs", false)) {
                e.setCancelled(true);
                return;
            }
        }
        if (attacker != null && !attacker.equals(victim) && this.hasNewbie(attacker)
                && this.c().getBoolean("combat.newbie-protection.remove-on-attack", true)) {
            this.removeNewbie(attacker, true);
        }

        if (attacker != null && !attacker.equals(victim)) {
            if (this.c().getBoolean("combat.tag-on.players", true)) {
                this.tag(victim, attacker, false);
                this.tag(attacker, victim, false);
            }
        } else if (attacker == null && e.getDamager() instanceof Mob && this.c().getBoolean("combat.tag-on.mobs", false)) {
            this.tag(victim, null, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (!this.enabled()) {
            return;
        }
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            UUID op = this.opponent.get(victim.getUniqueId());
            Player o = op == null ? null : Bukkit.getPlayer(op);
            if (o != null && this.inCombat(victim.getUniqueId())) {
                killer = o; // died to lava / fall in the middle of a fight: the opponent gets the kill
            }
        }
        boolean untagBoth = this.c().getBoolean("combat.untag-on-death", true);
        this.untag(victim, false);
        if (killer != null && !killer.equals(victim)) {
            if (untagBoth) {
                this.untag(killer, true);
            }
            this.reward(killer, victim.getName(), victim.getUniqueId());
        }
        this.deathEffect(victim.getLocation(), killer != null);
    }

    // =====================================================================================
    // restrictions
    // =====================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!this.enabled() || !this.inCombat(p.getUniqueId()) || p.hasPermission("unstableffa.combat.commands.bypass")) {
            return;
        }
        String cmd = e.getMessage().length() > 1 ? e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT) : "";
        int colon = cmd.indexOf(':');
        if (colon != -1) {
            cmd = cmd.substring(colon + 1); // /essentials:home counts as /home
        }
        boolean whitelist = !this.c().getString("combat.commands.mode", "whitelist").equalsIgnoreCase("blacklist");
        boolean listed = this.matches(cmd, this.c().getStringList(whitelist ? "combat.commands.allowed" : "combat.commands.blocked"));
        if (whitelist ? listed : !listed) {
            return;
        }
        e.setCancelled(true);
        this.send(p, "command-blocked", "&#D9381EYou can't use /{command} in combat. &7({time}s left)",
                "{command}", cmd, "{time}", String.valueOf(secs(this.combatUntil.get(p.getUniqueId()))));
    }

    /** "home" matches /home, "tp*" matches /tp, /tpa, /tpaccept... */
    private boolean matches(String cmd, List<String> list) {
        for (String s : list) {
            String x = s.toLowerCase(Locale.ROOT).trim();
            if (x.startsWith("/")) {
                x = x.substring(1);
            }
            if (x.endsWith("*") ? cmd.startsWith(x.substring(0, x.length() - 1)) : cmd.equals(x)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!this.enabled() || !this.c().getBoolean("combat.block-teleport", true) || !this.inCombat(e.getPlayer().getUniqueId())) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return; // pearls, portals and chorus fruit are handled by their own settings
        }
        if (e.getTo() != null && e.getFrom().getWorld() != null && e.getFrom().getWorld().equals(e.getTo().getWorld())
                && e.getFrom().distanceSquared(e.getTo()) < 4.0) {
            return; // tiny corrections (anticheat, freeze)
        }
        e.setCancelled(true);
        this.send(e.getPlayer(), "teleport-blocked", "&#D9381EYou can't teleport in combat.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFly(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (this.enabled() && e.isFlying() && this.c().getBoolean("combat.disable-flight", true) && this.inCombat(p.getUniqueId())
                && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            e.setCancelled(true);
            p.setAllowFlight(false);
            this.send(p, "flight-blocked", "&#D9381EYou can't fly in combat.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (this.enabled() && this.c().getBoolean("combat.block-placing", false) && this.inCombat(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            this.send(e.getPlayer(), "place-blocked", "&#D9381EYou can't place blocks in combat.");
        }
    }

    /** combat.block-containers: no chests, barrels, shulker boxes, ender chests... while in combat. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainer(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player) || !this.enabled() || !this.c().getBoolean("combat.block-containers", false)) {
            return;
        }
        Player p = (Player) e.getPlayer();
        org.bukkit.event.inventory.InventoryType t = e.getInventory().getType();
        boolean container = t == org.bukkit.event.inventory.InventoryType.CHEST || t == org.bukkit.event.inventory.InventoryType.ENDER_CHEST
                || t == org.bukkit.event.inventory.InventoryType.BARREL || t == org.bukkit.event.inventory.InventoryType.SHULKER_BOX
                || t == org.bukkit.event.inventory.InventoryType.HOPPER || t == org.bukkit.event.inventory.InventoryType.DISPENSER
                || t == org.bukkit.event.inventory.InventoryType.DROPPER;
        if (container && e.getInventory().getLocation() != null && this.inCombat(p.getUniqueId())) {
            e.setCancelled(true);
            this.send(p, "container-blocked", "&#D9381EYou can't open containers in combat.");
        }
    }

    /** combat.block-riding: no getting on horses, boats or minecarts while in combat. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent e) {
        if (e.getEntity() instanceof Player && this.enabled() && this.c().getBoolean("combat.block-riding", false)
                && this.inCombat(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
            this.send((Player) e.getEntity(), "riding-blocked", "&#D9381EYou can't ride anything in combat.");
        }
    }

    private boolean restricted(Material m) {
        return this.c().getBoolean("combat.item-restrictions.enabled", false) && this.inList("combat.item-restrictions.items", m.name());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (this.enabled() && this.inCombat(e.getPlayer().getUniqueId()) && this.restricted(e.getItem().getType())) {
            e.setCancelled(true);
            this.send(e.getPlayer(), "item-blocked", "&#D9381EYou can't use {item} in combat.", "{item}", pretty(e.getItem().getType()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (this.enabled() && e.isGliding() && e.getEntity() instanceof Player && this.inCombat(e.getEntity().getUniqueId())
                && this.restricted(Material.ELYTRA)) {
            e.setCancelled(true);
            this.send((Player) e.getEntity(), "item-blocked", "&#D9381EYou can't use {item} in combat.", "{item}", "Elytra");
        }
    }

    /** Ender pearl + trident cooldowns, trident ban per world, and restricted items you right-click. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (!this.enabled() || e.getItem() == null || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player p = e.getPlayer();
        Material m = e.getItem().getType();
        boolean combat = this.inCombat(p.getUniqueId());
        if (m == Material.ENDER_PEARL) {
            if (combat && this.restricted(m)) {
                e.setCancelled(true);
                this.send(p, "item-blocked", "&#D9381EYou can't use {item} in combat.", "{item}", "Ender Pearl");
                return;
            }
            this.cooldown(e, p, combat, "ender-pearl", this.pearlUntil, Material.ENDER_PEARL, "pearl-cooldown", "&#D9381EEnder pearl cooldown: {time}s");
        } else if (m == Material.TRIDENT) {
            if (this.inList("combat.trident.banned-worlds", p.getWorld().getName())) {
                e.setCancelled(true);
                this.send(p, "trident-banned", "&#D9381ETridents are not allowed in this world.");
                return;
            }
            this.cooldown(e, p, combat, "trident", this.tridentUntil, Material.TRIDENT, "trident-cooldown", "&#D9381ETrident cooldown: {time}s");
        } else if (combat && this.restricted(m) && m != Material.ELYTRA) {
            e.setCancelled(true);
            this.send(p, "item-blocked", "&#D9381EYou can't use {item} in combat.", "{item}", pretty(m));
        }
    }

    private void cooldown(PlayerInteractEvent e, Player p, boolean combat, String key, Map<UUID, Long> map, Material mat,
                          String msgKey, String msgDef) {
        long cd = this.time("combat." + key + ".cooldown", "0");
        if (cd <= 0L || (this.c().getBoolean("combat." + key + ".in-combat-only", true) && !combat)
                || this.inList("combat." + key + ".disabled-worlds", p.getWorld().getName())) {
            return;
        }
        Long until = map.get(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            e.setCancelled(true);
            this.send(p, msgKey, msgDef, "{time}", String.valueOf(secs(until)));
            return;
        }
        map.put(p.getUniqueId(), System.currentTimeMillis() + cd);
        p.setCooldown(mat, (int) (cd / 50L)); // the vanilla cooldown overlay on the item
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLand(ProjectileHitEvent e) {
        if (!this.enabled() || !(e.getEntity().getShooter() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity().getShooter();
        boolean pearl = e.getEntity() instanceof EnderPearl && this.c().getBoolean("combat.ender-pearl.refresh-combat-on-land", false);
        boolean trident = e.getEntity() instanceof Trident && this.c().getBoolean("combat.trident.refresh-combat-on-land", false);
        if ((pearl || trident) && this.inCombat(p.getUniqueId())) {
            this.tag(p, null, false);
        }
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (!w.isEmpty()) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }

    // =====================================================================================
    // death effects + kill rewards
    // =====================================================================================

    private void deathEffect(Location at, boolean byPlayer) {
        if (!this.c().getBoolean("combat.death-effects.enabled", false) || at.getWorld() == null) {
            return;
        }
        if (this.c().getBoolean("combat.death-effects.only-player-kills", true) && !byPlayer) {
            return;
        }
        if (this.c().getBoolean("combat.death-effects.lightning", true)) {
            at.getWorld().strikeLightningEffect(at); // looks and sounds like lightning, does no damage
        }
        if (this.c().getBoolean("combat.death-effects.particles", true)) {
            at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.02);
        }
    }

    private void reward(Player killer, String victimName, UUID victimId) {
        if (!this.c().getBoolean("combat.kill-rewards.enabled", false)) {
            return;
        }
        long now = System.currentTimeMillis();
        String pair = killer.getUniqueId() + "|" + victimId;
        if (this.c().getBoolean("combat.kill-rewards.global-cooldown", false)) {
            Long g = this.rewardGlobal.get(killer.getUniqueId());
            if (g != null && g > now) {
                return;
            }
            this.rewardGlobal.put(killer.getUniqueId(), now + this.time("combat.kill-rewards.global-cooldown-time", "5m"));
        } else if (this.c().getBoolean("combat.kill-rewards.same-player-cooldown", true)) {
            Long t = this.rewardPair.get(pair);
            if (t != null && t > now) {
                this.send(killer, "reward-cooldown", "&7No reward: you killed {player} too recently.", "{player}", victimName);
                return;
            }
            this.rewardPair.put(pair, now + this.time("combat.kill-rewards.same-player-cooldown-time", "1d"));
        }
        for (String cmd : this.c().getStringList("combat.kill-rewards.commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{killer}", killer.getName()).replace("{victim}", victimName));
        }
    }

    // =====================================================================================
    // newbie protection (counts only while online, saved in data.yml -> combat-newbie.<uuid>)
    // =====================================================================================

    private boolean hasNewbie(Player p) {
        Long t = this.newbieUntil.get(p.getUniqueId());
        return this.c().getBoolean("combat.newbie-protection.enabled", false) && t != null && t > System.currentTimeMillis();
    }

    private void loadNewbie(Player p) {
        if (!this.c().getBoolean("combat.newbie-protection.enabled", false)) {
            return;
        }
        String path = "combat-newbie." + p.getUniqueId();
        long left;
        if (!p.hasPlayedBefore() && !this.plugin.data().contains(path)) {
            left = this.time("combat.newbie-protection.duration", "10m");
        } else {
            left = this.plugin.data().getLong(path, 0L);
        }
        if (left > 0L) {
            this.newbieUntil.put(p.getUniqueId(), System.currentTimeMillis() + left);
        }
    }

    private void saveNewbie(UUID id) {
        Long t = this.newbieUntil.get(id);
        long left = t == null ? 0L : Math.max(0L, t - System.currentTimeMillis());
        this.plugin.data().set("combat-newbie." + id, left > 0L ? left : 0L);
        this.plugin.saveData();
    }

    private void removeNewbie(Player p, boolean message) {
        this.newbieUntil.remove(p.getUniqueId());
        this.plugin.data().set("combat-newbie." + p.getUniqueId(), 0L);
        this.plugin.saveData();
        BossBar b = this.newbieBars.remove(p.getUniqueId());
        if (b != null) {
            b.removeAll();
        }
        if (message) {
            this.send(p, "newbie-ended", "&#FF8330Your new player protection has ended.");
        }
    }

    private void newbieTick(Player p, long now) {
        Long t = this.newbieUntil.get(p.getUniqueId());
        if (t == null) {
            return;
        }
        if (t <= now || !this.c().getBoolean("combat.newbie-protection.enabled", false)) {
            this.removeNewbie(p, t <= now);
            return;
        }
        if (this.c().getBoolean("combat.newbie-protection.bossbar", true)) {
            BossBar b = this.newbieBars.get(p.getUniqueId());
            if (b == null) {
                b = Bukkit.createBossBar("", this.barColor("combat.newbie-protection.bossbar-color", BarColor.GREEN), BarStyle.SOLID);
                b.addPlayer(p);
                this.newbieBars.put(p.getUniqueId(), b);
            }
            long left = t - now;
            b.setTitle(this.color(this.c().getString("combat.newbie-protection.bossbar-title", "&#FF9147PvP protection: &f{time}")
                    .replace("{time}", this.plugin.duration(left))));
            b.setProgress(Math.max(0.0, Math.min(1.0, left / (double) Math.max(1L, this.time("combat.newbie-protection.duration", "10m")))));
        }
    }

    // =====================================================================================
    // logging out: the body
    // =====================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent e) {
        if (!e.isCancelled() && this.c().getBoolean("combat.exempt-admin-kick", true)) {
            this.nextQuitIsKick = true;
            UUID op = this.opponent.get(e.getPlayer().getUniqueId());
            this.untag(e.getPlayer(), false); // kicked by staff or the server: never punished
            Player o = op == null ? null : Bukkit.getPlayer(op);
            if (o != null) {
                this.untag(o, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean kicked = this.nextQuitIsKick;
        this.nextQuitIsKick = false;
        BossBar nb = this.newbieBars.remove(id);
        if (nb != null) {
            nb.removeAll();
        }
        if (this.newbieUntil.containsKey(id)) {
            this.saveNewbie(id);
            this.newbieUntil.remove(id);
        }
        BossBar cb = this.combatBars.remove(id);
        if (cb != null) {
            cb.removeAll();
        }
        if (!this.enabled() || kicked || !this.inCombat(id) || p.isDead()) {
            this.combatUntil.remove(id);
            this.opponent.remove(id);
            return;
        }
        UUID op = this.opponent.remove(id);
        this.combatUntil.remove(id);

        String bc = this.c().getString("combat.messages.logout-broadcast", "&#D9381E{player} logged out in combat!");
        if (bc != null && !bc.isEmpty()) {
            String out = this.color(bc.replace("{player}", p.getName()));
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) {
                    o.sendMessage(out);
                }
            }
        }
        String mode = this.c().getString("combat.logout.mode", "BODY").toUpperCase(Locale.ROOT);
        Player killer = op == null ? null : Bukkit.getPlayer(op);
        if (mode.equals("KILL")) {
            if (killer != null) {
                p.damage(10000.0, killer); // the opponent gets the kill
            }
            p.setHealth(0.0);
            this.plugin.data().set("combat-dead." + id, killer == null ? "-" : killer.getName());
            this.plugin.saveData();
            this.deathEffect(p.getLocation(), killer != null);
            if (killer != null) {
                this.reward(killer, p.getName(), id);
            }
        } else if (mode.equals("BODY")) {
            this.spawnBody(p, op);
        }
    }

    private void spawnBody(Player p, UUID lastAttacker) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Body b = new Body();
        b.owner = p.getUniqueId();
        b.name = p.getName();
        b.contents = cloneAll(p.getInventory().getContents());
        b.level = p.getLevel();
        b.until = System.currentTimeMillis() + this.time("combat.logout.body-time", "30s");
        b.chunk = loc.getChunk();
        b.lastAttacker = lastAttacker;
        b.chunk.addPluginChunkTicket(this.plugin); // stays loaded while the body is there

        EntityType type;
        try {
            type = EntityType.valueOf(this.c().getString("combat.logout.body-type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            type = EntityType.ZOMBIE;
        }
        if (!type.isAlive() || !type.isSpawnable()) {
            type = EntityType.ZOMBIE;
        }
        Entity ent = w.spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity)) {
            ent.remove();
            b.chunk.removePluginChunkTicket(this.plugin);
            return;
        }
        LivingEntity le = (LivingEntity) ent;
        le.setAI(!this.c().getBoolean("combat.logout.frozen", true));
        le.setSilent(true);
        le.setRemoveWhenFarAway(false);
        le.setCanPickupItems(false);
        if (le instanceof Zombie) {
            ((Zombie) le).setAdult();
        }
        le.setCustomName(this.color(this.c().getString("combat.logout.body-name", "&#D9381E{player} &7(logged out)").replace("{player}", p.getName())));
        le.setCustomNameVisible(true);
        try {
            le.setHealth(Math.max(1.0, Math.min(p.getHealth(), le.getMaxHealth())));
        } catch (IllegalArgumentException ignored) {
        }
        EntityEquipment eq = le.getEquipment();
        if (eq != null && this.c().getBoolean("combat.logout.show-armor", true)) {
            eq.setArmorContents(cloneAll(p.getInventory().getArmorContents()));
            eq.setItemInMainHand(p.getInventory().getItemInMainHand().clone());
            eq.setHelmetDropChance(0F);
            eq.setChestplateDropChance(0F);
            eq.setLeggingsDropChance(0F);
            eq.setBootsDropChance(0F);
            eq.setItemInMainHandDropChance(0F);
        }
        le.getPersistentDataContainer().set(this.bodyKey, PersistentDataType.STRING, b.owner.toString());
        b.entity = le.getUniqueId();
        this.bodies.put(b.owner, b);
        this.bodyByEntity.put(b.entity, b.owner);
    }

    private static ItemStack[] cloneAll(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null ? null : in[i].clone();
        }
        return out;
    }

    private LivingEntity entityOf(Body b) {
        Entity e = Bukkit.getEntity(b.entity);
        return e instanceof LivingEntity ? (LivingEntity) e : null;
    }

    private void forget(Body b) {
        this.bodies.remove(b.owner);
        this.bodyByEntity.remove(b.entity);
        if (b.chunk != null) {
            b.chunk.removePluginChunkTicket(this.plugin);
        }
    }

    private void removeBody(Body b) {
        LivingEntity le = this.entityOf(b);
        if (le != null) {
            le.remove();
        }
        this.forget(b);
    }

    /** Time ran out and nobody killed the body. */
    private void expire(Body b) {
        if (this.c().getString("combat.logout.on-expire", "SAFE").equalsIgnoreCase("KILL")) {
            LivingEntity le = this.entityOf(b);
            this.bodyDied(b, le == null ? null : le.getLocation(), null);
        }
        this.removeBody(b);
    }

    private void bodyDied(Body b, Location where, Player killer) {
        if (where != null && where.getWorld() != null) {
            for (ItemStack it : b.contents) {
                if (it != null && !it.getType().isAir()) {
                    where.getWorld().dropItemNaturally(where, it);
                }
            }
        }
        this.plugin.data().set("combat-dead." + b.owner, killer == null ? "-" : killer.getName());
        this.plugin.saveData();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyDeath(EntityDeathEvent e) {
        UUID owner = this.bodyByEntity.get(e.getEntity().getUniqueId());
        if (owner == null) {
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        Body b = this.bodies.get(owner);
        if (b == null) {
            return;
        }
        Player killer = e.getEntity().getKiller();
        if (killer == null && b.lastAttacker != null) {
            killer = Bukkit.getPlayer(b.lastAttacker);
        }
        if (this.c().getBoolean("combat.logout.drop-xp", true)) {
            e.setDroppedExp(Math.min(100, b.level * 7));
        }
        this.bodyDied(b, e.getEntity().getLocation(), killer);
        String m = this.c().getString("combat.messages.body-killed-broadcast", "&#D9381E{player} &7was killed while logged out{by}.");
        if (m != null && !m.isEmpty()) {
            Bukkit.broadcastMessage(this.color(m.replace("{player}", b.name).replace("{by}", killer == null ? "" : " by &f" + killer.getName())));
        }
        this.deathEffect(e.getEntity().getLocation(), killer != null);
        if (killer != null) {
            this.reward(killer, b.name, owner);
        }
        this.forget(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyBurn(EntityCombustEvent e) {
        if (this.bodyByEntity.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyTarget(EntityTargetEvent e) {
        if (this.bodyByEntity.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyEnvDamage(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent) && this.bodyByEntity.containsKey(e.getEntity().getUniqueId())
                && !this.c().getBoolean("combat.logout.environment-damage", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyInteract(PlayerInteractEntityEvent e) {
        if (this.bodyByEntity.containsKey(e.getRightClicked().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (this.enabled()) {
            this.loadNewbie(p);
        }
        final String dead = this.plugin.data().getString("combat-dead." + id);
        if (dead != null) {
            this.plugin.data().set("combat-dead." + id, null);
            this.plugin.saveData();
            Body b = this.bodies.get(id);
            if (b != null) {
                this.removeBody(b);
            }
            boolean keptItems = this.c().getString("combat.logout.mode", "BODY").equalsIgnoreCase("KILL");
            if (!keptItems) {
                p.getInventory().clear(); // the body already dropped them
                p.setLevel(0);
                p.setExp(0F);
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (!keptItems) {
                    p.setHealth(0.0);
                }
                this.send(p, "died-while-away", "&#D9381EYou were killed for logging out in combat{by}.",
                        "{by}", dead.equals("-") ? "" : " (by " + dead + ")");
            });
            return;
        }
        Body b = this.bodies.get(id);
        if (b == null) {
            return;
        }
        LivingEntity le = this.entityOf(b);
        if (le != null) {
            final Location at = le.getLocation();
            final double hp = le.getHealth();
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                p.teleport(at);
                p.setHealth(Math.max(0.5, Math.min(hp, p.getMaxHealth())));
            });
        }
        this.removeBody(b);
        if (this.enabled()) {
            this.tag(p, b.lastAttacker == null ? null : Bukkit.getPlayer(b.lastAttacker), true);
            this.send(p, "rejoined", "&#FF8330You came back in time. You are still in combat!");
        }
    }

    // =====================================================================================
    // /combat
    // =====================================================================================

    void handle(CommandSender s, String[] args) {
        if (!this.enabled()) {
            s.sendMessage(this.color("&#D9381EUnknown command. Type \"/help\" for help."));
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission("unstableffa.combat.admin");

        if (sub.equals("debug") && s instanceof Player) {
            Player p = (Player) s;
            Long until = this.combatUntil.get(p.getUniqueId());
            s.sendMessage(this.color("&8» <gradient:#FF7518:#FFFFFF>&lCombat</gradient><gradient:#FFFFFF:#FF7518>&l debug</gradient>"));
            s.sendMessage(this.color("&7enabled: &f" + this.enabled() + "  &7timer running: &f" + (this.ticker != null)
                    + "  &7duration: &f" + (this.time("combat.duration", "20s") / 1000L) + "s"));
            s.sendMessage(this.color("&7in combat: &f" + (until == null ? "no" : secs(until) + "s left")
                    + "  &7gamemode: &f" + p.getGameMode() + "  &7op: &f" + p.isOp()));
            String why = this.whyNotTagged(p);
            s.sendMessage(this.color("&7hits tag you: &f" + (why == null ? "yes" : "no, " + why)));
            return;
        }
        if (!admin || sub.isEmpty()) {
            if (!(s instanceof Player)) {
                s.sendMessage("Use /combat <tag|untag|list|newbie> ...");
                return;
            }
            Player p = (Player) s;
            if (this.inCombat(p.getUniqueId())) {
                p.sendMessage(this.color("&8» &#D9381EYou are in combat for &f" + secs(this.combatUntil.get(p.getUniqueId())) + "s&#D9381E."));
            } else {
                p.sendMessage(this.color("&8» &#FF9147You are not in combat."));
            }
            if (this.hasNewbie(p)) {
                p.sendMessage(this.color("&8» &#FF9147You have new player protection for &f"
                        + this.plugin.duration(this.newbieUntil.get(p.getUniqueId()) - System.currentTimeMillis()) + "&#FF9147."));
            }
            if (admin) {
                p.sendMessage(this.color("&7Admin: &f/combat tag <player> [player2]&7, &f/combat untag <player|all>&7, "
                        + "&f/combat list&7, &f/combat newbie <give|remove> <player>&7, &f/combat debug"));
            }
            return;
        }
        switch (sub) {
            case "tag": {
                if (args.length < 2) {
                    s.sendMessage(this.color("&#D9381EUse /combat tag <player> [player2]"));
                    return;
                }
                Player a = Bukkit.getPlayer(args[1]);
                Player b = args.length > 2 ? Bukkit.getPlayer(args[2]) : null;
                if (a == null || (args.length > 2 && b == null)) {
                    s.sendMessage(this.color("&8» &#D9381E&lError! &7Player not found."));
                    return;
                }
                this.tag(a, b, true);
                if (b != null) {
                    this.tag(b, a, true);
                }
                s.sendMessage(this.color("&8» &#FF9147&lDone! &f" + a.getName() + (b != null ? " &7and &f" + b.getName() + " &7are" : " &7is") + " now in combat."));
                return;
            }
            case "untag":
            case "removetag": {
                if (args.length < 2) {
                    s.sendMessage(this.color("&#D9381EUse /combat untag <player|all>"));
                    return;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    for (Player o : Bukkit.getOnlinePlayers()) {
                        this.untag(o, true);
                    }
                    s.sendMessage(this.color("&8» &#FF9147&lDone! &7Everyone is out of combat."));
                    return;
                }
                Player t = Bukkit.getPlayer(args[1]);
                if (t == null) {
                    s.sendMessage(this.color("&8» &#D9381E&lError! &7Player not found."));
                    return;
                }
                this.untag(t, true);
                s.sendMessage(this.color("&8» &#FF9147&lDone! &f" + t.getName() + " &7is out of combat."));
                return;
            }
            case "list": {
                s.sendMessage(this.color("&8» <gradient:#FF7518:#FFFFFF>&lCom</gradient><gradient:#FFFFFF:#FF7518>&lbat</gradient>"));
                int n = 0;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (this.inCombat(o.getUniqueId())) {
                        UUID op = this.opponent.get(o.getUniqueId());
                        Player opp = op == null ? null : Bukkit.getPlayer(op);
                        s.sendMessage(this.color("&7- &f" + o.getName() + " &8(" + secs(this.combatUntil.get(o.getUniqueId())) + "s"
                                + (opp == null ? "" : ", vs " + opp.getName()) + ")"));
                        n++;
                    }
                }
                for (Body b : this.bodies.values()) {
                    s.sendMessage(this.color("&7- &f" + b.name + " &8(logged out, body for " + secs(b.until) + "s)"));
                    n++;
                }
                if (n == 0) {
                    s.sendMessage(this.color("&7Nobody is in combat."));
                }
                return;
            }
            case "newbie": {
                if (args.length < 3) {
                    s.sendMessage(this.color("&#D9381EUse /combat newbie <give|remove> <player>"));
                    return;
                }
                Player t = Bukkit.getPlayer(args[2]);
                if (t == null) {
                    s.sendMessage(this.color("&8» &#D9381E&lError! &7Player not found."));
                    return;
                }
                if (args[1].equalsIgnoreCase("give")) {
                    this.newbieUntil.put(t.getUniqueId(), System.currentTimeMillis() + this.time("combat.newbie-protection.duration", "10m"));
                    this.saveNewbie(t.getUniqueId());
                    s.sendMessage(this.color("&8» &#FF9147&lDone! &f" + t.getName() + " &7has new player protection again."));
                } else {
                    this.removeNewbie(t, true);
                    s.sendMessage(this.color("&8» &#FF9147&lDone! &7Removed &f" + t.getName() + "&7's protection."));
                }
                return;
            }
            default:
                s.sendMessage(this.color("&7Use &f/combat tag|untag|list|newbie|debug"));
        }
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (!this.enabled()) {
            return out;
        }
        boolean admin = s.hasPermission("unstableffa.combat.admin");
        if (args.length == 1) {
            for (String x : admin ? new String[]{"tag", "untag", "list", "newbie", "debug"} : new String[]{"debug"}) {
                if (x.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(x);
                }
            }
            return out;
        }
        if (!admin) {
            return out;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("newbie") && args.length == 2) {
            for (String x : new String[]{"give", "remove"}) {
                if (x.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(x);
                }
            }
            return out;
        }
        if ((first.equals("tag") && args.length <= 3) || (first.equals("untag") && args.length == 2) || (first.equals("newbie") && args.length == 3)) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (first.equals("untag") && "all".startsWith(last)) {
                out.add("all");
            }
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(o.getName());
                }
            }
        }
        return out;
    }
}
