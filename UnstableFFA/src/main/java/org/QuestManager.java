package org;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

/**
 * Daily, weekly and monthly quests (/quests).
 *
 * Quests are set up in plugins/UnstableFFA/quests.yml (made for you on first start).
 * Every day/week/month a few quests are picked from each list - the same ones for everybody.
 * Progress counts automatically; when a quest is done the rewards are given right away.
 *
 * Quest types: BREAK_BLOCK, PLACE_BLOCK, KILL_MOB, KILL_PLAYER, FISH, CRAFT, EAT, WALK, PLAYTIME
 * Progress is saved in data.yml -> quests.<uuid>
 */
public final class QuestManager implements Listener {

    enum Period {
        DAILY("daily", "Daily", Material.CLOCK),
        WEEKLY("weekly", "Weekly", Material.WRITABLE_BOOK),
        MONTHLY("monthly", "Monthly", Material.NETHER_STAR);

        final String key;
        final String label;
        final Material icon;

        Period(String key, String label, Material icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }
    }

    static final class Quest {
        String id;
        Period period;
        String name;
        String type;
        List<String> targets = new ArrayList<>();
        int amount;
        Material icon;
        String description;
        String rewardText;
        List<String> rewards = new ArrayList<>();
    }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        Period view; // null = the Daily / Weekly / Monthly menu, otherwise the list for that period

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private static final String PLACED = "ze-placed";

    private final zenithEssentials plugin;
    private File file;
    private boolean enabled;
    private boolean creativeCounts;
    private boolean announce;
    private ZoneId zone = ZoneId.systemDefault();
    private final Map<Period, List<Quest>> pool = new EnumMap<>(Period.class);
    private final Map<Period, Integer> counts = new EnumMap<>(Period.class);

    // cache of the quests picked for the current day/week/month
    private final Map<Period, String> activeKey = new EnumMap<>(Period.class);
    private final Map<Period, List<Quest>> active = new EnumMap<>(Period.class);

    // per player, "periodKey|questId" -> progress / finished
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> done = new HashMap<>();
    private final Map<UUID, Double> walked = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    private BukkitTask ticker;
    private int minutes = 0;

