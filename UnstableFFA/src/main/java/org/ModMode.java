package org;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * /modmode  -  a staff mode for watching and checking players.
 *
 * Turning it on:  your items, armor, gamemode and flight are saved to plugins/UnstableFFA/modmode/<uuid>.yml,
 *                 your inventory is cleared and you get a hotbar of tools (right-click a player with them):
 *
 *     slot 1  Compass     right-click anywhere: teleport to a random player
 *     slot 2  Chest       right-click a player: /invsee   (sneak + right-click: /enderchest)
 *     slot 3  Blue ice    right-click a player: /freeze
 *     slot 4  Book        right-click a player: /history
 *     slot 5  Ender eye   right-click a player: /live
 *     slot 8  Dye         right-click: turn vanish on / off
 *     slot 9  Barrier     right-click: leave modmode
 *
 * Turning it off: everything is given back. If the server crashes while you are in modmode, the
 * saved file gives your items back the next time you join. Nobody can lose items to modmode.
 *
 * While in modmode you can't break or place blocks, drop or pick up items, or hurt anyone.
 *
 * Permission:  unstableffa.modmode        Settings: config.yml -> modmode
 * Works on its own: it only needs the plugin's config.yml and the vanish bridge in the main class.
 */
final class ModMode implements Listener {

    private final zenithEssentials plugin;
    private final NamespacedKey toolKey;
    private final File folder;
    private final Set<UUID> active = new HashSet<>();
    private final Random random = new Random();

