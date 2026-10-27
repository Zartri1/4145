package org;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Daily punishment limits  -  /punishlimits
 *
 * Every staff member can only ban / mute / kick / warn so many times per day, so a trainee cannot
 * mute the whole server. Limits belong to a GROUP; a staff member is in a group when they have the
 * permission  unstableffa.limit.<group>  (give it with LuckPerms). One player can also get
 * his own personal limit that beats his group.
 *
 *   -1 = unlimited.  Ops, console and unstableffa.limit.bypass are never limited.
 *   The counter resets at midnight. Only punishments that really went through are counted.
 *
 * Settings: staff.yml -> punish-limits      Counters: data.yml -> punishlimits
 * Menu:     /punishlimits   (permission unstableffa.punishlimits.admin)
 */
public final class PunishLimits implements Listener {

    static final String[] ACTIONS = {"ban", "mute", "kick", "warn"};
    private static final int[] ACTION_SLOTS = {10, 12, 14, 16};
    private static final int[] GROUP_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] STAFF_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        String kind;   // main, group, player
        String group;  // for kind=group
        UUID target;   // for kind=player

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private final zenithEssentials plugin;

    PunishLimits(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ config helpers

    private ConfigurationSection cfg() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("punish-limits");
        if (c == null) {
            c = this.plugin.getConfig().createSection("punish-limits");
        }
        return c;
    }

    boolean enabled() {
        return this.cfg().getBoolean("enabled", true);
    }

    private String today() {
        ZoneId zone;
        try {
            String z = this.cfg().getString("timezone", "");
            zone = z == null || z.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (Exception ex) {
            zone = ZoneId.systemDefault();
        }
        return LocalDate.now(zone).toString();
    }

    private List<String> groups() {
        ConfigurationSection g = this.cfg().getConfigurationSection("groups");
        return g == null ? new ArrayList<>() : new ArrayList<>(g.getKeys(false));
    }

    private int groupValue(String group, String action) {
        return this.cfg().getInt("groups." + group + "." + action, -1);
    }

    private void setGroupValue(String group, String action, int v) {
        this.cfg().set("groups." + group + "." + action, v);
        this.plugin.saveConfig();
    }

    private static String pretty(int v) {
        return v < 0 ? "Unlimited" : String.valueOf(v);
    }

    private static String plural(String action) {
        return action + "s";
    }

    // ------------------------------------------------------------------ limits and counters

    private boolean exempt(CommandSender s) {
        return !(s instanceof Player) || s.isOp() || s.hasPermission("unstableffa.limit.bypass");
    }

    /** Groups this online player is in. */
    private List<String> groupsOf(Player p) {
        List<String> out = new ArrayList<>();
        for (String g : this.groups()) {
            if (p.hasPermission("unstableffa.limit." + g.toLowerCase(Locale.ROOT))) {
                out.add(g);
            }
        }
        return out;
    }

    /** Personal override, or -2 when he has none. */
    private int override(UUID id, String action) {
        String path = "punishlimits.override." + id + "." + action;
        return this.plugin.data().contains(path) ? this.plugin.data().getInt(path) : -2;
    }

    /** Limit from groups (or the default). -1 = unlimited. */
    private int fromGroups(Player p, String action) {
        List<String> gs = this.groupsOf(p);
        if (gs.isEmpty()) {
            return this.cfg().getInt("default." + action, -1);
        }
        int best = 0;
        for (String g : gs) {
            int v = this.groupValue(g, action);
            if (v < 0) {
                return -1;
            }
            best = Math.max(best, v);
        }
        return best;
    }

    /** What this player may do today. -1 = unlimited. */
    int limitOf(Player p, String action) {
        int o = this.override(p.getUniqueId(), action);
        if (o != -2) {
            return o;
        }
        return this.fromGroups(p, action);
    }

    int used(UUID id, String action) {
        String base = "punishlimits.used." + id;
        if (!this.today().equals(this.plugin.data().getString(base + ".date", ""))) {
            return 0;
        }
        return this.plugin.data().getInt(base + "." + action, 0);
    }

    /** Called before a punishment. Returns false (and tells the staff member) when his limit is used up. */
    boolean allow(CommandSender s, String action) {
        if (action == null || !this.enabled() || this.exempt(s)) {
            return true;
        }
        Player p = (Player) s;
        int max = this.limitOf(p, action);
        if (max < 0) {
            return true;
        }
        int used = this.used(p.getUniqueId(), action);
        if (used < max) {
            return true;
        }
        String msg = max == 0
                ? this.cfg().getString("message-none", "&8» &#D9381E&lDenied! &7You are not allowed to use &f{action} &7at your rank.")
                : this.cfg().getString("message-reached", "&8» &#D9381E&lDenied! &7You already used all &f{max} &7{actions} you may give today. It resets at midnight.");
        s.sendMessage(Announcer.color(msg.replace("{max}", String.valueOf(max)).replace("{action}", action)
                .replace("{actions}", plural(action)).replace("{used}", String.valueOf(used))));
        if (this.cfg().getBoolean("notify-admins", true) && max > 0) {
            String note = Announcer.color(this.cfg().getString("message-admin",
                    "&8[&#B15CFFLimits&8] &f{player} &7tried to go over the daily &f{action} &7limit (&f{used}&7/&f{max}&7).")
                    .replace("{player}", p.getName()).replace("{action}", action)
                    .replace("{used}", String.valueOf(used)).replace("{max}", String.valueOf(max)));
            for (Player a : Bukkit.getOnlinePlayers()) {
                if (a.hasPermission("unstableffa.punishlimits.admin") && !a.equals(p)) {
                    a.sendMessage(note);
                }
            }
        }
        return false;
    }

    /** Called after a punishment went through. */
    void count(CommandSender s, String action) {
        if (action == null || !(s instanceof Player)) {
            return;
        }
        UUID id = ((Player) s).getUniqueId();
        String base = "punishlimits.used." + id;
        String day = this.today();
        if (!day.equals(this.plugin.data().getString(base + ".date", ""))) {
            this.plugin.data().set(base, null);
            this.plugin.data().set(base + ".date", day);
        }
        this.plugin.data().set(base + "." + action, this.plugin.data().getInt(base + "." + action, 0) + 1);
        this.plugin.saveData();
        Player p = (Player) s;
        if (this.enabled() && !this.exempt(s) && this.cfg().getBoolean("show-remaining", true)) {
            int max = this.limitOf(p, action);
            if (max >= 0) {
                int left = Math.max(0, max - this.used(id, action));
                p.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7You have &f" + left + " &7" + plural(action) + " left today."));
            }
        }
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        boolean admin = s.hasPermission("unstableffa.punishlimits.admin");
        if (args.length == 0) {
            if (admin && s instanceof Player) {
                this.openMain((Player) s);
            } else {
                this.showOwn(s);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("me") || !admin) {
            this.showOwn(s);
            return;
        }
        switch (sub) {
            case "player":
                if (args.length < 2 || !(s instanceof Player)) {
                    s.sendMessage("§cUsage: /punishlimits player <name>");
                    return;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
                if (!t.hasPlayedBefore() && !t.isOnline()) {
                    s.sendMessage("§cThat player has never joined.");
                    return;
                }
                this.openPlayer((Player) s, t.getUniqueId());
                return;
            case "addgroup":
                if (args.length < 2) {
                    s.sendMessage("§cUsage: /punishlimits addgroup <name>");
                    return;
                }
                String name = args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (name.isEmpty() || this.groups().contains(name)) {
                    s.sendMessage("§cThat name is empty or already used.");
                    return;
                }
                for (String a : ACTIONS) {
                    this.cfg().set("groups." + name + "." + a, 5);
                }
                this.plugin.saveConfig();
                s.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7Group &f" + name + " &7created with 5 of each per day. Give people the permission &funstableffa.limit." + name + "&7."));
                return;
            case "delgroup":
                if (args.length < 2 || !this.groups().contains(args[1].toLowerCase(Locale.ROOT))) {
                    s.sendMessage("§cThat group does not exist.");
                    return;
                }
                this.cfg().set("groups." + args[1].toLowerCase(Locale.ROOT), null);
                this.plugin.saveConfig();
                s.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7Group removed."));
                return;
            case "reset":
                if (args.length < 2) {
                    s.sendMessage("§cUsage: /punishlimits reset <name>");
                    return;
                }
                OfflinePlayer r = Bukkit.getOfflinePlayer(args[1]);
                this.plugin.data().set("punishlimits.used." + r.getUniqueId(), null);
                this.plugin.saveData();
                s.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7Today's counter of &f" + args[1] + " &7was reset."));
                return;
            default:
                s.sendMessage("§cUsage: /punishlimits [me|player <name>|addgroup <name>|delgroup <name>|reset <name>]");
        }
    }

    private void showOwn(CommandSender s) {
        if (!(s instanceof Player)) {
            s.sendMessage("§7Players only.");
            return;
        }
        Player p = (Player) s;
        if (!this.enabled() || this.exempt(p)) {
            p.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7You have no daily limits."));
            return;
        }
        p.sendMessage(Announcer.color("&8[&#B15CFFLimits&8] &7Your daily limits:"));
        for (String a : ACTIONS) {
            int max = this.limitOf(p, a);
            p.sendMessage(Announcer.color("  &8» &f" + a + "&7: &f" + this.used(p.getUniqueId(), a) + "&7 / &f" + pretty(max)));
        }
    }

    List<String> groupNames() {
        return this.groups();
    }

    // ------------------------------------------------------------------ menus

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Announcer.color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String x : lore) {
                    l.add(Announcer.color(x));
                }
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private void fill(Inventory inv) {
        ItemStack f = this.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, f);
        }
    }

    private static Material iconFor(String action) {
        switch (action) {
            case "ban":
                return Material.BARRIER;
            case "mute":
                return Material.NOTE_BLOCK;
            case "kick":
                return Material.LEATHER_BOOTS;
            default:
                return Material.PAPER;
        }
    }

    private Holder holder(String kind, int size, String title) {
        Holder h = new Holder();
        h.kind = kind;
        h.inv = Bukkit.createInventory(h, size, Announcer.color(title));
        this.fill(h.inv);
        return h;
    }

    private void click(Player p) {
        try {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        } catch (Throwable ignored) {
            // sound missing in this version
        }
    }

    void openMain(Player p) {
        Holder h = this.holder("main", 54, "&8Daily punishment limits");
        Inventory inv = h.inv;
        boolean on = this.enabled();
        inv.setItem(4, this.item(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&#FF9147Limits are ON" : "&#D9381ELimits are OFF",
                "&7Click to turn " + (on ? "off" : "on") + "."));
        inv.setItem(45, this.item(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lHow it</gradient><gradient:#FFFFFF:#FF7518>&l works</gradient>",
                "&7A group is a rank like trainee or helper.",
                "&7Give staff the permission:",
                "&funstableffa.limit.<group>",
                "&7Click a group to change its numbers.",
                "&7Click a staff head to give him a personal limit.",
                "&7/punishlimits addgroup <name> makes a new group."));
        inv.setItem(49, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>"));

        List<String> gs = this.groups();
        for (int i = 0; i < gs.size() && i < GROUP_SLOTS.length; i++) {
            String g = gs.get(i);
            List<String> lore = new ArrayList<>();
            for (String a : ACTIONS) {
                lore.add("&7" + a + ": &f" + pretty(this.groupValue(g, a)));
            }
            lore.add("");
            lore.add("&7Permission: &funstableffa.limit." + g.toLowerCase(Locale.ROOT));
            lore.add("&#FF8330Click to edit");
            inv.setItem(GROUP_SLOTS[i], this.item(Material.NAME_TAG, "&#B15CFF&l" + g, lore.toArray(new String[0])));
        }

        List<Player> staff = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (this.plugin.isStaff(o) || !this.groupsOf(o).isEmpty() || this.hasOverride(o.getUniqueId())) {
                staff.add(o);
            }
        }
        staff.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < staff.size() && i < STAFF_SLOTS.length; i++) {
            Player o = staff.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta m = head.getItemMeta();
            if (m instanceof SkullMeta) {
                ((SkullMeta) m).setOwningPlayer(o);
            }
            if (m != null) {
                m.setDisplayName(Announcer.color("&f&l" + o.getName()));
                List<String> lore = new ArrayList<>();
                List<String> og = this.groupsOf(o);
                lore.add(Announcer.color("&7Group: &f" + (og.isEmpty() ? "none" : String.join(", ", og))
                        + (this.exempt(o) ? " &8(exempt)" : "")));
                for (String a : ACTIONS) {
                    lore.add(Announcer.color("&7" + a + ": &f" + this.used(o.getUniqueId(), a) + "&7/&f" + pretty(this.limitOf(o, a))));
                }
                lore.add("");
                lore.add(Announcer.color("&#FF8330Click to edit"));
                m.setLore(lore);
                head.setItemMeta(m);
            }
            inv.setItem(STAFF_SLOTS[i], head);
        }
        p.openInventory(inv);
    }

    private boolean hasOverride(UUID id) {
        return this.plugin.data().contains("punishlimits.override." + id);
    }

    private void openGroup(Player p, String group) {
        Holder h = this.holder("group", 27, "&8Limits: " + group);
        h.group = group;
        for (int i = 0; i < ACTIONS.length; i++) {
            String a = ACTIONS[i];
            h.inv.setItem(ACTION_SLOTS[i], this.item(iconFor(a), "&#B15CFF&l" + a.toUpperCase(Locale.ROOT) + " per day",
                    "&7Now: &f" + pretty(this.groupValue(group, a)),
                    "",
                    "&#FF8330Left click &7+1    &#FF8330Right click &7-1",
                    "&#FF8330Shift &7= 5 at a time",
                    "&#FF8330Middle click or Q &7= unlimited"));
        }
        h.inv.setItem(18, this.item(Material.ARROW, "&7Back"));
        h.inv.setItem(26, this.item(Material.TNT, "<gradient:#FF7518:#FFFFFF>&lDelete</gradient><gradient:#FFFFFF:#FF7518>&l group</gradient>", "&7Shift + click to delete."));
        p.openInventory(h.inv);
    }

    private void openPlayer(Player viewer, UUID id) {
        OfflinePlayer t = Bukkit.getOfflinePlayer(id);
        String name = t.getName() != null ? t.getName() : id.toString().substring(0, 8);
        Holder h = this.holder("player", 27, "&8Limits: " + name);
        h.target = id;
        Player online = t.getPlayer();
        for (int i = 0; i < ACTIONS.length; i++) {
            String a = ACTIONS[i];
            int o = this.override(id, a);
            String group = online != null ? pretty(this.fromGroups(online, a)) : "unknown (offline)";
            h.inv.setItem(ACTION_SLOTS[i], this.item(iconFor(a), "&#B15CFF&l" + a.toUpperCase(Locale.ROOT) + " per day",
                    "&7Personal limit: &f" + (o == -2 ? "none (uses group)" : pretty(o)),
                    "&7Group gives: &f" + group,
                    "&7Used today: &f" + this.used(id, a),
                    "",
                    "&#FF8330Left click &7+1    &#FF8330Right click &7-1",
                    "&#FF8330Shift &7= 5 at a time",
                    "&#FF8330Q &7= unlimited",
                    "&#FF8330Middle click &7= remove personal limit"));
        }
        h.inv.setItem(4, this.item(Material.PLAYER_HEAD, "&f&l" + name));
        ItemMeta m = h.inv.getItem(4).getItemMeta();
        if (m instanceof SkullMeta) {
            ((SkullMeta) m).setOwningPlayer(t);
            h.inv.getItem(4).setItemMeta(m);
        }
        h.inv.setItem(18, this.item(Material.ARROW, "&7Back"));
        h.inv.setItem(22, this.item(Material.SPONGE, "<gradient:#FF7518:#FFFFFF>&lReset today</gradient><gradient:#FFFFFF:#FF7518>&l's counter</gradient>", "&7Gives him his punishments back for today."));
        viewer.openInventory(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
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
        if (!p.hasPermission("unstableffa.punishlimits.admin")) {
            p.closeInventory();
            return;
        }
        Holder h = (Holder) e.getInventory().getHolder();
        int slot = e.getRawSlot();
        ClickType ct = e.getClick();
        this.click(p);
        if (h.kind.equals("main")) {
            this.clickMain(p, slot);
        } else if (h.kind.equals("group")) {
            this.clickGroup(p, h, slot, ct);
        } else {
            this.clickPlayer(p, h, slot, ct);
        }
    }

    private void clickMain(Player p, int slot) {
        if (slot == 49) {
            p.closeInventory();
            return;
        }
        if (slot == 4) {
            this.cfg().set("enabled", !this.enabled());
            this.plugin.saveConfig();
            this.openMain(p);
            return;
        }
        List<String> gs = this.groups();
        for (int i = 0; i < GROUP_SLOTS.length && i < gs.size(); i++) {
            if (GROUP_SLOTS[i] == slot) {
                this.openGroup(p, gs.get(i));
                return;
            }
        }
        List<Player> staff = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (this.plugin.isStaff(o) || !this.groupsOf(o).isEmpty() || this.hasOverride(o.getUniqueId())) {
                staff.add(o);
            }
        }
        staff.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < STAFF_SLOTS.length && i < staff.size(); i++) {
            if (STAFF_SLOTS[i] == slot) {
                this.openPlayer(p, staff.get(i).getUniqueId());
                return;
            }
        }
    }

    /** New value after a click. current -1 = unlimited. Returns Integer.MIN_VALUE for "no change". */
    private static int nudge(int current, ClickType ct) {
        int step = ct.isShiftClick() ? 5 : 1;
        if (ct.isLeftClick()) {
            return current < 0 ? current : current + step;
        }
        if (ct.isRightClick()) {
            return current < 0 ? 10 : Math.max(0, current - step);
        }
        return Integer.MIN_VALUE;
    }

    private void clickGroup(Player p, Holder h, int slot, ClickType ct) {
        if (slot == 18) {
            this.openMain(p);
            return;
        }
        if (slot == 26) {
            if (ct.isShiftClick()) {
                this.cfg().set("groups." + h.group, null);
                this.plugin.saveConfig();
                this.openMain(p);
            }
            return;
        }
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTION_SLOTS[i] != slot) {
                continue;
            }
            String a = ACTIONS[i];
            int cur = this.groupValue(h.group, a);
            int next;
            if (ct == ClickType.MIDDLE || ct == ClickType.DROP || ct == ClickType.CONTROL_DROP) {
                next = -1;
            } else {
                next = nudge(cur, ct);
            }
            if (next != Integer.MIN_VALUE && next != cur) {
                this.setGroupValue(h.group, a, next);
            }
            this.openGroup(p, h.group);
            return;
        }
    }

    private void clickPlayer(Player p, Holder h, int slot, ClickType ct) {
        if (slot == 18) {
            this.openMain(p);
            return;
        }
        if (slot == 22) {
            this.plugin.data().set("punishlimits.used." + h.target, null);
            this.plugin.saveData();
            this.openPlayer(p, h.target);
            return;
        }
        for (int i = 0; i < ACTIONS.length; i++) {
            if (ACTION_SLOTS[i] != slot) {
                continue;
            }
            String a = ACTIONS[i];
            String path = "punishlimits.override." + h.target + "." + a;
            int cur = this.override(h.target, a);
            if (ct == ClickType.MIDDLE) {
                this.plugin.data().set(path, null);
                if (this.plugin.data().getConfigurationSection("punishlimits.override." + h.target) != null
                        && this.plugin.data().getConfigurationSection("punishlimits.override." + h.target).getKeys(false).isEmpty()) {
                    this.plugin.data().set("punishlimits.override." + h.target, null);
                }
            } else if (ct == ClickType.DROP || ct == ClickType.CONTROL_DROP) {
                this.plugin.data().set(path, -1);
            } else {
                int base = cur;
                if (cur == -2) {
                    Player on = Bukkit.getPlayer(h.target);
                    base = on != null ? this.fromGroups(on, a) : 0;
                    if (base < 0) {
                        base = 10;
                    }
                    // first click starts from what the group gives
                    this.plugin.data().set(path, base);
                    cur = base;
                }
                int next = nudge(cur, ct);
                if (next != Integer.MIN_VALUE) {
                    this.plugin.data().set(path, next);
                }
            }
            this.plugin.saveData();
            this.openPlayer(p, h.target);
            return;
        }
    }

    List<String> tab(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("me", "player", "addgroup", "delgroup", "reset"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("delgroup")) {
            out.addAll(this.groups());
        } else if (args.length == 2 && args[0].matches("(?i)player|reset")) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                out.add(o.getName());
            }
        }
        return out;
    }
}
