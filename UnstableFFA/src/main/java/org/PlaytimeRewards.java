package org;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * /rewards  -  rewards unlocked by total playtime (1 hour, 5 hours, 1 day ...).
 * Players click a reward in the menu to claim it; they get a clickable reminder in chat.
 * Settings: config.yml -> playtime-rewards     Saved in data.yml -> ptrewards.<uuid>
 */
public final class PlaytimeRewards implements Listener {

    private static final class Reward {
        String id;
        String name;
        long ms;
        Material icon;
        String text;
        List<String> commands;
    }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        final String[] slotReward = new String[54];

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private final zenithEssentials plugin;
    private final List<Reward> rewards = new ArrayList<>();
    private boolean enabled;
    private BukkitTask reminder;

    public PlaytimeRewards(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public void reload() {
        if (this.reminder != null) {
            this.reminder.cancel();
            this.reminder = null;
        }
        this.rewards.clear();
        FileConfiguration c = this.plugin.getConfig();
        this.enabled = c.getBoolean("playtime-rewards.enabled", true);
        ConfigurationSection rs = c.getConfigurationSection("playtime-rewards.rewards");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) {
                    continue;
                }
                Reward rw = new Reward();
                rw.id = id;
                rw.name = r.getString("name", "&#B15CFF" + id);
                rw.ms = this.plugin.parseDuration(r.getString("playtime", "1h"));
                Material m = Material.matchMaterial(r.getString("icon", "CHEST"));
                rw.icon = m == null ? Material.CHEST : m;
                rw.text = r.getString("reward-text", "");
                rw.commands = r.getStringList("commands");
                this.rewards.add(rw);
            }
        }
        this.rewards.sort((a, b) -> Long.compare(a.ms, b.ms));
        long every = Math.max(1L, c.getLong("playtime-rewards.remind-every-minutes", 10L)) * 1200L;
        if (this.enabled && !this.rewards.isEmpty()) {
            this.reminder = Bukkit.getScheduler().runTaskTimer(this.plugin, this::remindAll, every, every);
        }
    }

    private List<String> claimed(UUID id) {
        return this.plugin.data().getStringList("ptrewards." + id);
    }

    private int claimable(Player p) {
        long pt = this.plugin.playtimeOf(p.getUniqueId());
        List<String> got = this.claimed(p.getUniqueId());
        int n = 0;
        for (Reward r : this.rewards) {
            if (pt >= r.ms && !got.contains(r.id)) {
                n++;
            }
        }
        return n;
    }

    private void remindAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int n = this.claimable(p);
            if (n > 0) {
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(this.plugin.color(
                        "&8» <gradient:#FF7518:#FFFFFF>&lRewa</gradient><gradient:#FFFFFF:#FF7518>&lrds</gradient> &8• &7You have &f" + n + " &7playtime reward" + (n == 1 ? "" : "s") + " waiting! &#FF9147&l[CLAIM]")));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rewards"));
                p.spigot().sendMessage(tc);
            }
        }
    }

    // ---------- menu ----------

    public void handle(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can use /rewards.");
            return;
        }
        Player p = (Player) s;
        if (!this.enabled) {
            p.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Playtime rewards are turned off."));
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset") && p.hasPermission("unstableffa.rewards.admin")) {
            UUID id = this.plugin.offline(args[1]).getUniqueId();
            this.plugin.data().set("ptrewards." + id, null);
            this.plugin.saveData();
            p.sendMessage(this.plugin.color("&8» &#FF9147Reset playtime rewards for &f" + args[1] + "&#FF9147."));
            return;
        }
        this.open(p);
    }

    private void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, "§8Playtime Rewards");
        this.render(h, p);
        p.openInventory(h.inv);
    }

    private void render(Holder h, Player p) {
        Inventory inv = h.inv;
        java.util.Arrays.fill(h.slotReward, null);
        ItemStack frame = this.item(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList(), false);
        inv.clear();
        for (int i = 45; i < 54; i++) { // only a dark bar at the bottom, no glass wall
            inv.setItem(i, frame);
        }
        long pt = this.plugin.playtimeOf(p.getUniqueId());
        List<String> got = this.claimed(p.getUniqueId());
        List<String> head = new ArrayList<>();
        head.add("&7Your playtime: &f" + this.plugin.duration(pt));
        head.add("&7Claimed: &f" + got.size() + "&7/&f" + this.rewards.size());
        inv.setItem(4, this.item(Material.CLOCK, "<gradient:#FF7518:#FFFFFF>&lPlaytime</gradient><gradient:#FFFFFF:#FF7518>&l Rewards</gradient>", head, false));

        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < this.rewards.size() && i < slots.length; i++) {
            Reward r = this.rewards.get(i);
            boolean have = got.contains(r.id);
            boolean ready = pt >= r.ms;
            List<String> lore = new ArrayList<>();
            lore.add("&7Play for &f" + this.plugin.duration(r.ms));
            if (!r.text.isEmpty()) {
                lore.add("&7Reward: &f" + r.text);
            }
            lore.add(" ");
            if (have) {
                lore.add("&#FF9147✔ Claimed");
            } else if (ready) {
                lore.add("&#FF8330» Click to claim!");
            } else {
                double pct = Math.min(1.0, pt / (double) Math.max(1L, r.ms));
                lore.add(this.bar(pct) + " &7" + (int) (pct * 100) + "%");
                lore.add("&7" + this.plugin.duration(r.ms - pt) + " to go");
            }
            Material m = have ? Material.LIME_DYE : r.icon; // claimable ones glow, claimed ones get a lime dye
            inv.setItem(slots[i], this.item(m, (have ? "&#FF9147" : ready ? "" : "&7") + r.name, lore, ready && !have));
            h.slotReward[slots[i]] = r.id;
        }
        inv.setItem(49, this.item(Material.BARRIER, "&#D9381EClose", Collections.emptyList(), false));
        h.slotReward[49] = "#close";
    }

    private String bar(double pct) {
        int filled = (int) Math.round(pct * 10);
        StringBuilder sb = new StringBuilder("&#FF9147");
        for (int i = 0; i < 10; i++) {
            if (i == filled) {
                sb.append("&8");
            }
            sb.append("■");
        }
        return sb.toString();
    }

    private ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) {
                l.add(this.plugin.color(s));
            }
            meta.setLore(l);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder) || !(e.getWhoClicked() instanceof Player)) {
            return;
        }
        e.setCancelled(true);
        Holder h = (Holder) e.getView().getTopInventory().getHolder();
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54 || h.slotReward[slot] == null) {
            return;
        }
        String id = h.slotReward[slot];
        if (id.equals("#close")) {
            Bukkit.getScheduler().runTask(this.plugin, (Runnable) p::closeInventory);
            return;
        }
        Reward r = null;
        for (Reward x : this.rewards) {
            if (x.id.equals(id)) {
                r = x;
            }
        }
        if (r == null) {
            return;
        }
        List<String> got = this.claimed(p.getUniqueId());
        if (got.contains(r.id) || this.plugin.playtimeOf(p.getUniqueId()) < r.ms) {
            return;
        }
        got.add(r.id);
        this.plugin.data().set("ptrewards." + p.getUniqueId(), got);
        this.plugin.saveData();
        for (String cmd : r.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F);
        p.sendMessage(this.plugin.color("&8» <gradient:#FF7518:#FFFFFF>&lRewa</gradient><gradient:#FFFFFF:#FF7518>&lrds</gradient> &8• &7You claimed " + r.name + "&7!"));
        this.render(h, p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
