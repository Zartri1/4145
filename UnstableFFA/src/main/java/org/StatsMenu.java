package org;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

/**
 * /stats [player]  -  stats in chat:
 *
 *   ------------ DY76's Stats ------------
 *   - Kills: 6
 *   - Deaths: 26
 *   - K/D Ratio: 0.23
 *   ...
 *
 * Kills, deaths, cobwebs, pearls, golden apples, chorus and XP bottles come from Minecraft's own statistics.
 * Killstreak, best killstreak, swings and hits are counted by this class and saved in
 * plugins/UnstableFFA/stats.yml.
 *
 * Shown in chat or in a menu: config.yml -> stats-chat.display (chat / gui).
 * Players can also pick: /stats [player] chat  or  /stats [player] gui
 * Staff with unstableffa.history get a History button (a clickable [History] in chat, a book in the menu).
 *
 * Permission:  unstableffa.stats (everyone), unstableffa.stats.others (everyone)
 * Settings:    config.yml -> stats-chat (display, header, every line, footer)
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class StatsMenu implements Listener {

    private static final class Counters {
        int streak;
        int best;
        long swings;
        long hits;
    }

    private static final class Holder implements InventoryHolder {
        final String target;

        Holder(String target) {
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int HISTORY_SLOT = 40;
    private static final int CLOSE_SLOT = 44;

    private final zenithEssentials plugin;
    private final File file;
    private final Map<UUID, Counters> counters = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean dirty;

    StatsMenu(zenithEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        this.load();
        // Save every 5 minutes, only when something changed.
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.stats")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("stats-chat.enabled", true)) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Stats are turned off."));
            return;
        }
        // optional last word: chat or gui
        String mode = this.plugin.getConfig().getString("stats-chat.display", "chat").toLowerCase(Locale.ROOT);
        if (args.length > 0 && (args[args.length - 1].equalsIgnoreCase("gui") || args[args.length - 1].equalsIgnoreCase("chat")
                || args[args.length - 1].equalsIgnoreCase("menu"))) {
            mode = args[args.length - 1].equalsIgnoreCase("chat") ? "chat" : "gui";
            args = java.util.Arrays.copyOf(args, args.length - 1);
        }
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(s instanceof Player)) {
                s.sendMessage("Use /stats <player>.");
                return;
            }
            target = (Player) s;
        } else {
            if (!s.getName().equalsIgnoreCase(args[0]) && !s.hasPermission("unstableffa.stats.others")) {
                s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You can only look at your own stats."));
                return;
            }
            target = this.plugin.offline(args[0]);
            boolean known = target != null && (target.isOnline() || target.hasPlayedBefore());
            // Vanished staff look like they were never here.
            if (known && target.isOnline() && this.plugin.vanished(target.getUniqueId()) && !s.hasPermission("unstableffa.vanish")) {
                known = false;
            }
            if (!known) {
                s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Player '&f" + args[0] + "&7' not found."));
                return;
            }
        }
        if ((mode.equals("gui") || mode.equals("menu")) && s instanceof Player) {
            this.openMenu((Player) s, target);
        } else {
            this.show(s, target);
        }
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            for (String m : new String[]{"chat", "gui"}) {
                if (m.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(m);
                }
            }
            return out;
        }
        if (args.length == 1 && s.hasPermission("unstableffa.stats.others")) {
            String last = args[0].toLowerCase(Locale.ROOT);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(last)
                        && (!this.plugin.vanished(o.getUniqueId()) || s.hasPermission("unstableffa.vanish"))) {
                    out.add(o.getName());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ output

    private String nameOf(OfflinePlayer t) {
        return t.getName() == null ? this.plugin.nameFor(t.getUniqueId(), "Unknown") : t.getName();
    }

    private List<String> lines() {
        List<String> lines = this.plugin.getConfig().getStringList("stats-chat.lines");
        return lines.isEmpty() ? defaultLines() : lines;
    }

    private void show(CommandSender viewer, OfflinePlayer t) {
        Map<String, String> ph = this.placeholders(t);
        String header = this.plugin.getConfig().getString("stats-chat.header", "&#FF9147------------ {player}'s Stats ------------");
        String footer = this.plugin.getConfig().getString("stats-chat.footer", "");
        viewer.sendMessage(this.fill(header, ph));
        for (String line : this.lines()) {
            viewer.sendMessage(this.fill(line, ph));
        }
        if (footer != null && !footer.isEmpty()) {
            viewer.sendMessage(this.fill(footer, ph));
        }
        if (viewer instanceof Player && viewer.hasPermission("unstableffa.history")) {
            TextComponent btn = new TextComponent(TextComponent.fromLegacyText(Announcer.color("&#D9381E&l[History]")));
            btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/history " + ph.get("player")));
            btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Announcer.color("&7Open " + ph.get("player") + "'s punishment history"))));
            TextComponent row = new TextComponent(TextComponent.fromLegacyText(Announcer.color("&7Staff: ")));
            row.addExtra(btn);
            ((Player) viewer).spigot().sendMessage(row);
        }
    }

    // ------------------------------------------------------------------ menu

    private void openMenu(Player viewer, OfflinePlayer t) {
        Map<String, String> ph = this.placeholders(t);
        String name = ph.get("player");
        Inventory inv = Bukkit.createInventory(new Holder(name), 45, Announcer.color("&8Stats » " + name));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta hm = head.getItemMeta();
        if (hm instanceof SkullMeta) {
            ((SkullMeta) hm).setOwningPlayer(t);
        }
        if (hm != null) {
            hm.setDisplayName(Announcer.color("&#FF9147&l" + name + "'s Stats"));
            List<String> hl = new ArrayList<>();
            hl.add(Announcer.color(t.isOnline() ? "&#FF9147Online now" : "&7Offline"));
            hm.setLore(hl);
            head.setItemMeta(hm);
        }
        inv.setItem(4, head);

        // One paper per line from config: "- Kills: {kills}" becomes "Kills" with the value underneath.
        List<String> lines = this.lines();
        for (int i = 0; i < lines.size() && i < GRID.length; i++) {
            String raw = lines.get(i);
            int colon = raw.indexOf(':');
            String label = ChatColor.stripColor(Announcer.color(colon == -1 ? raw : raw.substring(0, colon))).trim();
            if (label.startsWith("-")) {
                label = label.substring(1).trim();
            }
            String value = colon == -1 ? "" : this.fill(raw.substring(colon + 1).trim(), ph);
            List<String> lore = new ArrayList<>();
            if (!value.isEmpty()) {
                lore.add(Announcer.color("&#FF9147") + value);
            }
            inv.setItem(GRID[i], this.item(Material.PAPER, "&f" + this.fill(label, ph), lore));
        }
        if (viewer.hasPermission("unstableffa.history")) {
            List<String> l = new ArrayList<>();
            l.add(Announcer.color("&7Bans, mutes, kicks and warnings."));
            l.add("");
            l.add(Announcer.color("&#FF8330Click to open"));
            inv.setItem(HISTORY_SLOT, this.item(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lHist</gradient><gradient:#FFFFFF:#FF7518>&lory</gradient>", l));
        }
        inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));
        this.plugin.tidy(inv, false);
        viewer.openInventory(inv);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Announcer.color(name));
            meta.setLore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        String target = ((Holder) e.getInventory().getHolder()).target;
        if (e.getRawSlot() == CLOSE_SLOT) {
            p.closeInventory();
        } else if (e.getRawSlot() == HISTORY_SLOT && p.hasPermission("unstableffa.history")) {
            p.closeInventory();
            p.performCommand("history " + target);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    private Map<String, String> placeholders(OfflinePlayer t) {
        String name = this.nameOf(t);
        Counters c = this.counters.getOrDefault(t.getUniqueId(), new Counters());
        long kills = this.stat(t, Statistic.PLAYER_KILLS);
        long deaths = this.stat(t, Statistic.DEATHS);

        Map<String, String> ph = new HashMap<>();
        ph.put("player", name);
        ph.put("kills", this.num(kills));
        ph.put("deaths", this.num(deaths));
        ph.put("kd", String.format(Locale.US, "%.2f", deaths == 0 ? (double) kills : kills / (double) deaths));
        ph.put("killstreak", this.num(c.streak));
        ph.put("best_killstreak", this.num(c.best));
        ph.put("cobwebs", this.num(this.used(t, Material.COBWEB)));
        ph.put("pearls", this.num(this.used(t, Material.ENDER_PEARL)));
        ph.put("golden_apples", this.num(this.used(t, Material.GOLDEN_APPLE)));
        ph.put("enchanted_golden_apples", this.num(this.used(t, Material.ENCHANTED_GOLDEN_APPLE)));
        ph.put("chorus", this.num(this.used(t, Material.CHORUS_FRUIT)));
        ph.put("xp_bottles", this.num(this.used(t, Material.EXPERIENCE_BOTTLE)));
        ph.put("swings", this.num(c.swings));
        ph.put("hits", this.num(c.hits));
        ph.put("accuracy", String.format(Locale.US, "%.2f%%", c.swings == 0 ? 0.0 : Math.min(100.0, c.hits * 100.0 / c.swings)));
        ph.put("mob_kills", this.num(this.stat(t, Statistic.MOB_KILLS)));
        ph.put("playtime", this.plugin.duration(this.plugin.playtimeOf(t.getUniqueId())));

        return ph;
    }

    private static List<String> defaultLines() {
        List<String> l = new ArrayList<>();
        l.add("&7- &fKills: &#FF9147{kills}");
        l.add("&7- &fDeaths: &#FF9147{deaths}");
        l.add("&7- &fK/D Ratio: &#FF9147{kd}");
        l.add("&7- &fKillstreak: &#FF9147{killstreak}");
        l.add("&7- &fBest Killstreak: &#FF9147{best_killstreak}");
        l.add("&7- &fCobwebs: &#FF9147{cobwebs}");
        l.add("&7- &fPearls: &#FF9147{pearls}");
        l.add("&7- &fGolden Apples: &#FF9147{golden_apples}");
        l.add("&7- &fEnchanted Golden Apples: &#FF9147{enchanted_golden_apples}");
        l.add("&7- &fChorus: &#FF9147{chorus}");
        l.add("&7- &fExperience Bottles: &#FF9147{xp_bottles}");
        l.add("&7- &fSwings: &#FF9147{swings}");
        l.add("&7- &fHits: &#FF9147{hits}");
        l.add("&7- &fAccuracy: &#FF9147{accuracy}");
        return l;
    }

    private String fill(String s, Map<String, String> ph) {
        String out = s == null ? "" : s;
        for (Map.Entry<String, String> en : ph.entrySet()) {
            out = out.replace("{" + en.getKey() + "}", en.getValue());
        }
        return Announcer.color(out);
    }

    private long stat(OfflinePlayer p, Statistic s) {
        try {
            return p.getStatistic(s);
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** How many times they used (placed, ate, threw) this item. */
    private long used(OfflinePlayer p, Material m) {
        try {
            return p.getStatistic(Statistic.USE_ITEM, m);
        } catch (Exception ex) {
            return 0L;
        }
    }

    private String num(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    // ------------------------------------------------------------------ counting

    private Counters of(UUID id) {
        return this.counters.computeIfAbsent(id, k -> new Counters());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            this.of(e.getPlayer().getUniqueId()).swings++;
            this.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && e.getEntity() instanceof LivingEntity) {
            this.of(e.getDamager().getUniqueId()).hits++;
            this.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        int victimStreak = this.of(victim.getUniqueId()).streak;
        this.of(victim.getUniqueId()).streak = 0;
        Player killer = victim.getKiller();
        int streak = 0;
        if (killer != null && !killer.equals(victim)) {
            Counters k = this.of(killer.getUniqueId());
            k.streak++;
            k.best = Math.max(k.best, k.streak);
            streak = k.streak;
        } else {
            killer = null;
        }
        this.dirty = true;
        this.plugin.killstreakHook(killer, victim, streak, victimStreak); // popup, milestone and "streak ended" messages
    }

    /**
     * Raw value for PlaceholderAPI (%zenith_<key>%). No thousands separators so other plugins can use it.
     * Returns null for an unknown key.
     */
    String papi(OfflinePlayer t, String key) {
        Counters c = this.counters.getOrDefault(t.getUniqueId(), new Counters());
        long kills = this.stat(t, Statistic.PLAYER_KILLS);
        long deaths = this.stat(t, Statistic.DEATHS);
        switch (key.toLowerCase(Locale.ROOT)) {
            case "killstreak":
            case "streak":
                return String.valueOf(c.streak);
            case "best_killstreak":
            case "best_streak":
                return String.valueOf(c.best);
            case "kills":
                return String.valueOf(kills);
            case "deaths":
                return String.valueOf(deaths);
            case "kd":
                return String.format(Locale.US, "%.2f", deaths == 0 ? (double) kills : kills / (double) deaths);
            case "mob_kills":
                return String.valueOf(this.stat(t, Statistic.MOB_KILLS));
            case "swings":
                return String.valueOf(c.swings);
            case "hits":
                return String.valueOf(c.hits);
            case "accuracy":
                return String.format(Locale.US, "%.1f", c.swings == 0 ? 0.0 : Math.min(100.0, c.hits * 100.0 / c.swings));
            case "playtime":
                return this.plugin.duration(this.plugin.playtimeOf(t.getUniqueId()));
            case "playtime_hours":
                return String.valueOf(this.plugin.playtimeOf(t.getUniqueId()) / 3_600_000L);
            default:
                return null;
        }
    }

    /** The best killstreak ever reached by every player (used by /leaderboard). */
    Map<UUID, Long> bestStreaks() {
        Map<UUID, Long> out = new HashMap<>();
        for (Map.Entry<UUID, Counters> en : this.counters.entrySet()) {
            if (en.getValue().best > 0) {
                out.put(en.getKey(), (long) en.getValue().best);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ file

    private void load() {
        if (!this.file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
        for (String key : y.getKeys(false)) {
            try {
                Counters c = new Counters();
                c.streak = y.getInt(key + ".streak");
                c.best = y.getInt(key + ".best");
                c.swings = y.getLong(key + ".swings");
                c.hits = y.getLong(key + ".hits");
                this.counters.put(UUID.fromString(key), c);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Called every 5 minutes and from the main class when the plugin stops. */
    void save() {
        if (!this.dirty) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Counters> en : this.counters.entrySet()) {
            String k = en.getKey().toString();
            Counters c = en.getValue();
            y.set(k + ".streak", c.streak);
            y.set(k + ".best", c.best);
            y.set(k + ".swings", c.swings);
            y.set(k + ".hits", c.hits);
        }
        try {
            y.save(this.file);
            this.dirty = false;
        } catch (Exception ex) {
            this.plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
        }
    }
}
