package org;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * /zenithmenu  -  one big menu (double chest) with buttons to the rest of the plugin.
 *
 * Every button is an entry in features.yml -> zenithmenu -> items. To add one, copy an entry,
 * give it a free slot (0-53) and say what it does. Nothing in the code to change.
 *
 *   slot          0-53 (counts from the top left)
 *   icon          any item name, or PLAYER_HEAD (shows the head of the player who opens the menu)
 *   name, lore    text with colors. Placeholders: {player} {kills} {deaths} {kd} {playtime} {online}
 *                 {staff_online} {ping} {world} {state} (toggles only)
 *   amount        stack size shown on the item (1-64)
 *   glow          true = enchanted shine
 *   permission    only players with this permission see the button
 *   hide-permission  players WITH this permission don't see the button (use it to give staff a different
 *                 button in the same slot: put the staff one first with "permission", the normal one with "hide-permission")
 *   enabled       false = hidden
 *
 *   What a click does (pick one):
 *   command       run a command (without the slash). run-as: player (default) or console
 *   open          open another menu from zenithmenu.menus.<name> ("main" = this menu)
 *   toggle        flip one of your /zenithsettings: messages, tpa, tpauto, sounds, alerts, vanish, socialspy.
 *                 Shows {state}; icon-off = a different icon when it is off
 *   link          send a clickable link in chat (link-message = the text, {link} in it)
 *
 *   close         true/false: close the menu on click (default: true, but false for open and toggle)
 *   cooldown      seconds before the same player can click this button again
 *   sound         click sound for this button (default zenithmenu.click-sound, "" = none)
 *
 * Permission:  unstableffa.menu (everyone)
 * Works on its own: it only needs the plugin's config and the bridge methods in the main class.
 */
final class ZenithMenu implements Listener {

    private static final class Holder implements InventoryHolder {
        final String menu;
        final Map<Integer, ConfigurationSection> buttons = new HashMap<>();
        Inventory inv;

        Holder(String menu) {
            this.menu = menu;
        }

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private static final int CLOSE_SLOT = 49;

    private final zenithEssentials plugin;
    private final Map<String, Long> used = new HashMap<>(); // "uuid:menu:key" -> millis

    ZenithMenu(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ command

    void handle(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("Only players can use /zenithmenu.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.menu")) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        if (!this.plugin.getConfig().getBoolean("zenithmenu.enabled", true)) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7The menu is turned off."));
            return;
        }
        String which = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "main";
        if (!which.equals("main") && this.itemsOf(which) == null) {
            p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7There is no menu called '&f" + which + "&7'."));
            return;
        }
        this.open(p, which);
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && s.hasPermission("unstableffa.menu")) {
            ConfigurationSection m = this.plugin.getConfig().getConfigurationSection("zenithmenu.menus");
            if (m != null) {
                String last = args[0].toLowerCase(Locale.ROOT);
                for (String k : m.getKeys(false)) {
                    if (k.toLowerCase(Locale.ROOT).startsWith(last)) {
                        out.add(k);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ menu

    private ConfigurationSection itemsOf(String menu) {
        if (menu.equals("main")) {
            return this.plugin.getConfig().getConfigurationSection("zenithmenu.items");
        }
        return this.plugin.getConfig().getConfigurationSection("zenithmenu.menus." + menu + ".items");
    }

    private void open(Player p, String menu) {
        Holder h = new Holder(menu);
        String title = menu.equals("main")
                ? this.plugin.getConfig().getString("zenithmenu.title", "&8Unstable Menu")
                : this.plugin.getConfig().getString("zenithmenu.menus." + menu + ".title", "&8Unstable Menu");
        Inventory inv = Bukkit.createInventory(h, 54, Announcer.color(title));
        h.inv = inv;

        int backSlot = this.plugin.getConfig().getInt("zenithmenu.back-slot", 45);
        ConfigurationSection items = this.itemsOf(menu);
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", true) || !this.visible(p, sec)) {
                    continue;
                }
                int slot = sec.getInt("slot", -1);
                if (slot < 0 || slot > 53 || slot == CLOSE_SLOT || (!menu.equals("main") && slot == backSlot)) {
                    this.plugin.getLogger().warning("zenithmenu " + menu + "." + key + ": slot must be 0-53 (and not " + CLOSE_SLOT
                            + (menu.equals("main") ? "" : " or " + backSlot) + ", those are Close/Back).");
                    continue;
                }
                if (h.buttons.containsKey(slot)) {
                    continue; // an earlier button for this player already uses the slot
                }
                inv.setItem(slot, this.build(p, sec));
                h.buttons.put(slot, sec);
            }
        }
        if (this.plugin.getConfig().getBoolean("zenithmenu.filler.enabled", false)) {
            Material fm = Material.matchMaterial(this.plugin.getConfig().getString("zenithmenu.filler.icon", "GRAY_STAINED_GLASS_PANE"));
            ItemStack filler = this.plain(fm == null ? Material.GRAY_STAINED_GLASS_PANE : fm, "&r");
            for (int i = 0; i < 54; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, filler);
                }
            }
        }
        inv.setItem(CLOSE_SLOT, this.plain(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>"));
        if (!menu.equals("main")) {
            inv.setItem(backSlot, this.plain(Material.ARROW, "<gradient:#FF7518:#FFFFFF>&lBa</gradient><gradient:#FFFFFF:#FF7518>&lck</gradient>"));
        }
        this.plugin.tidy(inv, false); // same calm style as every other menu
        p.openInventory(inv);
    }

    private boolean visible(Player p, ConfigurationSection sec) {
        String perm = sec.getString("permission", "");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) {
            return false;
        }
        String hide = sec.getString("hide-permission", "");
        return hide == null || hide.isEmpty() || !p.hasPermission(hide);
    }