    ModMode(zenithEssentials plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "modmode_tool");
        this.folder = new File(plugin.getDataFolder(), "modmode");
        this.folder.mkdirs();
    }

    boolean isActive(Player p) {
        return this.active.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Only players can use /modmode.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.modmode")) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("modmode.enabled", true)) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Modmode is turned off in the config."));
            return;
        }
        if (this.isActive(p)) {
            this.leave(p, true);
        } else {
            this.enter(p);
        }
    }

    // ------------------------------------------------------------------ enter / leave

    private void enter(Player p) {
        FileConfiguration c = this.plugin.getConfig();
        boolean wasVanished = this.plugin.vanished(p.getUniqueId());
        boolean weVanish = c.getBoolean("modmode.vanish", true) && !wasVanished;

        // Save everything FIRST. If saving fails, nothing is touched and nothing can be lost.
        FileConfiguration y = new YamlConfiguration();
        y.set("contents", Arrays.asList(p.getInventory().getContents()));
        y.set("gamemode", p.getGameMode().name());
        y.set("allow-flight", p.getAllowFlight());
        y.set("flying", p.isFlying());
        y.set("invulnerable", p.isInvulnerable());
        y.set("we-vanished", weVanish);
        try {
            y.save(this.fileOf(p.getUniqueId()));
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save modmode data for " + p.getName() + ": " + ex.getMessage());
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Could not save your items, so modmode was NOT turned on."));
            return;
        }

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));

        GameMode gm;
        try {
            gm = GameMode.valueOf(c.getString("modmode.gamemode", "SURVIVAL").toUpperCase());
        } catch (IllegalArgumentException ex) {
            gm = GameMode.SURVIVAL;
        }
        p.setGameMode(gm);
        if (c.getBoolean("modmode.fly", true)) {
            p.setAllowFlight(true);
            p.setFlying(true);
        }
        if (c.getBoolean("modmode.invulnerable", true)) {
            p.setInvulnerable(true);
        }
        if (c.getBoolean("modmode.night-vision", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
        }
        if (weVanish) {
            this.plugin.setVanishedQuiet(p, true);
            if (c.getBoolean("modmode.fake-messages", true)) {
                this.plugin.vanishBroadcast(p, false); // "Name left the game"
            }
        }
        this.giveTools(p);
        this.active.add(p.getUniqueId());
        p.updateInventory();
        p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lModm</gradient><gradient:#FFFFFF:#FF7518>&lode</gradient> &8• <gradient:#FF7518:#FFFFFF>&lO</gradient><gradient:#FFFFFF:#FF7518>&lN</gradient> &7- your items are saved. Use the barrier in slot 9 or /modmode to leave."));
    }

    private void leave(Player p, boolean notify) {
        this.active.remove(p.getUniqueId());
        File f = this.fileOf(p.getUniqueId());
        if (!f.exists()) {
            return;
        }
        FileConfiguration y = YamlConfiguration.loadConfiguration(f);

        List<?> list = y.getList("contents");
        if (list != null) {
            ItemStack[] arr = new ItemStack[list.size()];
            for (int i = 0; i < arr.length; i++) {
                Object o = list.get(i);
                arr[i] = o instanceof ItemStack ? (ItemStack) o : null;
            }
            try {
                p.getInventory().setContents(arr);
            } catch (IllegalArgumentException ex) {
                // Never lose the items: if they don't fit back in one go, put them in one by one.
                p.getInventory().clear();
                for (ItemStack it : arr) {
                    if (it != null) {
                        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                    }
                }
            }
        }
        try {
            p.setGameMode(GameMode.valueOf(y.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        boolean flight = y.getBoolean("allow-flight", false);
        p.setAllowFlight(flight);
        p.setFlying(flight && y.getBoolean("flying", false));
        p.setInvulnerable(y.getBoolean("invulnerable", false));
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        if (y.getBoolean("we-vanished", false)) {
            this.plugin.setVanishedQuiet(p, false);
            if (notify && this.plugin.getConfig().getBoolean("modmode.fake-messages", true)) {
                this.plugin.vanishBroadcast(p, true); // "Name joined the game"
            }
        }
        f.delete();
        p.updateInventory();
        if (notify) {
            p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lModm</gradient><gradient:#FFFFFF:#FF7518>&lode</gradient> &8• <gradient:#FF7518:#FFFFFF>&lOF</gradient><gradient:#FFFFFF:#FF7518>&lF</gradient> &7- your items are back."));
        }
    }

    /** Called when the plugin stops: everyone gets their items back. */
    void shutdown() {
        for (UUID id : new ArrayList<>(this.active)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                this.leave(p, false);
            }
        }
    }

    private File fileOf(UUID id) {
        return new File(this.folder, id + ".yml");
    }

    // ------------------------------------------------------------------ tools

    private void giveTools(Player p) {
        FileConfiguration c = this.plugin.getConfig();
        if (c.getBoolean("modmode.tools.random-teleport", true)) {
            p.getInventory().setItem(0, this.tool(Material.COMPASS, "teleport", "<gradient:#FF7518:#FFFFFF>&lRandom t</gradient><gradient:#FFFFFF:#FF7518>&leleport</gradient>", "&7Right-click: teleport to a random player"));
        }
        if (c.getBoolean("modmode.tools.inventory", true)) {
            p.getInventory().setItem(1, this.tool(Material.CHEST, "inventory", "<gradient:#FF7518:#FFFFFF>&lInven</gradient><gradient:#FFFFFF:#FF7518>&ltory</gradient>", "&7Right-click a player: /invsee", "&7Sneak + right-click: /enderchest"));
        }
        if (c.getBoolean("modmode.tools.freeze", true)) {
            p.getInventory().setItem(2, this.tool(Material.BLUE_ICE, "freeze", "<gradient:#FF7518:#FFFFFF>&lFre</gradient><gradient:#FFFFFF:#FF7518>&leze</gradient>", "&7Right-click a player: /freeze"));
        }
        if (c.getBoolean("modmode.tools.history", true)) {
            p.getInventory().setItem(3, this.tool(Material.BOOK, "history", "<gradient:#FF7518:#FFFFFF>&lHist</gradient><gradient:#FFFFFF:#FF7518>&lory</gradient>", "&7Right-click a player: /history"));
        }
        if (c.getBoolean("modmode.tools.live", true)) {
            p.getInventory().setItem(4, this.tool(Material.ENDER_EYE, "live", "<gradient:#FF7518:#FFFFFF>&lLive </gradient><gradient:#FFFFFF:#FF7518>&lview</gradient>", "&7Right-click a player: /live"));
        }
        if (c.getBoolean("modmode.tools.vanish", true)) {
            p.getInventory().setItem(7, this.vanishTool(this.plugin.vanished(p.getUniqueId())));
        }
        p.getInventory().setItem(8, this.tool(Material.BARRIER, "exit", "<gradient:#FF7518:#FFFFFF>&lLeave m</gradient><gradient:#FFFFFF:#FF7518>&lodmode</gradient>", "&7Right-click: get your items back"));
        p.getInventory().setHeldItemSlot(0);
    }

    private ItemStack vanishTool(boolean vanished) {
        return vanished
                ? this.tool(Material.LIME_DYE, "vanish", "&#FF9147&lVanish: ON", "&7Right-click: show yourself")
                : this.tool(Material.GRAY_DYE, "vanish", "&7&lVanish: OFF", "&7Right-click: hide yourself");
    }

    private ItemStack tool(Material m, String id, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Announcer.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) {
            l.add(Announcer.color(s));
        }
        meta.setLore(l);
        meta.getPersistentDataContainer().set(this.toolKey, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    private String toolId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return null;
        }
        return it.getItemMeta().getPersistentDataContainer().get(this.toolKey, PersistentDataType.STRING);
    }

    private void useTool(Player p, String id) {
        switch (id) {
            case "teleport": {
                List<Player> pool = new ArrayList<>();
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!o.equals(p) && !this.active.contains(o.getUniqueId()) && !this.plugin.vanished(o.getUniqueId())) {
                        pool.add(o);
                    }
                }
                if (pool.isEmpty()) {
                    p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Nobody to teleport to."));
                    return;
                }
                Player t = pool.get(this.random.nextInt(pool.size()));
                p.teleport(t.getLocation());
                p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lModm</gradient><gradient:#FFFFFF:#FF7518>&lode</gradient> &8• &7Teleported to &f" + t.getName() + "&7."));
                return;
            }
            case "vanish": {
                boolean now = !this.plugin.vanished(p.getUniqueId());
                this.plugin.setVanishedQuiet(p, now);
                p.getInventory().setItem(7, this.vanishTool(now));
                return;
            }
            case "exit":
                this.leave(p, true);
                return;
            default: {
                // The click can arrive here instead of (or as well as) the "right-click a player" event.
                // If a player is right in front of you, use the tool on them - no hint message.
                Player target = this.lookingAt(p);
                if (target != null) {
                    this.useOnPlayer(p, id, target);
                } else {
                    p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lModm</gradient><gradient:#FFFFFF:#FF7518>&lode</gradient> &8• &7Right-click a &fplayer &7with this item."));
                }
            }
        }
    }

    private final java.util.Map<UUID, Long> lastUse = new java.util.HashMap<>();

    /** The player you are aiming at (within 5 blocks), or null. */
    private Player lookingAt(Player p) {
        org.bukkit.util.RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 5.0, 0.2,
                en -> en instanceof Player && !en.equals(p));
        return r != null && r.getHitEntity() instanceof Player ? (Player) r.getHitEntity() : null;
    }

    /** Runs the tool on a player. Both click events can fire for one click, so only the first one counts. */
    private void useOnPlayer(Player p, String id, Player target) {
        long now = System.currentTimeMillis();
        Long last = this.lastUse.get(p.getUniqueId());
        if (last != null && now - last < 300L) {
            return;
        }
        this.lastUse.put(p.getUniqueId(), now);
        String name = target.getName();
        switch (id) {
            case "inventory":
                p.performCommand((p.isSneaking() ? "enderchest " : "invsee ") + name);
                break;
            case "freeze":
                p.performCommand("freeze " + name);
                break;
            case "history":
                p.performCommand("history " + name);
                break;
            case "live":
                p.performCommand("live " + name);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!this.isActive(p) || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String id = this.toolId(e.getItem());
        if (id != null) {
            e.setCancelled(true);
            this.useTool(p, id);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!this.isActive(p) || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String id = this.toolId(p.getInventory().getItemInMainHand());
        if (id == null) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getRightClicked() instanceof Player)) {
            return;
        }
        this.useOnPlayer(p, id, (Player) e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (this.isActive(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (this.isActive(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (this.isActive(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (this.isActive(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && this.isActive((Player) e.getEntity())
                && !this.plugin.getConfig().getBoolean("modmode.pickup-items", false)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player) || !this.isActive((Player) e.getWhoClicked())) {
            return;
        }
        boolean ownInventory = e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.PLAYER;
        if (ownInventory || e.isShiftClick() || e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player && this.isActive((Player) e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && this.isActive((Player) e.getDamager())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player && this.isActive((Player) e.getTarget())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        this.lastUse.remove(e.getPlayer().getUniqueId());
        if (this.isActive(e.getPlayer())) {
            this.leave(e.getPlayer(), false);
        }
    }

    /** The server crashed (or was killed) while the player was in modmode: give everything back now. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!this.fileOf(p.getUniqueId()).exists() || this.isActive(p)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline() && this.fileOf(p.getUniqueId()).exists()) {
                this.leave(p, false);
                p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lModm</gradient><gradient:#FFFFFF:#FF7518>&lode</gradient> &8• &7The server restarted while you were in modmode - your items are back."));
            }
        }, 5L);
    }
}
