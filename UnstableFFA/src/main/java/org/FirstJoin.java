package org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * First-join steps: a NEW player must read the rules and accept them, then read the guide and accept it,
 * before they can do anything on the server. Only for players who join for the very first time.
 *
 * While the steps are not finished the player can't move, chat, use commands, break or place blocks,
 * touch items, hurt or be hurt, and closing the menu opens it again right away. Each step has a minimum
 * reading time before its accept button works. Quitting does not skip it: the next join continues at the
 * same step. "I do not accept" kicks the player.
 *
 * Settings:    config.yml -> first-join       (the rules come from rules.list, the guide from guide.topics)
 * Permission:  unstableffa.firstjoin.bypass  (staff and ops skip the steps)
 * Progress:    data.yml -> first-join.<uuid> = the step the player is on (removed when they are done)
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class FirstJoin implements Listener {

    private static final class Holder implements InventoryHolder {
        final String step;

        Holder(String step) {
            this.step = step;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int SIZE = 45;
    private static final int DECLINE_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int ACCEPT_SLOT = 41;

    private final zenithEssentials plugin;
    private final Set<UUID> switching = new HashSet<>();
    private final Map<UUID, Long> openedAt = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private final Map<UUID, Long> notBefore = new HashMap<>();
    private final Set<UUID> active = new HashSet<>(); // players who are in the steps right now (keeps the checks cheap)

    FirstJoin(zenithEssentials plugin) {
        this.plugin = plugin;
        // Safety net: anyone who still has steps to do always has the menu open.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    private FileConfiguration c() {
        return this.plugin.getConfig();
    }

    // ------------------------------------------------------------------ state

    private boolean enabled() {
        return this.c().getBoolean("first-join.enabled", false) && !this.steps().isEmpty();
    }

    private List<String> steps() {
        List<String> out = new ArrayList<>();
        for (String s : this.c().getStringList("first-join.steps")) {
            String k = s.toLowerCase(Locale.ROOT);
            if ((k.equals("rules") || k.equals("guide")) && !out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** The step this player is on, or null when they have nothing to do. */
    private String current(Player p) {
        if (!this.enabled()) {
            return null;
        }
        // (who gets steps at all is decided in onJoin, so staff are only here after a /kickall)
        String s = this.plugin.data().getString("first-join." + p.getUniqueId(), null);
        return s != null && this.steps().contains(s) ? s : null;
    }

    /** True while this player still has to accept the rules / guide (used by the welcome message). */
    boolean pending(Player p) {
        return this.gated(p);
    }

    private boolean gated(Player p) {
        return !this.active.isEmpty() && this.active.contains(p.getUniqueId()) && this.current(p) != null;
    }

    // ------------------------------------------------------------------ /kickall

    /** /kickall [confirm] [reason] - kicks everybody (ops too); when they come back they must accept the rules and guide again. */
    void handleKickAll(org.bukkit.command.CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.kickall")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            s.sendMessage(Announcer.color("&8» &#FF8330&lWarning! &7This kicks &fEVERYONE &7(ops too). Everybody must accept the rules and guide again when they join."));
            s.sendMessage(Announcer.color("&8» &7Type &f/kickall confirm [reason] &7to do it."));
            return;
        }
        if (!this.enabled()) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7The first-join steps are turned off (first-join.enabled), so nobody would see them."));
            return;
        }
        String reason = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
        this.plugin.data().set("first-join-version", this.plugin.data().getInt("first-join-version", 0) + 1);
        this.plugin.data().set("first-join-reset-at", System.currentTimeMillis());
        this.plugin.saveData();
        String msg = this.c().getString("first-join.kickall-message", "&#D9381E&lThe server is restarting the player check.\n&7Rejoin to accept the rules again.");
        final String kick = Announcer.color(msg.replace("{reason}", reason).replace("\\n", "\n"));
        int n = 0;
        for (Player o : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            o.kickPlayer(kick);
            n++;
        }
        s.sendMessage(Announcer.color("&8» &#FF9147&lSuccess! &7Kicked &f" + n + " &7player(s). Everyone must accept the rules and guide again."));
    }

    // ------------------------------------------------------------------ join

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!this.enabled()) {
            return;
        }
        boolean bypass = p.hasPermission("unstableffa.firstjoin.bypass");
        String key = "first-join." + p.getUniqueId();
        if (this.plugin.data().getString(key, null) == null) {
            // after /kickall everybody has to do the steps again (staff too, unless first-join.kickall-includes-staff is false)
            boolean outdated = this.plugin.data().getInt("first-join-seen." + p.getUniqueId(), 0) < this.plugin.data().getInt("first-join-version", 0);
            boolean forced = outdated && (!bypass || this.c().getBoolean("first-join.kickall-includes-staff", true));
            boolean brandNew = !p.hasPlayedBefore() && !bypass;
            if (forced || brandNew) {
                this.plugin.data().set(key, this.steps().get(0));
                this.plugin.saveData();
            }
        }
        if (this.current(p) != null) {
            this.active.add(p.getUniqueId());
            long delay = Math.max(1L, this.c().getLong("first-join.delay-ticks", 40L));
            this.notBefore.put(p.getUniqueId(), System.currentTimeMillis() + delay * 50L);
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (p.isOnline() && this.gated(p)) {
                    this.open(p, this.current(p));
                }
            }, delay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        this.active.remove(id);
        this.openedAt.remove(id);
        this.notBefore.remove(id);
        this.lastWarn.remove(id);
        this.switching.remove(id);
    }

    // ------------------------------------------------------------------ menus

    private void open(Player p, String step) {
        if (step == null) {
            return;
        }
        List<String> steps = this.steps();
        int n = steps.indexOf(step) + 1;
        Inventory inv = Bukkit.createInventory(new Holder(step), SIZE, Announcer.color(
                "&8Step " + n + "/" + steps.size() + " &7- " + (step.equals("rules") ? "&8Server rules" : "&8Server guide")));
        if (step.equals("rules")) {
            List<Map<?, ?>> list = this.c().getMapList("rules.list");
            for (int i = 0; i < list.size() && i < GRID.length; i++) {
                Map<?, ?> m = list.get(i);
                List<String> lore = this.wrap(this.str(m, "text", ""), 34, "&7");
                String pun = this.str(m, "punishment", "");
                if (!pun.isEmpty()) {
                    lore.add("");
                    lore.add("&#D9381EBreaking it: &f" + pun);
                }
                inv.setItem(GRID[i], this.item(Material.PAPER, "&#FF791F&l" + (i + 1) + ". &f&l" + this.str(m, "title", "Rule " + (i + 1)), lore));
            }
            if (this.c().getBoolean("first-join.decline-button", true)) {
                inv.setItem(DECLINE_SLOT, this.item(Material.RED_CONCRETE, "<gradient:#FF7518:#FFFFFF>&lI do not</gradient><gradient:#FFFFFF:#FF7518>&l accept</gradient>",
                        this.wrap("You can't play on this server without accepting the rules.", 34, "&7")));
            }
        } else {
            ConfigurationSection topics = this.c().getConfigurationSection("guide.topics");
            int i = 0;
            if (topics != null) {
                for (String key : topics.getKeys(false)) {
                    if (i >= GRID.length) {
                        break;
                    }
                    ConfigurationSection t = topics.getConfigurationSection(key);
                    if (t == null) {
                        continue;
                    }
                    Material mat = Material.matchMaterial(t.getString("icon", "PAPER"));
                    List<String> lore = new ArrayList<>();
                    for (String line : t.getStringList("lines")) {
                        lore.add(this.pretty(line));
                    }
                    inv.setItem(GRID[i++], this.item(mat == null ? Material.PAPER : mat, t.getString("name", key), lore));
                }
            }
        }
        inv.setItem(INFO_SLOT, this.item(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lSt</gradient><gradient:#FFFFFF:#FF7518>&lep</gradient> " + n + " of " + steps.size(),
                this.wrap(step.equals("rules") ? "Read all the rules, then accept them to continue."
                        : "Read the guide, then accept it to start playing.", 34, "&7")));
        this.openedAt.put(p.getUniqueId(), System.currentTimeMillis());
        this.refreshButton(inv, p, step);
        this.switching.add(p.getUniqueId());
        p.openInventory(inv);
        final UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(this.plugin, () -> this.switching.remove(id));
    }

    private long waitLeft(Player p) {
        long need = Math.max(0L, this.c().getLong("first-join.read-seconds", 8L)) * 1000L;
        Long at = this.openedAt.get(p.getUniqueId());
        long left = at == null ? 0L : need - (System.currentTimeMillis() - at);
        return Math.max(0L, (left + 999L) / 1000L);
    }

    /** The accept button counts down until the player has had time to read. */
    private void refreshButton(Inventory inv, Player p, String step) {
        long left = this.waitLeft(p);
        String what = step.equals("rules") ? "rules" : "guide";
        if (left > 0L) {
            inv.setItem(ACCEPT_SLOT, this.item(Material.GRAY_CONCRETE, "&7Read the " + what + " first &8(" + left + "s)",
                    this.wrap("The button turns green when you have had time to read.", 34, "&7")));
        } else {
            inv.setItem(ACCEPT_SLOT, this.item(Material.LIME_CONCRETE, "<gradient:#FF7518:#FFFFFF>&lI have read a</gradient><gradient:#FFFFFF:#FF7518>&lnd accept the</gradient> " + what,
                    this.wrap("Click here to continue.", 34, "&7")));
        }
    }

    private void tick() {
        if (this.active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(this.active)) {
            Player p = Bukkit.getPlayer(id);
            String step = p == null ? null : this.current(p);
            if (step == null) {
                this.active.remove(id);
                continue;
            }
            Long nb = this.notBefore.get(id);
            if (nb != null && now < nb) {
                continue;
            }
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Holder && ((Holder) top.getHolder()).step.equals(step)) {
                this.refreshButton(top, p, step);
                continue;
            }
            Long at = this.openedAt.get(id);
            if (at == null || now - at > 1500L) { // not right after a switch
                this.open(p, step);
            }
        }
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        final Player p = (Player) e.getWhoClicked();
        boolean ours = e.getInventory().getHolder() instanceof Holder;
        if (!ours) {
            if (this.gated(p)) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) {
            return;
        }
        final String step = ((Holder) e.getInventory().getHolder()).step;
        if (!step.equals(this.current(p))) {
            return;
        }
        int slot = e.getRawSlot();
        if (slot == DECLINE_SLOT && step.equals("rules") && this.c().getBoolean("first-join.decline-button", true)) {
            Bukkit.getScheduler().runTask(this.plugin, () -> p.kickPlayer(Announcer.color(this.c().getString("first-join.kick-message",
                    "&#D9381E&lYou must accept the rules to play here.\n&7Rejoin when you are ready."))));
            return;
        }
        if (slot != ACCEPT_SLOT) {
            return;
        }
        long left = this.waitLeft(p);
        if (left > 0L) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            p.sendMessage(Announcer.color("&8» &#D9381E&lWait! &7Read it first. You can accept in &f" + left + "s&7."));
            return;
        }
        this.accept(p, step);
    }

    private void accept(final Player p, String step) {
        if (step.equals("rules")) { // so the normal /rules accept does not ask again
            this.plugin.data().set("rules-accepted." + p.getUniqueId(), this.c().getInt("rules.version", 1));
        }
        List<String> steps = this.steps();
        int next = steps.indexOf(step) + 1;
        final String key = "first-join." + p.getUniqueId();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0F, 1.2F);
        if (next < steps.size()) {
            final String ns = steps.get(next);
            this.plugin.data().set(key, ns);
            this.plugin.saveData();
            Bukkit.getScheduler().runTask(this.plugin, () -> this.open(p, ns));
            return;
        }
        this.plugin.data().set(key, null); // done for good
        this.plugin.data().set("first-join-seen." + p.getUniqueId(), this.plugin.data().getInt("first-join-version", 0));
        this.active.remove(p.getUniqueId());
        this.plugin.saveData();
        this.switching.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            p.closeInventory();
            this.switching.remove(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F);
            String msg = this.c().getString("first-join.done-message", "&8» &#FF9147&lThanks! &7You are all set. Have fun!");
            if (msg != null && !msg.isEmpty()) {
                p.sendMessage(Announcer.color(msg));
            }
            this.plugin.firstJoinDone(p); // now the welcome message
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder
                || (e.getWhoClicked() instanceof Player && this.gated((Player) e.getWhoClicked()))) {
            e.setCancelled(true);
        }
    }

    /** Closing the menu (Esc, E, anything) opens it again, as long as the steps are not done. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder) || !(e.getPlayer() instanceof Player)) {
            return;
        }
        final Player p = (Player) e.getPlayer();
        if (this.switching.contains(p.getUniqueId()) || !this.gated(p)) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            String step = this.current(p);
            if (p.isOnline() && step != null) {
                Long at = this.openedAt.get(p.getUniqueId());
                if (at != null) { // keep the reading timer running, closing does not restart it
                    this.openAgain(p, step, at);
                } else {
                    this.open(p, step);
                }
            }
        });
    }

    private void openAgain(Player p, String step, long keepTime) {
        this.open(p, step);
        this.openedAt.put(p.getUniqueId(), keepTime);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Holder) {
            this.refreshButton(top, p, step);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player && !(e.getInventory().getHolder() instanceof Holder) && this.gated((Player) e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ everything else is locked

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        Long last = this.lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 4000L) {
            return;
        }
        this.lastWarn.put(p.getUniqueId(), now);
        String m = this.c().getString("first-join.blocked-message", "&8» &#D9381E&lWait! &7Finish the first-join steps before you do that.");
        if (m != null && !m.isEmpty()) {
            p.sendMessage(Announcer.color(m));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!this.gated(p) || e.getTo() == null) {
            return;
        }
        Location f = e.getFrom();
        Location t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back); // can look around, can't go anywhere
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!this.gated(p)) {
            return;
        }
        String first = e.getMessage().substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        if (colon != -1) {
            first = first.substring(colon + 1);
        }
        for (String ok : this.c().getStringList("first-join.allowed-commands")) {
            if (ok.equalsIgnoreCase(first)) {
                return;
            }
        }
        e.setCancelled(true);
        this.warn(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (this.gated(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && this.gated((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && this.gated((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && this.gated((Player) e.getDamager())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player && this.gated((Player) e.getTarget())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && this.gated((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Announcer.color(name));
        List<String> l = new ArrayList<>();
        for (String x : lore) {
            l.add(Announcer.color(x));
        }
        m.setLore(l);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private String str(Map<?, ?> m, String key, String def) {
        Object o = m.get(key);
        return o == null ? def : String.valueOf(o);
    }

    private String pretty(String line) {
        int bar = line.indexOf(" | ");
        if (bar == -1) {
            return "&7" + line;
        }
        return "&#FF791F" + line.substring(0, bar).trim() + " &8» &7" + line.substring(bar + 3).trim();
    }

    private List<String> wrap(String text, int width, String color) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(color + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(color + line);
        }
        return out;
    }
}
