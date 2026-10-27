package org;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /warps  -  a menu with every warp you can use. Click a warp to teleport.
 *
 *   /warps              the menu (pages of 28)
 *   /warps list         the same list in chat, every name clickable
 *   /warps go <name>    teleport to a warp
 *
 * You only see the warps you may use: the same rule as /warp (op, or the permission
 * unstableffa.warp.<name>, spaces become underscores).
 * Warps are the ones made with /setwarp (saved in data.yml).
 *
 * Permission:  unstableffa.warps (everyone)      Settings: config.yml -> warp-list
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class Warps implements Listener {

    private static final class Holder implements InventoryHolder {
        final int page;
        final List<String> names;

        Holder(int page, List<String> names) {
            this.page = page;
            this.names = names;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final int[] SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    private static final int PREV = 45;
    private static final int CLOSE = 49;
    private static final int NEXT = 53;

    private final zenithEssentials plugin;

    Warps(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.warps")) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("warp-list.enabled", true)) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7The warp list is turned off."));
            return;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Warps: " + String.join(", ", this.usable(null)));
            return;
        }
        Player p = (Player) s;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("go") && args.length > 1) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            String key = this.find(name);
            if (key == null) {
                p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7There is no warp called '&f" + name + "&7'."));
            } else {
                this.go(p, key);
            }
            return;
        }
        if (sub.equals("list")) {
            this.chatList(p);
            return;
        }
        this.open(p, 0);
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("list");
            out.add("go");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("go")) {
            out.addAll(this.usable(s));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ data

    /** The warps this viewer may use, sorted. A null viewer (console) sees all of them. */
    private List<String> usable(CommandSender viewer) {
        List<String> out = new ArrayList<>();
        ConfigurationSection ws = this.plugin.data().getConfigurationSection("warps");
        if (ws != null) {
            for (String key : ws.getKeys(false)) {
                if (viewer == null || this.canUse(viewer, key)) {
                    out.add(key);
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private boolean canUse(CommandSender s, String key) {
        return s.isOp() || s.hasPermission("unstableffa.warp." + key.toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    private String find(String name) {
        ConfigurationSection ws = this.plugin.data().getConfigurationSection("warps");
        if (ws == null) {
            return null;
        }
        for (String key : ws.getKeys(false)) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    private void go(Player p, String key) {
        if (!this.canUse(p, key)) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You can't use that warp."));
            return;
        }
        Location loc = this.plugin.data().getLocation("warps." + key);
        if (loc == null || loc.getWorld() == null) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7That warp's world isn't loaded."));
            return;
        }
        this.plugin.startTp(p, loc, "warp-teleport", key);
    }

    // ------------------------------------------------------------------ chat list

    private void chatList(Player p) {
        List<String> names = this.usable(p);
        if (names.isEmpty()) {
            p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lWar</gradient><gradient:#FFFFFF:#FF7518>&lps</gradient> &8• &7There are no warps you can use."));
            return;
        }
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lWar</gradient><gradient:#FFFFFF:#FF7518>&lps</gradient> &8• ")));
        for (int i = 0; i < names.size(); i++) {
            TextComponent one = new TextComponent(TextComponent.fromLegacyText(Announcer.color("&f" + names.get(i))));
            one.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/warps go " + names.get(i)));
            line.addExtra(one);
            if (i < names.size() - 1) {
                line.addExtra(new TextComponent(TextComponent.fromLegacyText(Announcer.color("&8, "))));
            }
        }
        p.spigot().sendMessage(line);
        p.sendMessage(Announcer.color("&7Click a name to teleport."));
    }

    // ------------------------------------------------------------------ menu

    private void open(Player p, int page) {
        List<String> names = this.usable(p);
        if (names.isEmpty()) {
            p.sendMessage(Announcer.color("&8» <gradient:#FF7518:#FFFFFF>&lWar</gradient><gradient:#FFFFFF:#FF7518>&lps</gradient> &8• &7There are no warps you can use."));
            return;
        }
        int pages = Math.max(1, (names.size() + SLOTS.length - 1) / SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(new Holder(page, names), 54,
                Announcer.color("&8Warps &7(" + (page + 1) + "/" + pages + ")"));
        ItemStack filler = this.item(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 0; i < SLOTS.length; i++) {
            int idx = page * SLOTS.length + i;
            if (idx >= names.size()) {
                break;
            }
            String key = names.get(idx);
            Location l = this.plugin.data().getLocation("warps." + key);
            List<String> lore = new ArrayList<>();
            lore.add("&7World: &f" + (l == null || l.getWorld() == null ? "not loaded" : l.getWorld().getName()));
            lore.add("");
            lore.add("&#FF8330Click to teleport");
            inv.setItem(SLOTS[i], this.item(this.icon(key, l), "&#FF791F&l" + key, lore));
        }
        if (page > 0) {
            inv.setItem(PREV, this.item(Material.ARROW, "&#FF8330&l« Previous page", new ArrayList<>()));
        }
        if (page < pages - 1) {
            inv.setItem(NEXT, this.item(Material.ARROW, "&#FF8330&lNext page »", new ArrayList<>()));
        }
        inv.setItem(CLOSE, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));
        this.plugin.tidy(inv, false); // same calm style as every other menu
        p.openInventory(inv);
    }

    /** Icon from config.yml -> warp-list.icons.<name>, otherwise by dimension. */
    private Material icon(String key, Location l) {
        String cfg = this.plugin.getConfig().getString("warp-list.icons." + key.toLowerCase(Locale.ROOT).replace(' ', '_'));
        if (cfg != null) {
            Material m = Material.matchMaterial(cfg);
            if (m != null) {
                return m;
            }
        }
        if (l != null && l.getWorld() != null) {
            switch (l.getWorld().getEnvironment()) {
                case NETHER:
                    return Material.NETHERRACK;
                case THE_END:
                    return Material.END_STONE;
                default:
                    return Material.GRASS_BLOCK;
            }
        }
        return Material.COMPASS;
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
        if (slot == CLOSE) {
            p.closeInventory();
        } else if (slot == PREV && h.page > 0) {
            this.open(p, h.page - 1);
        } else if (slot == NEXT && (h.page + 1) * SLOTS.length < h.names.size()) {
            this.open(p, h.page + 1);
        } else {
            for (int i = 0; i < SLOTS.length; i++) {
                if (SLOTS[i] == slot) {
                    int idx = h.page * SLOTS.length + i;
                    if (idx < h.names.size()) {
                        p.closeInventory();
                        this.go(p, h.names.get(idx));
                    }
                    return;
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
}
