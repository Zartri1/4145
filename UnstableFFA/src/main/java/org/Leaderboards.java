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
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * /leaderboard (also /top, /lb)  -  the best players of the server.
 *
 *   /leaderboard            a menu with one icon per list (kills, deaths, playtime, killstreak, ...)
 *   /leaderboard <list>     opens that list directly
 *   Click an icon: a second menu with the best players of that list (heads, rank, value, online or not).
 *   Click a head: that player's stats. Back goes to the menu again.
 *
 * Every icon can be placed in any slot, renamed, given another item or turned off in
 * config.yml -> top -> categories.  You can add your own lists with any Minecraft statistic.
 *
 * Playtime comes from the plugin's own data and is always up to date. Kills and deaths and the other
 * statistics are refreshed every few minutes, a few players per tick, so nothing lags.
 *
 * Permission:  unstableffa.leaderboard (everyone)      Settings: config.yml -> top
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class Leaderboards implements Listener {

    private static final class Holder implements InventoryHolder {
        final String cat; // null = the menu with all the lists

        Holder(String cat) {
            this.cat = cat;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class Cat {
        String key;
        String name;      // as written in the config (with colors)
        String plain;     // without colors, for titles
        String desc;
        Material icon;
        int slot;
        Statistic stat;   // null for playtime and killstreak
        String kind;      // playtime | streak | stat
        String fmt;       // number | time | distance | ticks
    }

    /** key, name, icon, slot, kind or statistic, format, shown when not mentioned in the config */
    private static final Object[][] BUILT_IN = {
        {"kills", "<gradient:#FF7518:#FFFFFF>&lKil</gradient><gradient:#FFFFFF:#FF7518>&lls</gradient>", "DIAMOND_SWORD", 10, "PLAYER_KILLS", "number", true, "The players with the most kills."},
        {"deaths", "<gradient:#FF7518:#FFFFFF>&lDea</gradient><gradient:#FFFFFF:#FF7518>&lths</gradient>", "SKELETON_SKULL", 12, "DEATHS", "number", true, "The players who died the most."},
        {"killstreak", "<gradient:#FF7518:#FFFFFF>&lKills</gradient><gradient:#FFFFFF:#FF7518>&ltreak</gradient>", "BLAZE_POWDER", 14, "@streak", "number", true, "The best killstreaks ever reached."},
        {"playtime", "<gradient:#FF7518:#FFFFFF>&lPlay</gradient><gradient:#FFFFFF:#FF7518>&ltime</gradient>", "CLOCK", 16, "@playtime", "time", true, "The players with the most time on the server."},
        {"mobs", "<gradient:#FF7518:#FFFFFF>&lMob k</gradient><gradient:#FFFFFF:#FF7518>&lills</gradient>", "ZOMBIE_HEAD", 20, "MOB_KILLS", "number", false, "The players with the most mob kills."},
        {"distance", "<gradient:#FF7518:#FFFFFF>&lDist</gradient><gradient:#FFFFFF:#FF7518>&lance</gradient>", "LEATHER_BOOTS", 22, "WALK_ONE_CM", "distance", false, "The players who walked the furthest."},
        {"fishing", "<gradient:#FF7518:#FFFFFF>&lFish</gradient><gradient:#FFFFFF:#FF7518>&ling</gradient>", "FISHING_ROD", 24, "FISH_CAUGHT", "number", false, "The players who caught the most fish."},
    };

    private static final int LIST_SIZE = 54;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int YOU_SLOT = 53;

    private final zenithEssentials plugin;

    /** category key -> (player -> value). null until the first refresh has finished. */
    private volatile Map<String, Map<UUID, Long>> cache;
    private boolean refreshing;

    Leaderboards(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    /** Starts the refresh timer. Called once from onEnable. */
    void start() {
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("top.enabled", true)) {
            return;
        }
        long every = Math.max(1L, c.getLong("top.refresh-minutes", 10L)) * 1200L;
        Bukkit.getScheduler().runTaskTimer(this.plugin, this::beginRefresh, 200L, every);
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Only players can use /leaderboard.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.leaderboard")) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("top.enabled", true)) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Leaderboards are turned off."));
            return;
        }
        List<Cat> en = this.enabled();
        if (en.isEmpty()) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7No leaderboards are turned on."));
            return;
        }
        if (args.length == 0) {
            this.openMenu(p);
            return;
        }
        String want = args[0].toLowerCase(Locale.ROOT);
        for (Cat c : en) {
            if (c.key.equals(want) || c.plain.toLowerCase(Locale.ROOT).startsWith(want)) {
                this.openList(p, c);
                return;
            }
        }
        StringBuilder keys = new StringBuilder();
        for (Cat c : en) {
            keys.append(keys.length() == 0 ? "" : ", ").append(c.key);
        }
        p.sendMessage(Announcer.color("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/leaderboard [" + keys + "]"));
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String last = args[0].toLowerCase(Locale.ROOT);
            for (Cat c : this.enabled()) {
                if (c.key.startsWith(last)) {
                    out.add(c.key);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ menu with all the lists

    private void openMenu(Player p) {
        FileConfiguration cfg = this.plugin.getConfig();
        int rows = Math.max(1, Math.min(6, cfg.getInt("top.menu-rows", 3)));
        int size = rows * 9;
        Inventory inv = Bukkit.createInventory(new Holder(null), size,
                Announcer.color(cfg.getString("top.menu-title", "&8Leaderboards")));
        for (Cat c : this.enabled()) {
            if (c.slot < 0 || c.slot >= size) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            if (c.desc != null && !c.desc.isEmpty()) {
                lore.add("&7" + c.desc);
                lore.add("");
            }
            lore.add("&#FF8330Click to open");
            inv.setItem(c.slot, this.item(c.icon, c.name, lore));
        }
        inv.setItem(size - 5, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));
        this.plugin.tidy(inv, false); // same calm style as every other menu
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ one list

    private void openList(Player p, Cat cat) {
        FileConfiguration cfg = this.plugin.getConfig();
        Inventory inv = Bukkit.createInventory(new Holder(cat.key), LIST_SIZE,
                Announcer.color("&8Leaderboard &7- &f" + cat.plain));
        inv.setItem(BACK_SLOT, this.item(Material.ARROW, "<gradient:#FF7518:#FFFFFF>&lBa</gradient><gradient:#FFFFFF:#FF7518>&lck</gradient>", new ArrayList<>()));
        inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));

        List<Map.Entry<UUID, Long>> rank = this.ranking(cat);
        if (rank == null) {
            List<String> lore = new ArrayList<>();
            lore.add("&7The list is being counted right now.");
            lore.add("&7Open it again in a few seconds.");
            inv.setItem(22, this.item(Material.PAPER, "&#FF8330&lLoading...", lore));
            this.beginRefresh();
        } else if (rank.isEmpty()) {
            inv.setItem(22, this.item(Material.PAPER, "&7Nobody is on this list yet", new ArrayList<>()));
        } else {
            int max = Math.max(1, Math.min(45, cfg.getInt("top.size", 36)));
            for (int i = 0; i < max && i < rank.size(); i++) {
                Map.Entry<UUID, Long> en2 = rank.get(i);
                inv.setItem(i, this.head(en2.getKey(), i + 1, cat, en2.getValue(), true));
            }
            int mine = -1;
            for (int i = 0; i < rank.size(); i++) {
                if (rank.get(i).getKey().equals(p.getUniqueId())) {
                    mine = i;
                    break;
                }
            }
            List<String> lore = new ArrayList<>();
            if (mine == -1) {
                lore.add("&7You are not on this list yet.");
            } else {
                lore.add("&7Place: &f#" + (mine + 1) + " &7of &f" + rank.size());
                lore.add("&7" + cat.plain + ": &f" + this.format(cat, rank.get(mine).getValue()));
            }
            ItemStack you = this.head(p.getUniqueId(), 0, cat, 0L, false);
            ItemMeta m = you.getItemMeta();
            m.setDisplayName(Announcer.color("<gradient:#FF7518:#FFFFFF>&lYour </gradient><gradient:#FFFFFF:#FF7518>&lrank</gradient>"));
            List<String> l2 = new ArrayList<>();
            for (String x : lore) {
                l2.add(Announcer.color(x));
            }
            m.setLore(l2);
            you.setItemMeta(m);
            inv.setItem(YOU_SLOT, you);
        }
        this.plugin.tidy(inv, false);
        p.openInventory(inv);
    }

    private ItemStack head(UUID id, int rank, Cat cat, long value, boolean clickable) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        m.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        String color = rank == 1 ? "&#FF791F" : rank == 2 ? "&#C0C0C0" : rank == 3 ? "&#CD7032" : "&7";
        String name = this.plugin.nameFor(id, "Unknown");
        m.setDisplayName(Announcer.color(color + "&l#" + rank + " &f" + name));
        List<String> lore = new ArrayList<>();
        lore.add(Announcer.color("&7" + cat.plain + ": &f" + this.format(cat, value)));
        lore.add(Announcer.color("&7Status: " + (Bukkit.getPlayer(id) != null ? "&#FF9147ONLINE" : "&8OFFLINE")));
        if (clickable && this.plugin.getConfig().getBoolean("top.click-head-opens-stats", true)) {
            lore.add("");
            lore.add(Announcer.color("&#FF8330Click for details"));
        }
        m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Announcer.color(name));
        List<String> l = new ArrayList<>();
        for (String x : lore) {
            l.add(Announcer.color(x));
        }
        m.setLore(l);
        it.setItemMeta(m);
        return it;
    }

    private String format(Cat c, long v) {
        switch (c.fmt) {
            case "time":
                return this.plugin.duration(v * 1000L);
            case "ticks":
                return this.plugin.duration(v * 50L);
            case "distance":
                return String.format(Locale.US, "%.1f km", v / 100000.0);
            default:
                return String.format(Locale.US, "%,d", v);
        }
    }

    // ------------------------------------------------------------------ clicks

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
        Holder h = (Holder) e.getInventory().getHolder();
        int slot = e.getRawSlot();
        if (h.cat == null) { // the menu
            if (slot == e.getInventory().getSize() - 5) {
                p.closeInventory();
                return;
            }
            for (Cat c : this.enabled()) {
                if (c.slot == slot) {
                    this.openList(p, c);
                    return;
                }
            }
            return;
        }
        if (slot == CLOSE_SLOT) {
            p.closeInventory();
        } else if (slot == BACK_SLOT) {
            this.openMenu(p);
        } else if (slot >= 0 && slot < 45 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.PLAYER_HEAD
                && this.plugin.getConfig().getBoolean("top.click-head-opens-stats", true)) {
            ItemMeta m = e.getCurrentItem().getItemMeta();
            if (m instanceof SkullMeta && ((SkullMeta) m).getOwningPlayer() != null) {
                String name = this.plugin.nameFor(((SkullMeta) m).getOwningPlayer().getUniqueId(), null);
                if (name != null) {
                    p.closeInventory();
                    p.performCommand("stats " + name + " gui");
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ the lists from config.yml

    /** The lists that are turned on, with their slot, icon and name from config.yml -> top.categories. */
    private List<Cat> enabled() {
        FileConfiguration cfg = this.plugin.getConfig();
        List<Cat> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ConfigurationSection cs = cfg.getConfigurationSection("top.categories");
        List<String> keys = new ArrayList<>();
        for (Object[] b : BUILT_IN) {
            keys.add((String) b[0]);
        }
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                if (!keys.contains(k)) {
                    keys.add(k);
                }
            }
        }
        for (String key : keys) {
            if (!seen.add(key)) {
                continue;
            }
            Object[] def = null;
            for (Object[] b : BUILT_IN) {
                if (b[0].equals(key)) {
                    def = b;
                }
            }
            Object raw = cs == null ? null : cs.get(key);
            ConfigurationSection sec = raw instanceof ConfigurationSection ? (ConfigurationSection) raw : null;
            boolean on;
            if (raw instanceof Boolean) { // the old format:  kills: true
                on = (Boolean) raw;
            } else if (sec != null) {
                on = sec.getBoolean("enabled", true);
            } else {
                on = def != null && (Boolean) def[6];
            }
            if (!on) {
                continue;
            }
            Cat c = new Cat();
            c.key = key;
            c.name = sec != null ? sec.getString("name", def != null ? (String) def[1] : "&#FF791F&l" + key) : (def != null ? (String) def[1] : "&#FF791F&l" + key);
            c.plain = org.bukkit.ChatColor.stripColor(Announcer.color(c.name));
            c.desc = sec != null ? sec.getString("description", def != null ? (String) def[7] : "") : (def != null ? (String) def[7] : "");
            String icon = sec != null ? sec.getString("icon", def != null ? (String) def[2] : "PAPER") : (def != null ? (String) def[2] : "PAPER");
            Material m = Material.matchMaterial(icon);
            c.icon = m == null ? Material.PAPER : m;
            c.slot = sec != null ? sec.getInt("slot", def != null ? (Integer) def[3] : 0) : (def != null ? (Integer) def[3] : 0);
            String what = sec != null ? sec.getString("statistic", def != null ? (String) def[4] : "") : (def != null ? (String) def[4] : "");
            c.fmt = sec != null ? sec.getString("format", def != null ? (String) def[5] : "number").toLowerCase(Locale.ROOT)
                    : (def != null ? (String) def[5] : "number");
            if (what.equals("@playtime")) {
                c.kind = "playtime";
            } else if (what.equals("@streak")) {
                c.kind = "streak";
            } else {
                try {
                    c.stat = Statistic.valueOf(what.toUpperCase(Locale.ROOT));
                    c.kind = "stat";
                } catch (IllegalArgumentException ex) {
                    this.plugin.getLogger().warning("top.categories." + key + ": '" + what + "' is not a Minecraft statistic.");
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ data

    /** Every player the plugin knows, as UUIDs. */
    private List<UUID> knownIds() {
        List<UUID> ids = new ArrayList<>();
        ConfigurationSection us = this.plugin.data().getConfigurationSection("users");
        if (us != null) {
            for (String k : us.getKeys(false)) {
                String u = us.getString(k + ".uuid");
                if (u == null) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(u));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ PlaceholderAPI

    private volatile Map<String, List<Map.Entry<UUID, Long>>> papiSnap = new HashMap<>();
    private volatile Map<String, Cat> papiCats = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask papiTask;

    /** Starts the snapshot refresh used by %zenith_top_..% and %zenith_rank_..% (other plugins read it from any thread). */
    void startPapi() {
        if (this.papiTask != null) {
            return;
        }
        this.papiTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::refreshPapi, 40L, 200L);
    }

    private void refreshPapi() {
        Map<String, List<Map.Entry<UUID, Long>>> fresh = new HashMap<>();
        Map<String, Cat> cats = new HashMap<>();
        for (Cat c : this.enabled()) {
            List<Map.Entry<UUID, Long>> r = this.ranking(c);
            if (r == null) {
                this.beginRefresh();
                continue;
            }
            fresh.put(c.key, r);
            cats.put(c.key, c);
        }
        this.papiSnap = fresh;
        this.papiCats = cats;
    }

    /**
     * rank_<category>                 position of the player, "-" if not ranked
     * top_<category>_<n>_name         name at position n
     * top_<category>_<n>_value        value at position n, formatted like the menu
     * Categories are the keys from top.categories (kills, deaths, killstreak, playtime, ...).
     * Returns null for an unknown placeholder.
     */
    String papi(OfflinePlayer p, String key) {
        String k = key.toLowerCase(Locale.ROOT);
        String catKey;
        String what;
        int pos = 0;
        if (k.startsWith("rank_")) {
            catKey = k.substring(5);
            what = "rank";
        } else if (k.startsWith("top_")) {
            int last = k.lastIndexOf('_');
            what = k.substring(last + 1);
            String rest = k.substring(4, Math.max(4, last));
            int us = rest.lastIndexOf('_');
            if (us < 0 || (!what.equals("name") && !what.equals("value"))) {
                return null;
            }
            try {
                pos = Integer.parseInt(rest.substring(us + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            catKey = rest.substring(0, us);
        } else {
            return null;
        }
        Map<String, Cat> cats = this.papiCats;
        Cat cat = cats.get(catKey);
        if (cat == null) {
            return cats.isEmpty() ? "..." : null;
        }
        List<Map.Entry<UUID, Long>> rank = this.papiSnap.get(catKey);
        if (rank == null) {
            return "...";
        }
        if (what.equals("rank")) {
            if (p == null) {
                return "-";
            }
            for (int i = 0; i < rank.size(); i++) {
                if (rank.get(i).getKey().equals(p.getUniqueId())) {
                    return String.valueOf(i + 1);
                }
            }
            return "-";
        }
        if (pos < 1 || pos > rank.size()) {
            return "-";
        }
        Map.Entry<UUID, Long> en = rank.get(pos - 1);
        return what.equals("name") ? this.plugin.nameFor(en.getKey(), "?") : this.format(cat, en.getValue());
    }

    /** The sorted list for one category, best first. null = not counted yet. */
    private List<Map.Entry<UUID, Long>> ranking(Cat c) {
        Map<UUID, Long> values = new HashMap<>();
        if (c.kind.equals("streak")) {
            values.putAll(this.plugin.streaks());
        } else if (c.kind.equals("playtime")) {
            ConfigurationSection us = this.plugin.data().getConfigurationSection("users");
            if (us != null) {
                for (String k : us.getKeys(false)) {
                    String u = us.getString(k + ".uuid");
                    if (u == null) {
                        continue;
                    }
                    try {
                        UUID id = UUID.fromString(u);
                        long sec = Bukkit.getPlayer(id) != null ? this.plugin.playtimeOf(id) / 1000L : us.getLong(k + ".playtime-seconds", 0L);
                        values.put(id, sec);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } else {
            Map<String, Map<UUID, Long>> cc = this.cache;
            if (cc == null || cc.get(c.key) == null) {
                return null;
            }
            values.putAll(cc.get(c.key));
            for (Player online : Bukkit.getOnlinePlayers()) { // online players are always live
                try {
                    values.put(online.getUniqueId(), (long) online.getStatistic(c.stat));
                } catch (Exception ignored) {
                }
            }
        }
        Set<String> hide = new HashSet<>();
        for (String n : this.plugin.getConfig().getStringList("top.exclude")) {
            hide.add(n.toLowerCase(Locale.ROOT));
        }
        List<Map.Entry<UUID, Long>> list = new ArrayList<>();
        for (Map.Entry<UUID, Long> en : values.entrySet()) {
            if (en.getValue() <= 0L) {
                continue;
            }
            if (!hide.isEmpty() && hide.contains(this.plugin.nameFor(en.getKey(), "").toLowerCase(Locale.ROOT))) {
                continue;
            }
            list.add(en);
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    /** Reads the statistics of every known player, a few per tick, then swaps the new lists in. */
    private void beginRefresh() {
        if (this.refreshing) {
            return;
        }
        final List<Cat> statCats = new ArrayList<>();
        for (Cat c : this.enabled()) {
            if (c.kind.equals("stat")) {
                statCats.add(c);
            }
        }
        if (statCats.isEmpty()) {
            this.cache = new HashMap<>();
            return;
        }
        final List<UUID> ids = this.knownIds();
        final Map<String, Map<UUID, Long>> fresh = new HashMap<>();
        for (Cat c : statCats) {
            fresh.put(c.key, new HashMap<>());
        }
        final int batch = Math.max(1, this.plugin.getConfig().getInt("top.batch-per-tick", 20));
        this.refreshing = true;
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(ids.size(), this.i + batch);
                for (; this.i < end; this.i++) {
                    UUID id = ids.get(this.i);
                    OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                    for (Cat c : statCats) {
                        long v = 0L;
                        try {
                            v = op.getStatistic(c.stat);
                        } catch (Exception ignored) {
                        }
                        if (v > 0L) {
                            fresh.get(c.key).put(id, v);
                        }
                    }
                }
                if (this.i >= ids.size()) {
                    Leaderboards.this.cache = fresh;
                    Leaderboards.this.refreshing = false;
                    this.cancel();
                }
            }
        }.runTaskTimer(this.plugin, 1L, 1L);
    }
}
