package org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /rules  -  the server rules in a menu, with an "I have read the rules" button.
 * /guide  -  a help menu for new players: topics with the commands they need. Click a topic to
 *            get the commands in chat, each one clickable.
 * /staffguide - the same kind of menu for staff, with the staff commands (config.yml -> staff-guide).
 *
 * New players get the rules menu on their first join. If rules.require-accept is on, players
 * can't break or place blocks until they accepted the rules. Raise rules.version when you change
 * the rules and everybody has to read and accept them again.
 *
 * Permissions: unstableffa.rules, unstableffa.guide (everyone), unstableffa.staffguide (staff)
 *              unstableffa.rules.bypass (never blocked, for staff)
 * Settings:    config.yml -> rules, guide        Accepted rules are saved in data.yml -> rules-accepted.<uuid>
 * Works on its own: it only needs the plugin's config.yml and the bridge methods in the main class.
 */
final class RulesGuide implements Listener {

    private static final class Holder implements InventoryHolder {
        final String kind; // "rules" or "guide"

        Holder(String kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int SIZE = 45;
    private static final int OTHER_SLOT = 39;   // rules <-> guide
    private static final int MAIN_SLOT = 40;    // accept button (rules)
    private static final int CLOSE_SLOT = 44;

    private final zenithEssentials plugin;
    private final Map<UUID, Long> lastRemind = new HashMap<>();

    RulesGuide(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ commands

    void handle(CommandSender s, String name, String[] args) {
        boolean rules = name.equals("rules");
        boolean staff = name.equals("staffguide");
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean(rules ? "rules.enabled" : staff ? "staff-guide.enabled" : "guide.enabled", true)) {
            s.sendMessage(Announcer.color("&8» &#D9381E&lError! &7That is turned off."));
            return;
        }
        if (!(s instanceof Player)) {
            if (rules) {
                List<Map<?, ?>> list = c.getMapList("rules.list");
                for (int i = 0; i < list.size(); i++) {
                    s.sendMessage((i + 1) + ". " + this.str(list.get(i), "title", "") + " - " + this.str(list.get(i), "text", ""));
                }
            } else {
                s.sendMessage("Only players can use /guide.");
            }
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission(rules ? "unstableffa.rules" : staff ? "unstableffa.staffguide" : "unstableffa.guide")) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (rules) {
            this.openRules(p);
        } else if (staff) {
            this.openGuide(p, "staff-guide");
        } else {
            this.openGuide(p);
        }
    }

    // ------------------------------------------------------------------ rules menu

    private void openRules(Player p) {
        FileConfiguration c = this.plugin.getConfig();
        Inventory inv = Bukkit.createInventory(new Holder("rules"), SIZE, Announcer.color("&8Server rules"));
        this.fill(inv);
        List<Map<?, ?>> list = c.getMapList("rules.list");
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
        if (list.isEmpty()) {
            inv.setItem(22, this.item(Material.PAPER, "&7No rules have been added yet", new ArrayList<>()));
        }

        if (c.getBoolean("guide.enabled", true)) {
            inv.setItem(OTHER_SLOT, this.item(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lServer</gradient><gradient:#FFFFFF:#FF7518>&l guide</gradient>", this.wrap("Commands and tips for getting started.", 34, "&7")));
        }
        if (this.accepted(p)) {
            inv.setItem(MAIN_SLOT, this.item(Material.EMERALD, "<gradient:#FF7518:#FFFFFF>&lYou accepte</gradient><gradient:#FFFFFF:#FF7518>&ld the rules</gradient>", this.wrap("Thank you! You can read them again any time with /rules.", 34, "&7")));
        } else {
            List<String> lore = this.wrap("Click here when you have read all the rules above.", 34, "&7");
            if (c.getBoolean("rules.require-accept", false)) {
                lore.add("");
                lore.add("&#FF8330You can't build until you accept.");
            }
            inv.setItem(MAIN_SLOT, this.item(Material.LIME_CONCRETE, "<gradient:#FF7518:#FFFFFF>&lI have read and </gradient><gradient:#FFFFFF:#FF7518>&laccept the rules</gradient>", lore));
        }
        String discord = c.getString("rules.discord", "");
        if (discord != null && !discord.isEmpty()) {
            inv.setItem(41, this.item(Material.NAME_TAG, "&#FF791F&lQuestions?", this.wrap("Ask staff on our Discord: " + discord, 34, "&7")));
        }
        inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));
        this.plugin.tidy(inv, false); // same calm style as every other menu
        p.openInventory(inv);
    }

    private boolean accepted(Player p) {
        int need = this.plugin.getConfig().getInt("rules.version", 1);
        return this.plugin.data().getInt("rules-accepted." + p.getUniqueId(), 0) >= need;
    }

    private boolean mustAccept(Player p) {
        FileConfiguration c = this.plugin.getConfig();
        return c.getBoolean("rules.enabled", true) && c.getBoolean("rules.require-accept", false)
                && !p.hasPermission("unstableffa.rules.bypass") && !this.accepted(p);
    }

    // ------------------------------------------------------------------ guide menu

    private void openGuide(Player p) {
        this.openGuide(p, "guide");
    }

    /** section = "guide" (/guide) or "staff-guide" (/staffguide). Both use the same topic format. */
    private void openGuide(Player p, String section) {
        FileConfiguration c = this.plugin.getConfig();
        boolean staff = section.equals("staff-guide");
        Inventory inv = Bukkit.createInventory(new Holder(staff ? "staffguide" : "guide"), SIZE,
                Announcer.color(staff ? "&8Staff guide" : "&8Server guide"));
        this.fill(inv);
        ConfigurationSection topics = c.getConfigurationSection(section + ".topics");
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
                lore.add("");
                lore.add("&#FF8330Click to get these in chat");
                inv.setItem(GRID[i++], this.item(mat == null ? Material.PAPER : mat, t.getString("name", key), lore));
            }
        }
        if (i == 0) {
            inv.setItem(22, this.item(Material.PAPER, "&7No guide topics have been added yet", new ArrayList<>()));
        }
        if (!staff && c.getBoolean("rules.enabled", true)) {
            inv.setItem(MAIN_SLOT, this.item(Material.WRITABLE_BOOK, "<gradient:#FF7518:#FFFFFF>&lServer</gradient><gradient:#FFFFFF:#FF7518>&l rules</gradient>", this.wrap("Read the rules of the server.", 34, "&7")));
        }
        inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", new ArrayList<>()));
        this.plugin.tidy(inv, false); // same calm style as every other menu
        p.openInventory(inv);
    }