    private ItemStack build(Player p, ConfigurationSection sec) {
        String state = "";
        boolean on = true;
        String toggle = sec.getString("toggle", "");
        if (toggle != null && !toggle.isEmpty()) {
            Boolean b = this.plugin.settingState(p, toggle);
            on = b == null || b;
            state = Announcer.color(on ? sec.getString("state-on", "&#FF9147On") : sec.getString("state-off", "&#D9381EOff"));
        }
        String icon = sec.getString("icon", "PAPER");
        if (!on && sec.getString("icon-off") != null) {
            icon = sec.getString("icon-off");
        }
        ItemStack it;
        if (icon.equalsIgnoreCase("PLAYER_HEAD")) {
            it = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) it.getItemMeta();
            sm.setOwningPlayer(p);
            it.setItemMeta(sm);
        } else {
            Material m = Material.matchMaterial(icon);
            it = new ItemStack(m == null ? Material.PAPER : m);
        }
        it.setAmount(Math.max(1, Math.min(64, sec.getInt("amount", 1))));
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Announcer.color(this.fill(sec.getString("name", "&f" + sec.getName()), p, state)));
        List<String> lore = new ArrayList<>();
        for (String l : sec.getStringList("lore")) {
            lore.add(Announcer.color(this.fill(l, p, state)));
        }
        meta.setLore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        if (sec.getBoolean("glow", false)) {
            meta.addEnchant(Enchantment.LURE, 1, true);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack plain(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Announcer.color(name));
        it.setItemMeta(meta);
        return it;
    }

    /** Placeholders. The costly ones are only worked out when the text really uses them. */
    private String fill(String s, Player p, String state) {
        if (s == null) {
            return "";
        }
        String out = s.replace("{player}", p.getName()).replace("{state}", state);
        if (out.indexOf('{') < 0) {
            return out;
        }
        if (out.contains("{kills}") || out.contains("{kd}")) {
            int k = p.getStatistic(Statistic.PLAYER_KILLS);
            int d = p.getStatistic(Statistic.DEATHS);
            out = out.replace("{kills}", String.valueOf(k)).replace("{kd}", d == 0 ? String.valueOf(k) : String.format(Locale.ROOT, "%.2f", k / (double) d));
        }
        if (out.contains("{deaths}")) {
            out = out.replace("{deaths}", String.valueOf(p.getStatistic(Statistic.DEATHS)));
        }
        if (out.contains("{playtime}")) {
            out = out.replace("{playtime}", this.plugin.duration(this.plugin.playtimeOf(p.getUniqueId())));
        }
        if (out.contains("{online}")) {
            int n = 0;
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!this.plugin.vanished(o.getUniqueId()) || p.hasPermission("unstableffa.vanish")) {
                    n++;
                }
            }
            out = out.replace("{online}", String.valueOf(n));
        }
        if (out.contains("{staff_online}")) {
            int n = 0;
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (this.plugin.isStaff(o) && !this.plugin.vanished(o.getUniqueId())) {
                    n++;
                }
            }
            out = out.replace("{staff_online}", String.valueOf(n));
        }
        if (out.contains("{ping}")) {
            out = out.replace("{ping}", String.valueOf(p.getPing()));
        }
        if (out.contains("{world}")) {
            out = out.replace("{world}", p.getWorld().getName());
        }
        return out;
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
        final Player p = (Player) e.getWhoClicked();
        final Holder h = (Holder) e.getInventory().getHolder();
        int slot = e.getRawSlot();
        if (slot == CLOSE_SLOT) {
            p.closeInventory();
            return;
        }
        if (!h.menu.equals("main") && slot == this.plugin.getConfig().getInt("zenithmenu.back-slot", 45)) {
            this.click(p, null);
            Bukkit.getScheduler().runTask(this.plugin, () -> this.open(p, "main"));
            return;
        }
        final ConfigurationSection sec = h.buttons.get(slot);
        if (sec == null) {
            return;
        }

        // cooldown per button
        int cd = sec.getInt("cooldown", 0);
        if (cd > 0 && !p.hasPermission("unstableffa.menu.bypass")) {
            String key = p.getUniqueId() + ":" + h.menu + ":" + sec.getName();
            long now = System.currentTimeMillis();
            Long last = this.used.get(key);
            if (last != null && now - last < cd * 1000L) {
                long left = (cd * 1000L - (now - last) + 999L) / 1000L;
                p.sendMessage(Announcer.color(this.plugin.getConfig().getString("zenithmenu.cooldown-message", "&8» &#D9381E&lWait! &7You can use that again in &f{time}s&7.")
                        .replace("{time}", String.valueOf(left))));
                return;
            }
            this.used.put(key, now);
        }
        this.click(p, sec);

        String open = sec.getString("open", "");
        String toggle = sec.getString("toggle", "");
        String link = sec.getString("link", "");
        String cmd = this.fill(sec.getString("command", ""), p, "").replaceFirst("^/", "").trim();

        if (open != null && !open.isEmpty()) {
            final String target = open.toLowerCase(Locale.ROOT);
            if (target.equals("main") || this.itemsOf(target) != null) {
                Bukkit.getScheduler().runTask(this.plugin, () -> this.open(p, target));
            } else {
                p.sendMessage(Announcer.color("&8» &#D9381E&lError! &7Menu '&f" + open + "&7' doesn't exist."));
            }
            return;
        }
        if (toggle != null && !toggle.isEmpty()) {
            this.plugin.flipSetting(p, toggle);
            e.getInventory().setItem(slot, this.build(p, sec)); // show the new state straight away
            this.plugin.tidy(e.getInventory(), false);
            if (sec.getBoolean("close", false)) {
                p.closeInventory();
            }
            return;
        }
        if (link != null && !link.isEmpty()) {
            p.closeInventory();
            String url = link.toLowerCase(Locale.ROOT).startsWith("http") ? link : "https://" + link;
            String text = Announcer.color(sec.getString("link-message", "&8» &7Click here: &#B15CFF{link}").replace("{link}", link));
            TextComponent comp = new TextComponent(TextComponent.fromLegacyText(text));
            comp.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            comp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(Announcer.color("&7Click to open"))));
            p.spigot().sendMessage(comp);
            return;
        }

        final boolean console = sec.getString("run-as", "player").equalsIgnoreCase("console");
        if (sec.getBoolean("close", true)) {
            p.closeInventory();
        }
        if (cmd.isEmpty()) {
            return;
        }
        final String run = cmd;
        // one tick later, so the old menu is closed before a command opens a new one
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (console) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
            } else {
                p.performCommand(run);
            }
        });
    }

    private void click(Player p, ConfigurationSection sec) {
        String sn = sec != null && sec.contains("sound") ? sec.getString("sound", "")
                : this.plugin.getConfig().getString("zenithmenu.click-sound", "UI_BUTTON_CLICK");
        if (sn == null || sn.isEmpty()) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.valueOf(sn.toUpperCase(Locale.ROOT)), 0.6F, 1.2F);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String prefix = e.getPlayer().getUniqueId() + ":";
        this.used.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