    public QuestManager(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    // loading
    // =====================================================================================

    public void reload() {
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
        this.saveAll();
        this.file = Configs.file(this.plugin, "quests.yml");
        if (!this.file.exists()) {
            this.writeDefaults();
        }
        FileConfiguration q = YamlConfiguration.loadConfiguration(this.file);
        FileConfiguration c = this.plugin.getConfig();
        this.enabled = c.getBoolean("quests.enabled", true);
        this.creativeCounts = q.getBoolean("settings.creative-counts", false);
        this.announce = q.getBoolean("settings.announce-completion", false);
        try {
            String tz = q.getString("settings.timezone", "");
            this.zone = tz == null || tz.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (Exception ex) {
            this.zone = ZoneId.systemDefault();
        }
        this.pool.clear();
        this.activeKey.clear();
        this.active.clear();
        for (Period p : Period.values()) {
            this.counts.put(p, Math.max(1, q.getInt("settings." + p.key + "-count", p == Period.MONTHLY ? 2 : 3)));
            List<Quest> list = new ArrayList<>();
            ConfigurationSection sec = q.getConfigurationSection(p.key);
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    ConfigurationSection qs = sec.getConfigurationSection(id);
                    if (qs == null) {
                        continue;
                    }
                    Quest quest = new Quest();
                    quest.id = id;
                    quest.period = p;
                    quest.name = qs.getString("name", "&f" + id);
                    quest.type = qs.getString("type", "BREAK_BLOCK").toUpperCase(Locale.ROOT);
                    String tg = qs.getString("target", "");
                    if (tg != null && !tg.isEmpty() && !tg.equalsIgnoreCase("ANY")) {
                        for (String t : tg.split(",")) {
                            quest.targets.add(t.trim().toUpperCase(Locale.ROOT));
                        }
                    }
                    quest.amount = Math.max(1, qs.getInt("amount", 1));
                    Material m = Material.matchMaterial(qs.getString("icon", "PAPER"));
                    quest.icon = m == null ? Material.PAPER : m;
                    quest.description = qs.getString("description", "");
                    quest.rewardText = qs.getString("reward-text", "");
                    quest.rewards = qs.getStringList("rewards");
                    list.add(quest);
                }
            }
            list.sort((a, b) -> a.id.compareTo(b.id));
            this.pool.put(p, list);
        }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            this.load(pl.getUniqueId());
        }
        this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1200L, 1200L);
    }

    private void writeDefaults() {
        String d = String.join("\n",
                "# ==========================================================",
                "#                 UNSTABLE FFA QUESTS",
                "# ==========================================================",
                "# Every day / week / month, <count> quests are picked at random from each list below.",
                "# Everyone gets the same quests. Rewards are console commands ({player} = the player).",
                "#",
                "# type:   BREAK_BLOCK, PLACE_BLOCK, KILL_MOB, KILL_PLAYER, FISH, CRAFT, EAT, WALK (blocks), PLAYTIME (minutes)",
                "# target: what counts (optional). Block/item/mob names, comma separated.",
                "#         *_LOG = anything ending in _LOG,  DIAMOND* = anything starting with DIAMOND.  Leave out = anything.",
                "",
                "settings:",
                "  daily-count: 3",
                "  weekly-count: 3",
                "  monthly-count: 2",
                "  timezone: \"Europe/Copenhagen\"     # when quests reset (midnight, Monday, 1st of the month)",
                "  creative-counts: false            # count progress in creative mode?",
                "  announce-completion: false        # tell the whole server when someone finishes a quest",
                "",
                "daily:",
                "  miner:",
                "    name: \"&7Miner\"",
                "    type: BREAK_BLOCK",
                "    target: STONE,DEEPSLATE,COBBLESTONE,COBBLED_DEEPSLATE",
                "    amount: 256",
                "    icon: STONE_PICKAXE",
                "    reward-text: \"8 Iron Ingots\"",
                "    rewards: [\"give {player} iron_ingot 8\"]",
                "  lumberjack:",
                "    name: \"&#FF7518Lumberjack\"",
                "    type: BREAK_BLOCK",
                "    target: \"*_LOG\"",
                "    amount: 64",
                "    icon: IRON_AXE",
                "    reward-text: \"16 Bread\"",
                "    rewards: [\"give {player} bread 16\"]",
                "  monster-hunter:",
                "    name: \"&#D9381EMonster Hunter\"",
                "    type: KILL_MOB",
                "    target: ZOMBIE,SKELETON,SPIDER,CREEPER",
                "    amount: 25",
                "    icon: IRON_SWORD",
                "    reward-text: \"10 Golden Carrots\"",
                "    rewards: [\"give {player} golden_carrot 10\"]",
                "  fisher:",
                "    name: \"&#B15CFFFisher\"",
                "    type: FISH",
                "    amount: 10",
                "    icon: FISHING_ROD",
                "    reward-text: \"1 Diamond\"",
                "    rewards: [\"give {player} diamond 1\"]",
                "  explorer:",
                "    name: \"&#FF9147Explorer\"",
                "    type: WALK",
                "    amount: 2000",
                "    icon: LEATHER_BOOTS",
                "    reward-text: \"4 Ender Pearls\"",
                "    rewards: [\"give {player} ender_pearl 4\"]",
                "  active-player:",
                "    name: \"&#FF8330Active Player\"",
                "    type: PLAYTIME",
                "    amount: 60",
                "    icon: CLOCK",
                "    reward-text: \"1 Golden Apple\"",
                "    rewards: [\"give {player} golden_apple 1\"]",
                "",
                "weekly:",
                "  deep-miner:",
                "    name: \"&#B15CFFDeep Miner\"",
                "    type: BREAK_BLOCK",
                "    target: DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE",
                "    amount: 16",
                "    icon: DIAMOND_PICKAXE",
                "    reward-text: \"4 Diamonds\"",
                "    rewards: [\"give {player} diamond 4\"]",
                "  builder:",
                "    name: \"&#FF8330Builder\"",
                "    type: PLACE_BLOCK",
                "    amount: 1000",
                "    icon: BRICKS",
                "    reward-text: \"32 Experience Bottles\"",
                "    rewards: [\"give {player} experience_bottle 32\"]",
                "  slayer:",
                "    name: \"&#D9381ESlayer\"",
                "    type: KILL_MOB",
                "    amount: 200",
                "    icon: NETHERITE_SWORD",
                "    reward-text: \"1 Totem of Undying\"",
                "    rewards: [\"give {player} totem_of_undying 1\"]",
                "  chef:",
                "    name: \"&#FF7518Chef\"",
                "    type: EAT",
                "    amount: 50",
                "    icon: COOKED_BEEF",
                "    reward-text: \"8 Golden Carrots\"",
                "    rewards: [\"give {player} golden_carrot 8\"]",
                "  dedicated:",
                "    name: \"&#FF8330Dedicated\"",
                "    type: PLAYTIME",
                "    amount: 600",
                "    icon: CLOCK",
                "    reward-text: \"2 Golden Apples\"",
                "    rewards: [\"give {player} golden_apple 2\"]",
                "",
                "monthly:",
                "  veteran:",
                "    name: \"&#8E3BD1Veteran\"",
                "    type: PLAYTIME",
                "    amount: 1800",
                "    icon: NETHER_STAR",
                "    reward-text: \"1 Enchanted Golden Apple\"",
                "    rewards: [\"give {player} enchanted_golden_apple 1\"]",
                "  pvp-champion:",
                "    name: \"&#D9381EPvP Champion\"",
                "    type: KILL_PLAYER",
                "    amount: 50",
                "    icon: DIAMOND_SWORD",
                "    reward-text: \"1 Netherite Ingot\"",
                "    rewards: [\"give {player} netherite_ingot 1\"]",
                "  crafter:",
                "    name: \"&#FF7518Master Crafter\"",
                "    type: CRAFT",
                "    amount: 500",
                "    icon: CRAFTING_TABLE",
                "    reward-text: \"16 Diamonds\"",
                "    rewards: [\"give {player} diamond 16\"]",
                "");
        try {
            this.file.getParentFile().mkdirs();
            Files.write(this.file.toPath(), d.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not create quests.yml: " + ex.getMessage());
        }
    }

    // =====================================================================================
    // periods
    // =====================================================================================

    private String periodKey(Period p) {
        LocalDate d = LocalDate.now(this.zone);
        switch (p) {
            case WEEKLY:
                return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MONTHLY:
                return YearMonth.from(d).toString();
            default:
                return d.toString();
        }
    }

    private long msUntilReset(Period p) {
        ZonedDateTime now = ZonedDateTime.now(this.zone);
        LocalDate d = now.toLocalDate();
        LocalDate next;
        switch (p) {
            case WEEKLY:
                next = d.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                break;
            case MONTHLY:
                next = d.withDayOfMonth(1).plusMonths(1);
                break;
            default:
                next = d.plusDays(1);
                break;
        }
        return Duration.between(now, next.atStartOfDay(this.zone)).toMillis();
    }

    // The quests picked for the current day/week/month (same for everyone).
    private List<Quest> active(Period p) {
        String key = this.periodKey(p);
        if (!key.equals(this.activeKey.get(p))) {
            List<Quest> list = new ArrayList<>(this.pool.getOrDefault(p, Collections.emptyList()));
            Collections.shuffle(list, new Random(key.hashCode() * 31L + p.ordinal()));
            this.active.put(p, new ArrayList<>(list.subList(0, Math.min(this.counts.getOrDefault(p, 3), list.size()))));
            this.activeKey.put(p, key);
        }
        return this.active.get(p);
    }

    // =====================================================================================
    // progress storage
    // =====================================================================================

    private void load(UUID id) {
        Set<String> current = new HashSet<>();
        for (Period p : Period.values()) {
            current.add(this.periodKey(p));
        }
        Map<String, Integer> prog = new HashMap<>();
        ConfigurationSection ps = this.plugin.data().getConfigurationSection("quests." + id + ".progress");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                if (current.contains(k.split("\\|")[0])) {
                    prog.put(k, ps.getInt(k));
                }
            }
        }
        Set<String> fin = new HashSet<>();
        for (String k : this.plugin.data().getStringList("quests." + id + ".done")) {
            if (current.contains(k.split("\\|")[0])) {
                fin.add(k);
            }
        }
        this.progress.put(id, prog);
        this.done.put(id, fin);
    }

    private void save(UUID id) {
        Map<String, Integer> prog = this.progress.get(id);
        if (prog == null) {
            return;
        }
        String base = "quests." + id;
        this.plugin.data().set(base, null); // old periods are dropped here
        for (Map.Entry<String, Integer> en : prog.entrySet()) {
            this.plugin.data().set(base + ".progress." + en.getKey(), en.getValue());
        }
        this.plugin.data().set(base + ".done", new ArrayList<>(this.done.getOrDefault(id, Collections.emptySet())));
    }

    public void saveAll() {
        if (this.dirty.isEmpty()) {
            return;
        }
        for (UUID id : this.dirty) {
            this.save(id);
        }
        this.dirty.clear();
        this.plugin.saveData();
    }

    public void onJoin(Player p) {
        this.load(p.getUniqueId());
    }

    public void onQuit(Player p) {
        UUID id = p.getUniqueId();
        if (this.dirty.remove(id)) {
            this.save(id);
            this.plugin.saveData();
        }
        this.progress.remove(id);
        this.done.remove(id);
        this.walked.remove(id);
    }

    private void tick() {
        if (!this.enabled) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!this.plugin.vanished(p.getUniqueId())) {
                this.add(p, "PLAYTIME", null, 1);
            }
        }
        if (++this.minutes % 5 == 0) {
            this.saveAll();
        }
    }

    // =====================================================================================
    // counting
    // =====================================================================================

    private boolean matches(Quest q, String target) {
        if (q.targets.isEmpty()) {
            return true;
        }
        if (target == null) {
            return false;
        }
        for (String t : q.targets) {
            if (t.startsWith("*") ? target.endsWith(t.substring(1))
                    : t.endsWith("*") ? target.startsWith(t.substring(0, t.length() - 1)) : target.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private void add(Player p, String type, String target, int amount) {
        if (!this.enabled || amount <= 0) {
            return;
        }
        if (!this.creativeCounts && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        UUID id = p.getUniqueId();
        Map<String, Integer> prog = this.progress.get(id);
        if (prog == null) {
            this.load(id);
            prog = this.progress.get(id);
        }
        Set<String> fin = this.done.get(id);
        for (Period per : Period.values()) {
            String pk = this.periodKey(per);
            for (Quest q : this.active(per)) {
                if (!q.type.equals(type) || !this.matches(q, target)) {
                    continue;
                }
                String key = pk + "|" + q.id;
                if (fin.contains(key)) {
                    continue;
                }
                int v = Math.min(q.amount, prog.getOrDefault(key, 0) + amount);
                prog.put(key, v);
                this.dirty.add(id);
                if (v >= q.amount) {
                    fin.add(key);
                    this.complete(p, q);
                }
            }
        }
    }

    private void complete(Player p, Quest q) {
        for (String cmd : q.rewards) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
        p.sendTitle(this.plugin.color("<gradient:#FF7518:#FFFFFF>&lQUEST C</gradient><gradient:#FFFFFF:#FF7518>&lOMPLETE</gradient>"), this.plugin.color(q.name), 10, 50, 15);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
        p.sendMessage(this.plugin.color("&8» <gradient:#FF7518:#FFFFFF>&lQue</gradient><gradient:#FFFFFF:#FF7518>&lsts</gradient> &8• &7You finished the " + q.period.label.toLowerCase(Locale.ROOT)
                + " quest " + q.name + "&7!" + (q.rewardText.isEmpty() ? "" : " &7Reward: &f" + q.rewardText)));
        if (this.announce) {
            String msg = this.plugin.color("&8» <gradient:#FF7518:#FFFFFF>&lQue</gradient><gradient:#FFFFFF:#FF7518>&lsts</gradient> &8• &f" + p.getName() + " &7finished " + q.name + "&7!");
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) {
                    o.sendMessage(msg);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().hasMetadata(PLACED)) {
            e.getBlock().removeMetadata(PLACED, this.plugin);
            return; // placed by a player -> doesn't count (stops place/break farming)
        }
        this.add(e.getPlayer(), "BREAK_BLOCK", e.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        e.getBlock().setMetadata(PLACED, new FixedMetadataValue(this.plugin, true));
        this.add(e.getPlayer(), "PLACE_BLOCK", e.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        if (e.getEntity() instanceof Player) {
            if (!e.getEntity().equals(killer)) {
                this.add(killer, "KILL_PLAYER", "PLAYER", 1);
            }
        } else {
            this.add(killer, "KILL_MOB", e.getEntityType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item) {
            this.add(e.getPlayer(), "FISH", ((Item) e.getCaught()).getItemStack().getType().name(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player && e.getRecipe() != null) {
            ItemStack r = e.getRecipe().getResult();
            this.add((Player) e.getWhoClicked(), "CRAFT", r.getType().name(), Math.max(1, r.getAmount()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        this.add(e.getPlayer(), "EAT", e.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null || (f.getBlockX() == t.getBlockX() && f.getBlockZ() == t.getBlockZ()) || f.getWorld() != t.getWorld()) {
            return;
        }
        Player p = e.getPlayer();
        if (p.isFlying() || p.isGliding() || p.isInsideVehicle()) {
            return;
        }
        double dx = t.getX() - f.getX();
        double dz = t.getZ() - f.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 10) {
            return; // teleport, not walking
        }
        double total = this.walked.getOrDefault(p.getUniqueId(), 0.0) + dist;
        int whole = (int) total;
        this.walked.put(p.getUniqueId(), total - whole);
        this.add(p, "WALK", null, whole);
    }

    // =====================================================================================
    // /quests menu
    // =====================================================================================

    public void handle(CommandSender s, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("reset") && s.hasPermission("unstableffa.quests.admin")) {
            Player t = Bukkit.getPlayerExact(args[1]);
            UUID id = t != null ? t.getUniqueId() : this.plugin.offline(args[1]).getUniqueId();
            this.progress.remove(id);
            this.done.remove(id);
            this.plugin.data().set("quests." + id, null);
            this.plugin.saveData();
            if (t != null) {
                this.load(id);
            }
            s.sendMessage(this.plugin.color("&8» &#FF9147Quest progress reset for &f" + args[1] + "&#FF9147."));
            return;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can open /quests. Admin: /quests reset <player>");
            return;
        }
        Player p = (Player) s;
        if (!this.enabled) {
            p.sendMessage(this.plugin.color("&8» &#D9381E&lDisabled! &7Quests are turned off."));
            return;
        }
        this.openView(p, null);
    }

    private void openView(Player p, Period view) {
        Holder h = new Holder();
        h.view = view;
        h.inv = Bukkit.createInventory(h, 54, view == null ? "§8Quests" : "§8" + view.label + " quests");
        this.render(h, p);
        p.openInventory(h.inv);
    }

    // Layout (54 slots):
    //   Menu:  three big buttons - Daily, Weekly, Monthly (how many are done, when they reset).
    //   List:  click one and a double chest opens with ALL quests of that period, a status pane under each
    //          (green = done, yellow = in progress, grey = not started). Back (45) and Close (49).
    private static final int[] HUB_SLOTS = {20, 22, 24};
    private static final int[] LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34}; // status = slot + 9
    private static final int CLOSE_SLOT = 49;
    private static final int BACK_SLOT = 45;

    private void render(Holder h, Player p) {
        Inventory inv = h.inv;
        inv.clear();
        UUID id = p.getUniqueId();
        if (!this.progress.containsKey(id)) {
            this.load(id);
        }
        Map<String, Integer> prog = this.progress.get(id);
        Set<String> fin = this.done.get(id);

        if (h.view == null) {
            for (Period per : Period.values()) {
                String tpk = this.periodKey(per);
                List<Quest> tqs = this.active(per);
                int finished = 0;
                for (Quest q : tqs) {
                    if (fin.contains(tpk + "|" + q.id)) {
                        finished++;
                    }
                }
                boolean all = finished == tqs.size() && !tqs.isEmpty();
                double pct = tqs.isEmpty() ? 0 : finished / (double) tqs.size();
                inv.setItem(HUB_SLOTS[per.ordinal()], this.item(per.icon, "&#FF791F&l" + per.label.toUpperCase(Locale.ROOT) + " QUESTS",
                        Arrays.asList("&7Completed: " + (all ? "&#FF9147" : "&f") + finished + "&7/&f" + tqs.size(),
                                this.bar(pct),
                                "&7New quests in: &f" + this.plugin.duration(this.msUntilReset(per)),
                                "",
                                "&#FF8330Click to see the quests"), all));
            }
            inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", Collections.emptyList(), false));
            return;
        }

        String pk = this.periodKey(h.view);
        List<Quest> qs = this.active(h.view);
        int finished = 0;
        for (Quest q : qs) {
            if (fin.contains(pk + "|" + q.id)) {
                finished++;
            }
        }
        inv.setItem(4, this.item(h.view.icon, "&#FF791F&l" + h.view.label.toUpperCase(Locale.ROOT) + " QUESTS",
                Arrays.asList("&7Completed: &f" + finished + "&7/&f" + qs.size(),
                        "&7New quests in: &f" + this.plugin.duration(this.msUntilReset(h.view))), false));
        int n = Math.min(qs.size(), LIST_SLOTS.length);
        for (int i = 0; i < n; i++) {
            Quest q = qs.get(i);
            String key = pk + "|" + q.id;
            boolean isDone = fin.contains(key);
            int v = isDone ? q.amount : Math.min(q.amount, prog.getOrDefault(key, 0));
            int pct = (int) Math.floor(Math.min(1.0, v / (double) q.amount) * 100);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + this.describe(q));
            lore.add("");
            lore.add("&7Progress: " + (isDone ? "&#FF9147" : "&f") + v + "&7/&f" + q.amount);
            lore.add(this.bar(pct / 100.0));
            if (q.rewardText != null && !q.rewardText.isEmpty()) {
                lore.add("");
                lore.add("&7Reward: &f" + q.rewardText);
            }
            int slot = LIST_SLOTS[i];
            inv.setItem(slot, this.item(q.icon, (isDone ? "&#FF9147&l" : "&f&l") + q.name, lore, isDone));
            // status underneath, readable at a glance
            if (isDone) {
                inv.setItem(slot + 9, this.item(Material.LIME_STAINED_GLASS_PANE, "&#FF9147Completed", Collections.emptyList(), false));
            } else if (pct > 0) {
                inv.setItem(slot + 9, this.item(Material.YELLOW_STAINED_GLASS_PANE, "&#FF8330" + pct + "%",
                        Collections.singletonList("&7" + v + "/" + q.amount), false));
            } else {
                inv.setItem(slot + 9, this.item(Material.GRAY_STAINED_GLASS_PANE, "&7Not started", Collections.emptyList(), false));
            }
        }
        if (n == 0) {
            inv.setItem(22, this.item(Material.BARRIER, "&7No quests right now", Collections.emptyList(), false));
        }
        inv.setItem(BACK_SLOT, this.item(Material.ARROW, "<gradient:#FF7518:#FFFFFF>&lBa</gradient><gradient:#FFFFFF:#FF7518>&lck</gradient>", Collections.emptyList(), false));
        inv.setItem(CLOSE_SLOT, this.item(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", Collections.emptyList(), false));
    }

    private String describe(Quest q) {
        if (q.description != null && !q.description.isEmpty()) {
            return q.description;
        }
        String what = q.targets.isEmpty() ? "" : " " + this.pretty(q.targets);
        switch (q.type) {
            case "BREAK_BLOCK":
                return "Break " + q.amount + (what.isEmpty() ? " blocks" : what);
            case "PLACE_BLOCK":
                return "Place " + q.amount + (what.isEmpty() ? " blocks" : what);
            case "KILL_MOB":
                return "Kill " + q.amount + (what.isEmpty() ? " mobs" : what);
            case "KILL_PLAYER":
                return "Kill " + q.amount + " players";
            case "FISH":
                return "Catch " + q.amount + " fish";
            case "CRAFT":
                return "Craft " + q.amount + (what.isEmpty() ? " items" : what);
            case "EAT":
                return "Eat " + q.amount + (what.isEmpty() ? " food" : what);
            case "WALK":
                return "Walk " + q.amount + " blocks";
            case "PLAYTIME":
                return "Play for " + this.plugin.duration(q.amount * 60000L);
            default:
                return q.type + " x" + q.amount;
        }
    }

    private String pretty(List<String> targets) {
        List<String> out = new ArrayList<>();
        for (String t : targets) {
            out.add(t.replace("*", "").replace("_", " ").trim().toLowerCase(Locale.ROOT));
        }
        if (out.size() > 3) {
            return String.join(", ", out.subList(0, 3)) + " ...";
        }
        return String.join(" / ", out);
    }

    private String bar(double pct) {
        int filled = (int) Math.floor(Math.min(1.0, pct) * 20);
        StringBuilder sb = new StringBuilder("&#FF9147");
        for (int i = 0; i < 20; i++) {
            if (i == filled) {
                sb.append("&8");
            }
            sb.append("|");
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
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        final Player p = (Player) e.getWhoClicked();
        Holder h = (Holder) e.getView().getTopInventory().getHolder();
        int slot = e.getRawSlot();
        if (slot == CLOSE_SLOT) {
            Bukkit.getScheduler().runTask(this.plugin, (Runnable) p::closeInventory);
        } else if (h.view != null && slot == BACK_SLOT) {
            Bukkit.getScheduler().runTask(this.plugin, () -> this.openView(p, null));
        } else if (h.view == null) {
            for (int i = 0; i < HUB_SLOTS.length; i++) {
                if (HUB_SLOTS[i] == slot) {
                    final Period per = Period.values()[i];
                    Bukkit.getScheduler().runTask(this.plugin, () -> this.openView(p, per));
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