    /** "/home | Go to your home" becomes "/home » Go to your home"; other lines are shown as plain text. */
    private String pretty(String line) {
        int bar = line.indexOf(" | ");
        if (bar == -1) {
            return "&7" + line;
        }
        return "&#FF791F" + line.substring(0, bar).trim() + " &8» &7" + line.substring(bar + 3).trim();
    }

    private void sendTopic(Player p, ConfigurationSection t) {
        p.sendMessage("");
        p.sendMessage(Announcer.color("&8&m      &r " + t.getString("name", "Guide") + " &8&m      "));
        for (String line : t.getStringList("lines")) {
            TextComponent tc = new TextComponent(TextComponent.fromLegacyText(Announcer.color(this.pretty(line))));
            int bar = line.indexOf(" | ");
            if (bar != -1 && line.trim().startsWith("/")) {
                String cmd = line.substring(0, bar).trim();
                int sp = cmd.indexOf(' ');
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, (sp == -1 ? cmd : cmd.substring(0, sp)) + " "));
            }
            p.spigot().sendMessage(tc);
        }
        p.sendMessage("");
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
        String kind = ((Holder) e.getInventory().getHolder()).kind;
        int slot = e.getRawSlot();
        if (slot == CLOSE_SLOT) {
            p.closeInventory();
            return;
        }
        if (kind.equals("rules")) {
            if (slot == OTHER_SLOT && this.plugin.getConfig().getBoolean("guide.enabled", true)) {
                this.openGuide(p);
            } else if (slot == MAIN_SLOT && !this.accepted(p)) {
                this.plugin.data().set("rules-accepted." + p.getUniqueId(), this.plugin.getConfig().getInt("rules.version", 1));
                this.plugin.saveData();
                p.closeInventory();
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F);
                p.sendMessage(Announcer.color("&8» &#FF9147&lThanks! &7You accepted the rules. Have fun!"));
            }
            return;
        }
        // guide / staff guide
        boolean staff = kind.equals("staffguide");
        if (!staff && slot == MAIN_SLOT && this.plugin.getConfig().getBoolean("rules.enabled", true)) {
            this.openRules(p);
            return;
        }
        ConfigurationSection topics = this.plugin.getConfig().getConfigurationSection((staff ? "staff-guide" : "guide") + ".topics");
        if (topics == null) {
            return;
        }
        int i = 0;
        for (String key : topics.getKeys(false)) {
            if (i >= GRID.length) {
                break;
            }
            ConfigurationSection t = topics.getConfigurationSection(key);
            if (t == null) {
                continue;
            }
            if (GRID[i++] == slot) {
                p.closeInventory();
                this.sendTopic(p, t);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ first join + require-accept

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        FileConfiguration c = this.plugin.getConfig();
        if (!c.getBoolean("rules.enabled", true) || p.hasPermission("unstableffa.rules.bypass") || !p.hasPermission("unstableffa.rules")) {
            return;
        }
        if (c.getBoolean("first-join.enabled", false)) {
            return; // FirstJoin (rules, then guide) takes care of new players
        }
        if (!p.hasPlayedBefore() && c.getBoolean("rules.show-on-first-join", true)) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (p.isOnline()) {
                    this.openRules(p);
                }
            }, 40L);
        } else if (c.getBoolean("rules.require-accept", false) && !this.accepted(p)) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (p.isOnline()) {
                    this.remind(p, true);
                }
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (this.mustAccept(e.getPlayer())) {
            e.setCancelled(true);
            this.remind(e.getPlayer(), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (this.mustAccept(e.getPlayer())) {
            e.setCancelled(true);
            this.remind(e.getPlayer(), false);
        }
    }

    private void remind(Player p, boolean force) {
        long now = System.currentTimeMillis();
        Long last = this.lastRemind.get(p.getUniqueId());
        if (!force && last != null && now - last < 5000L) {
            return;
        }
        this.lastRemind.put(p.getUniqueId(), now);
        TextComponent tc = new TextComponent(TextComponent.fromLegacyText(Announcer.color(
                "&8» <gradient:#FF7518:#FFFFFF>&lRul</gradient><gradient:#FFFFFF:#FF7518>&les</gradient> &8• &7Read and accept the rules to build. &#FF9147&l[OPEN /rules]")));
        tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rules"));
        p.spigot().sendMessage(tc);
    }

    // ------------------------------------------------------------------ helpers

    private void fill(Inventory inv) {
        ItemStack f = this.item(Material.GRAY_STAINED_GLASS_PANE, " ", new ArrayList<>());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, f);
        }
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

    private String str(Map<?, ?> m, String key, String def) {
        Object o = m.get(key);
        return o == null ? def : String.valueOf(o);
    }

    /** Splits text into lines of about "width" characters, each starting with the given color. */
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
