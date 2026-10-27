package org;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.potion.PotionEffect;
import org.bukkit.BanList.Type;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class zenithEssentials extends JavaPlugin implements Listener, TabCompleter {
    private File dataFile;
    private File msgFile;
    private FileConfiguration data;
    private FileConfiguration messages;
    private final Map<UUID, UUID> tpaReq = new HashMap();
    private final Map<UUID, Boolean> tpType = new HashMap();
    private final Set<UUID> god = new HashSet();
    private final Set<UUID> vanished = new HashSet();
    private final Set<UUID> tpAuto = new HashSet();
    private final Set<UUID> tpDisabled = new HashSet();
    private final Set<UUID> frozen = new HashSet();
    private final Set<UUID> socialSpy = new HashSet();
    private final Set<UUID> inStaffChat = new HashSet();
    private final Map<UUID, BukkitTask> activeTeleports = new HashMap();
    private boolean lockdownActive = false;
    // Staff currently looking at someone's /invsee or /enderchest without edit permission.
    private final Set<UUID> invseeReadOnly = new HashSet<>();

    // users index (data.yml -> users:)
    private final Map<String, UUID> nameIndex = new HashMap<>();  // lowercase name -> uuid
    private final Map<UUID, String> uuidIndex = new HashMap<>();  // uuid -> real-cased name
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    private static final String INVSEE_PREFIX = "§8Inventory: ";
    // Vanilla private-message commands (checked for the "private messages OFF" setting and vanish).
    private static final Set<String> PRIVATE_MESSAGE_COMMANDS = new HashSet<>(Arrays.asList(
            "msg", "message", "tell", "w", "whisper"
    ));

    // Discord status-embed state.
    private long serverStartTime;
    private BukkitTask statusTask;
    private DiscordWebhooks discord;   // all Discord webhooks live in DiscordWebhooks.java

    // Commands a muted player should NOT be able to use to get around chat mute.
    private static final Set<String> MUTED_BLOCKED_COMMANDS = new HashSet<>(Arrays.asList(
            "msg", "message", "tell", "w", "whisper", "r", "reply", "me"
    ));

    // Single permission gating the entire TPA system (tpa, tpahere, tpaccept, tpdeny, tpacancel).
    private static final String TPA_PERMISSION = "unstableffa.tpa";

    // ---------- permission nodes (see plugin.yml for the full list + the moderator/admin groups) ----------
    // Moderators get PERM_PUNISH. IP addresses and alt accounts are split off so moderators can't see them.
    private static final String PERM_PUNISH = "unstableffa.punish";          // ban/mute/kick/warn/history/notes...
    private static final String PERM_ALTS = "unstableffa.alts";              // see possible alt accounts (names only)
    private static final String PERM_IP_VIEW = "unstableffa.ip.view";        // see real IP addresses
    private static final String PERM_IPBAN = "unstableffa.ipban";            // /ipban + /unipban
    private static final String PERM_STAFF_ALERTS = "unstableffa.alerts";    // join/report/filter/note alerts

    // Simple Voice Chat: muted players are also muted in voice chat.
    // uuid -> mute expiry (millis, -1 = permanent). Read from the voice chat thread, so it's concurrent.
    private final Map<UUID, Long> voiceMuteCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> voiceNotifyCooldown = new ConcurrentHashMap<>();
    private boolean voiceChatHooked = false;

    // Chat cooldown, command cooldowns, anti-cuss and the slur filter live in ChatGuard.java.
    ChatGuard chatGuard;
    // /unstableffa config lives in ConfigCommand.java.
    private ConfigCommand configCommand;

    // Bigger systems, each in its own class (all can be switched on/off in config.yml).
    private LiveLink liveLink;
    // /announce lives in Announcer.java.
    private final Announcer announcer = new Announcer(this);
    // /commandspy lives in CommandSpy.java.
    private CommandSpy commandSpy;
    // /modmode lives in ModMode.java; commands.disabled / commands.override live in CommandManager.java.
    private ModMode modMode;
    private CommandManager commandManager;
    // /leaderboard lives in Leaderboards.java; /rules and /guide live in RulesGuide.java.
    private Leaderboards leaderboards;
    private RulesGuide rulesGuide;
    private Warps warps;
    private StatsMenu statsMenu;
    private final Killstreak killstreak = new Killstreak(this);
    private final PunishLimits punishLimits = new PunishLimits(this);
    private final TpaGui tpaGui = new TpaGui(this);
    private Welcome welcome;
    private ZenithMenu zenithMenu;
    private final DiscordCommand discordCommand = new DiscordCommand(this);
    private final DebugInfo debugInfo = new DebugInfo(this);
    private FirstJoin firstJoin;
    private CombatTag combat;
    // Ban embeds (Ban ID + Appeal button, edited to UNBANNED) for the punishment webhook live in BanLog.java.
    private final BanLog banLog = new BanLog(this);
    // /lagfinder and the automatic low-TPS alerts live in LagFinder.java.
    private final LagFinder lagFinder = new LagFinder(this);
    private AutoBroadcaster broadcaster;
    private BackupManager backups;
    private ChatLogger chatLogger;
    private PlaytimeRewards playtimeRewards;
    private QuestManager quests;
    private BanEvasion banEvasion;
    private StaffActivity staffActivity;
    private ScreenShare screenShare;

    // Status embed: highest player count since the last restart.
    private int peakPlayers = 0;

    // ---- settings live in the configs folder (see Configs); getConfig() is the combined view ----
    private Configs configs;

    @Override
    public FileConfiguration getConfig() {
        FileConfiguration m = this.configs == null ? null : this.configs.merged();
        return m != null ? m : super.getConfig();
    }

    @Override
    public void reloadConfig() {
        if (this.configs != null) {
            this.configs.rebuild();
        } else {
            super.reloadConfig();
        }
    }

    @Override
    public void saveConfig() {
        if (this.configs != null) {
            this.configs.save();
        }
    }

    @Override
    public void saveDefaultConfig() {
        // the default files are created by Configs.setup()
    }

    public void onEnable() {
        this.configs = new Configs(this); // all settings files live in plugins/UnstableFFA/configs/
        this.configs.setup();
        this.chatGuard = new ChatGuard(this);
        this.configCommand = new ConfigCommand(this);
        this.liveLink = new LiveLink(this);
        this.broadcaster = new AutoBroadcaster(this);
        this.backups = new BackupManager(this);
        this.chatLogger = new ChatLogger(this);
        this.playtimeRewards = new PlaytimeRewards(this);
        this.quests = new QuestManager(this);
        this.banEvasion = new BanEvasion(this);
        this.staffActivity = new StaffActivity(this);
        this.screenShare = new ScreenShare(this);
        this.loadFiles();
        this.lockdownActive = this.getConfig().getBoolean("lockdown-active", false);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.commandSpy = new CommandSpy(this);
        this.lagFinder.start();
        this.getServer().getPluginManager().registerEvents(this.commandSpy, this);
        this.modMode = new ModMode(this);
        this.getServer().getPluginManager().registerEvents(this.modMode, this);
        this.leaderboards = new Leaderboards(this);
        this.getServer().getPluginManager().registerEvents(this.leaderboards, this);
        this.leaderboards.start();
        this.rulesGuide = new RulesGuide(this);
        this.getServer().getPluginManager().registerEvents(this.rulesGuide, this);
        this.warps = new Warps(this);
        this.getServer().getPluginManager().registerEvents(this.warps, this);
        this.statsMenu = new StatsMenu(this);
        this.getServer().getPluginManager().registerEvents(this.statsMenu, this);
        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZenithPlaceholders.hook(this); // %zenith_killstreak% and friends
        }
        this.welcome = new Welcome(this);
        this.getServer().getPluginManager().registerEvents(this.welcome, this);
        this.zenithMenu = new ZenithMenu(this);
        this.getServer().getPluginManager().registerEvents(this.zenithMenu, this);
        this.firstJoin = new FirstJoin(this);
        this.getServer().getPluginManager().registerEvents(this.firstJoin, this);
        this.getServer().getPluginManager().registerEvents(new ChatExtras(this), this);
        this.combat = new CombatTag(this);
        this.getServer().getPluginManager().registerEvents(this.combat, this);
        this.getServer().getPluginManager().registerEvents(new WindchargeTech(this), this);
        this.getServer().getPluginManager().registerEvents(new ProjectileHitbox(this), this);
        this.combat.reload();
        this.commandManager = new CommandManager(this);
        this.getServer().getPluginManager().registerEvents(this.commandManager, this);
        this.getServer().getPluginManager().registerEvents(this.chatLogger, this);
        this.getServer().getPluginManager().registerEvents(this.playtimeRewards, this);
        this.getServer().getPluginManager().registerEvents(this.punishLimits, this);
        this.getServer().getPluginManager().registerEvents(this.tpaGui, this);
        this.getServer().getPluginManager().registerEvents(this.quests, this);
        this.getServer().getPluginManager().registerEvents(this.screenShare, this);
        this.backups.startup();
        String[] cmds = new String[]{"unstableffa", "punish", "tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tpauto", "tpatoggle", "tpall", "sethome", "home", "delhome", "listhomes", "spawn", "setspawn", "warp", "setwarp", "delwarp", "adminlist", "fly", "flyspeed", "walkspeed", "god", "vanish", "sudo", "ban", "tempban", "unban", "mute", "unmute", "kick", "warn", "ping", "msg", "trash", "freeze", "socialspy", "staffchat", "sc", "clearchat", "report", "reports", "openwarpgui", "lockdown", "serverstatus",
                "history", "unwarn", "pardonoffense", "note", "alts", "ipban", "unipban", "clearhistory", "staffstats", "invsee", "enderchest", "zenithsettings",
                "seen", "whois", "playtime", "mutelist", "nick", "live", "forcetp", "tp", "forcetphere",
                "mutechat", "clearlag", "staff", "kit", "block", "unblock", "ignore", "unignore", "near",
                "livelink", "quests", "rewards", "chatlog", "banevasion", "staffactivity", "ss", "announce", "announcerestart", "commandspy", "banlog", "lagfinder", "modmode", "leaderboard", "rules", "guide", "warps", "stats", "welcome", "staffguide", "combat", "zenithmenu", "discord", "kickall", "punishlimits"};

        for(String c : cmds) {
            PluginCommand pc = this.getCommand(c);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }

        // commands.disabled / commands.override from config.yml
        this.commandManager.apply();

        this.serverStartTime = System.currentTimeMillis();

        // Keeps open /invsee views in sync with the target's real inventory.
        (new BukkitRunnable() {
            public void run() {
                zenithEssentials.this.tickInvsee();
            }
        }).runTaskTimer(this, 10L, 5L);

        // Refreshes open /live views twice a second.
        (new BukkitRunnable() {
            public void run() {
                zenithEssentials.this.tickLive();
            }
        }).runTaskTimer(this, 20L, 10L);

        // Discord "server status" embed: posts an embed on startup and keeps editing the
        // same message on an interval, so the Discord channel always shows a live status
        // (Online / Maintenance / Offline), player count, and staff-online count/names.
        this.discord = new DiscordWebhooks(this);
        if (this.getConfig().getBoolean("discord-webhook.status-embed.enabled", false)) {
            int interval = Math.max(1, this.getConfig().getInt("discord-webhook.status-embed.update-interval-minutes", 5));
            this.updateStatusEmbedAsync(false);
            // Runs on the main thread so reading players/permissions is safe; the HTTP call itself is async.
            this.statusTask = (new BukkitRunnable() {
                public void run() {
                    zenithEssentials.this.updateStatusEmbedAsync(false);
                }
            }).runTaskTimer(this, 20L * 60 * interval, 20L * 60 * interval);
        }

        // Automatic /clearlag timer.
        this.startClearLag();

        // Simple Voice Chat: muted players can't talk in voice chat either.
        if (this.getConfig().getBoolean("voicechat.mute-with-chat", true)
                && this.getServer().getPluginManager().getPlugin("voicechat") != null) {
            this.hookVoiceChat();
        }
    }

    @Override
    public void onDisable() {
        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            ZenithPlaceholders.unhook();
        }
        // Plugin shutdown logic
        if (this.modMode != null) {
            this.modMode.shutdown(); // everyone in /modmode gets their items back
        }
        if (this.statsMenu != null) {
            this.statsMenu.save(); // killstreaks, swings and hits
        }
        if (this.combat != null) {
            this.combat.shutdown(); // logout bodies go away, nobody stays tagged
        }
        for (UUID fid : new ArrayList<>(this.frozen)) { // nobody stays stuck with walk speed 0
            Player fp = Bukkit.getPlayer(fid);
            if (fp != null) {
                this.setFrozen(fp, false, true);
            }
        }
        if (this.quests != null) {
            this.quests.saveAll();
            this.staffActivity.save();
            this.chatLogger.shutdown();
        }
        if (this.statusTask != null) {
            this.statusTask.cancel();
            this.statusTask = null;
        }

        // Best-effort synchronous update so the Discord embed flips to "Offline" as the
        // server actually goes down. The Bukkit scheduler is unavailable during onDisable,
        // so this runs on the main thread directly rather than via BukkitRunnable.
        if (this.getConfig().getBoolean("discord-webhook.status-embed.enabled", false)) {
            try {
                this.updateStatusEmbed(true);
            } catch (Exception ignored) {
            }
        }
    }

    public void loadFiles() {
        this.dataFile = new File(this.getDataFolder(), "data.yml");
        this.msgFile = Configs.file(this, "messages.yml");
        if (!this.dataFile.exists()) {
            try {
                this.dataFile.createNewFile();
            } catch (IOException var2) {
            }
        }

        if (!this.msgFile.exists()) {
            Configs.saveDefault(this, "messages.yml");
        }

        this.data = this.loadDataSafely();
        this.messages = YamlConfiguration.loadConfiguration(this.msgFile);

        this.data.options().setHeader(Arrays.asList(
                "UnstableFFA data file",
                "users: every player seen, by username (uuid inside).",
                "Other sections are keyed by UUID - look the name up under 'users'."));
        this.migrateUsers();
        this.rebuildVoiceMuteCache();
        this.loadRelations();
        this.loadKits();
        if (this.chatGuard != null) {
            this.chatGuard.reload();
        }
        if (this.quests != null) {
            this.broadcaster.reload();
            this.backups.reload();
            this.chatLogger.reload();
            this.playtimeRewards.reload();
            this.quests.reload();
            this.staffActivity.reload();
            this.screenShare.reload();
        }
    }

    private String translate(String msg) {
        if (msg != null && !msg.isEmpty()) {
            Pattern p = Pattern.compile("&#[a-fA-F0-9]{6}");

            for(Matcher m = p.matcher(msg); m.find(); m = p.matcher(msg)) {
                String c = msg.substring(m.start(), m.end());
                msg = msg.replace(c, ChatColor.of(c.substring(1)).toString());
            }

            return org.bukkit.ChatColor.translateAlternateColorCodes('&', msg);
        } else {
            return "";
        }
    }

    private String getMsg(String path) {
        String pre = this.translate(this.messages.getString("prefix", "<gradient:#FF7518:#FFFFFF>&lUnst</gradient><gradient:#FFFFFF:#FF7518>&lable</gradient>&f&lEssentials &8»"));
        String msg = this.messages.getString(path);
        return msg == null ? pre + " §cMissing: " + path : pre + " " + this.translate(msg);
    }

    // Reads a formatting string from messages.yml with a fallback default, and applies
    // {sender}/{target}/{message} placeholders. Colors are translated.
    private String formatMsg(String path, String def, String sender, String target, String message) {
        String fmt = this.messages.getString(path, def);
        String out = this.translate(fmt);
        if (sender != null) {
            out = out.replace("{sender}", sender);
        }
        if (target != null) {
            out = out.replace("{target}", target);
        }
        if (message != null) {
            out = out.replace("{message}", message);
        }
        return out;
    }

    // Generic version of formatMsg: reads a template from messages.yml (falling back to
    // "def" if the key is missing), translates colors, then replaces every {key} in the
    // template with its value from "placeholders". Used for punishment messages, which
    // need a variable set of tokens ({reason}, {duration}, {staff}, {player}, ...).
    // Reloads config.yml, messages.yml, data.yml, kits.yml and restarts timers.
    // Used by /unstableffa reload and /unstableffa config.
    void reloadAll() {
        this.reloadConfig();
        this.loadFiles();
        this.startClearLag();
        this.lockdownActive = this.getConfig().getBoolean("lockdown-active");
        if (this.combat != null) {
            this.combat.reload(); // combat.enabled: false stops everything right away
        }
        if (this.commandManager != null) {
            this.commandManager.reload(); // commands.disabled / commands.override work right away
        }
    }

    // Used by ChatGuard (and other helper classes) to read a messages.yml line with placeholders.
    String fmt(String path, String def, Map<String, String> placeholders) {
        return this.formatMsgMap(path, def, placeholders);
    }

    private String formatMsgMap(String path, String def, Map<String, String> placeholders) {
        String fmt = this.messages.getString(path, def);
        String out = this.translate(fmt);
        for (Map.Entry<String, String> en : placeholders.entrySet()) {
            String value = en.getValue() == null ? "" : en.getValue();
            out = out.replace("{" + en.getKey() + "}", value);
        }
        return out;
    }

    // Returns true if the player is currently muted, clearing the mute first if it has expired.
    private boolean isMuted(Player p) {
        String path = "mutes." + p.getUniqueId();
        if (this.data.contains(path)) {
            long exp = this.data.getLong(path);
            if (exp != -1L && exp <= System.currentTimeMillis()) {
                this.data.set(path, (Object) null);
                this.voiceMuteCache.remove(p.getUniqueId());
                this.saveD();
                return false;
            }
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Simple Voice Chat mute
    //
    // Hooked purely with reflection, so the plugin compiles and runs WITHOUT the voice chat
    // API on the build path, and still works fine on servers that don't have the mod.
    // Every microphone packet from a muted player is cancelled, so /mute, /tempban-style
    // mutes and /punish <player> <MUTE category> all silence voice chat too.
    // =====================================================================================

    private void rebuildVoiceMuteCache() {
        this.voiceMuteCache.clear();
        ConfigurationSection ms = this.data.getConfigurationSection("mutes");
        if (ms == null) {
            return;
        }
        for (String key : ms.getKeys(false)) {
            try {
                this.voiceMuteCache.put(UUID.fromString(key), ms.getLong(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // Thread-safe: called from the voice chat network thread.
    private boolean isVoiceMuted(UUID id) {
        Long exp = this.voiceMuteCache.get(id);
        if (exp == null) {
            return false;
        }
        if (exp != -1L && exp <= System.currentTimeMillis()) {
            this.voiceMuteCache.remove(id);
            return false;
        }
        return true;
    }

    private void hookVoiceChat() {
        try {
            ClassLoader cl = this.getServer().getPluginManager().getPlugin("voicechat").getClass().getClassLoader();
            final Class<?> serviceClass = Class.forName("de.maxhenkel.voicechat.api.BukkitVoicechatService", true, cl);
            final Class<?> pluginClass = Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin", true, cl);
            final Class<?> registrationClass = Class.forName("de.maxhenkel.voicechat.api.events.EventRegistration", true, cl);
            final Class<?> micEventClass = Class.forName("de.maxhenkel.voicechat.api.events.MicrophonePacketEvent", true, cl);
            final Class<?> connectionClass = Class.forName("de.maxhenkel.voicechat.api.VoicechatConnection", true, cl);
            final Class<?> serverPlayerClass = Class.forName("de.maxhenkel.voicechat.api.ServerPlayer", true, cl);

            final java.lang.reflect.Method getConnection = micEventClass.getMethod("getSenderConnection");
            final java.lang.reflect.Method getPlayer = connectionClass.getMethod("getPlayer");
            final java.lang.reflect.Method getUuid = serverPlayerClass.getMethod("getUuid");
            final java.lang.reflect.Method cancel = micEventClass.getMethod("cancel");
            final java.lang.reflect.Method registerEvent = registrationClass.getMethod("registerEvent", Class.class, java.util.function.Consumer.class);

            final java.util.function.Consumer<Object> onMic = event -> {
                try {
                    Object conn = getConnection.invoke(event);
                    if (conn == null) {
                        return;
                    }
                    Object sp = getPlayer.invoke(conn);
                    if (sp == null) {
                        return;
                    }
                    UUID id = (UUID) getUuid.invoke(sp);
                    if (id != null && this.isVoiceMuted(id)) {
                        cancel.invoke(event);
                        this.notifyVoiceMuted(id);
                    }
                } catch (Exception ignored) {
                }
            };

            Object voicechatPlugin = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{pluginClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getPluginId":
                        return "unstableffa";
                    case "registerEvents":
                        registerEvent.invoke(args[0], micEventClass, onMic);
                        return null;
                    case "initialize":
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "UnstableFFA voice chat hook";
                    default:
                        return null;
                }
            });

            Object service = this.getServer().getServicesManager().load(serviceClass);
            if (service == null) {
                this.getLogger().warning("Simple Voice Chat found, but its API service isn't available - voice mutes are off.");
                return;
            }
            serviceClass.getMethod("registerPlugin", pluginClass).invoke(service, voicechatPlugin);
            this.voiceChatHooked = true;
            this.getLogger().info("Hooked into Simple Voice Chat - muted players are muted in voice chat too.");
        } catch (Throwable t) {
            this.getLogger().warning("Could not hook into Simple Voice Chat: " + t);
        }
    }

    // Tells a muted player (at most every 5 seconds) that their voice isn't going through.
    private void notifyVoiceMuted(UUID id) {
        long now = System.currentTimeMillis();
        Long last = this.voiceNotifyCooldown.get(id);
        if (last != null && now - last < 5000L) {
            return;
        }
        this.voiceNotifyCooldown.put(id, now);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            Long exp = this.voiceMuteCache.get(id);
            String left = exp == null ? "" : (exp == -1L ? "permanent" : this.formatUptime(exp - System.currentTimeMillis()) + " left");
            String msg = this.formatMsgMap("voicechat-muted-actionbar", "<gradient:#FF7518:#FFFFFF>&lYou are</gradient><gradient:#FFFFFF:#FF7518>&l muted</gradient> &8» &7voice chat is disabled &8(&f{time}&8)",
                    java.util.Collections.singletonMap("time", left));
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        });
    }

    // config.yml "owner" can be one name or a list of names. These players can't be punished or IP banned.
    private boolean isOwner(String name) {
        if (name == null) {
            return false;
        }
        List<String> owners = new ArrayList<>();
        if (this.getConfig().isList("owner")) {
            owners.addAll(this.getConfig().getStringList("owner"));
        } else {
            owners.add(this.getConfig().getString("owner", ""));
        }
        for (String o : owners) {
            if (o != null && o.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================================
    // v2: settings, punishment history, alts, IP bans, notes, warn escalation, chat filter
    // =====================================================================================

    // Is this UUID currently muted? Clears the mute first if it has expired.
    private boolean isMutedUuid(UUID id) {
        String path = "mutes." + id;
        if (!this.data.contains(path)) {
            return false;
        }
        long exp = this.data.getLong(path);
        if (exp != -1L && exp <= System.currentTimeMillis()) {
            this.data.set(path, (Object) null);
            this.voiceMuteCache.remove(id);
            this.saveD();
            return false;
        }
        return true;
    }

    // ---------- per-player settings (/zenithsettings) ----------

    private boolean getSetting(UUID id, String key, boolean def) {
        return this.data.getBoolean("settings." + id + "." + key, def);
    }

    private void setSetting(UUID id, String key, boolean value) {
        this.data.set("settings." + id + "." + key, value);
        this.saveD();
    }

    private boolean isMsgDisabled(Player p) {
        return this.getSetting(p.getUniqueId(), "msg-disabled", false);
    }

    // ---------- vanish helpers ----------

    private String fakeLeaveMessage(Player p) {
        String q = this.messages.getString("leave-message");
        if (q != null && !q.equalsIgnoreCase("none")) {
            return this.translate(q).replace("{player}", p.getName());
        }
        return "§e" + p.getName() + " left the game";
    }

    private String fakeJoinMessage(Player p) {
        String j = this.messages.getString("join-message");
        if (j != null && !j.equalsIgnoreCase("none")) {
            return this.translate(j).replace("{player}", p.getName());
        }
        return "§e" + p.getName() + " joined the game";
    }

    private void refreshStatusEmbedIfTracking() {
        if (this.getConfig().getBoolean("discord-webhook.status-embed.update-on-join-quit", false)) {
            this.updateStatusEmbedAsync(false);
        }
    }

    // Online players you can see plus every offline player the plugin has seen before.
    // Vanished players are left out for people who can't see them.
    private List<String> suggestableNames(CommandSender viewer) {
        Set<String> out = new HashSet<>(this.knownNames());
        if (!viewer.hasPermission("unstableffa.vanish")) {
            for (UUID vid : this.vanished) {
                Player v = Bukkit.getPlayer(vid);
                if (v != null) {
                    out.remove(v.getName());
                }
            }
        }
        out.addAll(this.visibleOnlineNames(viewer));
        return new ArrayList<>(out);
    }

    boolean isVanishedUuid(UUID id) {
        return this.vanished.contains(id);
    }

    private List<String> visibleOnlineNames(CommandSender viewer) {
        List<String> out = new ArrayList<>();
        boolean seeAll = viewer.hasPermission("unstableffa.vanish");
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (seeAll || !this.vanished.contains(pl.getUniqueId())) {
                out.add(pl.getName());
            }
        }
        return out;
    }

    // ---------- home limits ----------

    // -1 = unlimited. Uses unstableffa.homes.<number> permissions (highest wins),
    // OP / unstableffa.homes.unlimited = unlimited, otherwise "default-home-limit" from config.yml.
    private int getHomeLimit(Player p) {
        if (p.isOp() || p.hasPermission("unstableffa.homes.unlimited")) {
            return -1;
        }
        int best = -1;
        boolean found = false;
        String prefix = "unstableffa.homes.";
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission().toLowerCase();
            if (perm.startsWith(prefix)) {
                try {
                    int v = Integer.parseInt(perm.substring(prefix.length()));
                    if (!found || v > best) {
                        best = v;
                        found = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return found ? best : this.getConfig().getInt("default-home-limit", -1);
    }

    // ---------- punishment log ----------
    // Log line format: timestamp|ACTION|duration|staff|reason|banId|removedBy

    private String[] parseEntry(String line) {
        String[] raw = line.split("\\|", 7);
        String[] p = new String[]{"0", "?", "-", "?", "N/A", "-", "-"};
        for (int i = 0; i < raw.length && i < 7; i++) {
            p[i] = raw[i];
        }
        return p;
    }

    private String clean(String s, String def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        return s.replace("|", "/").replace("\n", " ");
    }

    private String newBanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private String ipKey(String ip) {
        return ip.replace('.', '_').replace(':', '-');
    }

    private void recordPunishment(OfflinePlayer t, String fallbackName, String action, String duration, String staff, String reason, String banId) {
        String id = t.getUniqueId().toString();
        String name = this.displayName(t, fallbackName);
        List<String> log = this.data.getStringList("punishlog." + id);
        log.add(System.currentTimeMillis() + "|" + action + "|" + this.clean(duration, "-") + "|" + this.clean(staff, "?") + "|"
                + this.clean(reason, "N/A") + "|" + this.clean(banId, "-") + "|-");
        this.data.set("punishlog." + id, log);
        this.data.set("punishnames." + id, name);
        if (this.staffActivity != null && !action.equals("NOTE")) {
            this.staffActivity.onPunish(staff);
        }
        this.banLog.onRecord(t, name, action, duration, staff, reason, banId);
        this.saveD();
    }

    // returns {bans, mutes, kicks, activeWarns, removedWarns, notes}
    private int[] getStats(UUID id) {
        int[] r = new int[6];
        for (String line : this.data.getStringList("punishlog." + id)) {
            String[] p = this.parseEntry(line);
            switch (p[1]) {
                case "BAN":
                case "TEMPBAN":
                case "IPBAN":
                    r[0]++;
                    break;
                case "MUTE":
                    r[1]++;
                    break;
                case "KICK":
                    r[2]++;
                    break;
                case "WARN":
                    if (p[6].equals("-")) {
                        r[3]++;
                    } else {
                        r[4]++;
                    }
                    break;
                case "NOTE":
                    r[5]++;
                    break;
                default:
                    break;
            }
        }
        return r;
    }

    private String historySummary(UUID id) {
        int[] s = this.getStats(id);
        return s[0] + " bans, " + s[1] + " mutes, " + s[2] + " kicks, " + s[3] + " active warns";
    }

    private String nameOf(String uuidStr) {
        String n = this.data.getString("punishnames." + uuidStr);
        if (n == null) {
            n = this.data.getString("playernames." + uuidStr);
        }
        return n == null ? uuidStr.substring(0, 8) : n;
    }

    // Every player name we have ever seen (joined since the update, or punished).
    private Set<String> knownNames() {
        Set<String> out = new HashSet<>();
        for (String sec : new String[]{"playernames", "punishnames"}) {
            ConfigurationSection cs = this.data.getConfigurationSection(sec);
            if (cs != null) {
                for (String k : cs.getKeys(false)) {
                    String nm = this.data.getString(sec + "." + k);
                    if (nm != null) {
                        out.add(nm);
                    }
                }
            }
        }
        out.addAll(this.uuidIndex.values());
        return out;
    }

    private String findUuidByBanId(String id) {
        ConfigurationSection ps = this.data.getConfigurationSection("punishlog");
        if (ps == null) {
            return null;
        }
        for (String key : ps.getKeys(false)) {
            for (String line : this.data.getStringList("punishlog." + key)) {
                if (this.parseEntry(line)[5].equalsIgnoreCase(id)) {
                    return key;
                }
            }
        }
        return null;
    }

    // ---------- warns ----------

    private boolean removeWarnAt(UUID id, int index, String staff) {
        List<String> log = this.data.getStringList("punishlog." + id);
        if (index < 0 || index >= log.size()) {
            return false;
        }
        String[] p = this.parseEntry(log.get(index));
        if (!p[1].equals("WARN") || !p[6].equals("-")) {
            return false;
        }
        p[6] = this.clean(staff, "?");
        log.set(index, String.join("|", p));
        this.data.set("punishlog." + id, log);
        this.saveD();
        return true;
    }

    private int latestActiveWarnIndex(UUID id) {
        List<String> log = this.data.getStringList("punishlog." + id);
        for (int i = log.size() - 1; i >= 0; --i) {
            String[] p = this.parseEntry(log.get(i));
            if (p[1].equals("WARN") && p[6].equals("-")) {
                return i;
            }
        }
        return -1;
    }

    // "Warn #n" counts every warn (removed ones too) from oldest = 1, same numbers as in /history.
    private int nthWarnIndex(UUID id, int n) {
        List<String> log = this.data.getStringList("punishlog." + id);
        int count = 0;
        for (int i = 0; i < log.size(); ++i) {
            if (this.parseEntry(log.get(i))[1].equals("WARN")) {
                count++;
                if (count == n) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean performUnwarn(CommandSender s, OfflinePlayer t, String tn, int idx) {
        if (!this.removeWarnAt(t.getUniqueId(), idx, s.getName())) {
            return false;
        }
        this.broadcastPunish(tn, "Warning Removed", s.getName(), "Staff Action");
        this.sendDiscordWebhook("unwarn", "Warning Removed", tn, s.getName(), "Warn removed", null, this.historySummary(t.getUniqueId()));
        if (t.isOnline() && t.getPlayer() != null) {
            t.getPlayer().sendMessage("§aOne of your warnings was removed. Active warnings: §f" + this.getStats(t.getUniqueId())[3]);
        }
        return true;
    }

    // Automatic punishment when a player reaches a number of ACTIVE warns.
    // config.yml: warn-escalation: { 3: "mute 1h", 5: "tempban 1d" }  (actions: mute, tempban, ban, kick)
    private void checkWarnEscalation(OfflinePlayer t, String name) {
        int active = this.getStats(t.getUniqueId())[3];
        String rule = null;
        ConfigurationSection es = this.getConfig().getConfigurationSection("warn-escalation");
        if (es != null) {
            rule = es.getString(String.valueOf(active));
        } else if (active == 3) {
            rule = "mute 1h";
        } else if (active == 5) {
            rule = "tempban 1d";
        }
        if (rule == null || rule.trim().isEmpty()) {
            return;
        }
        String[] parts = rule.trim().split("\\s+");
        String action = parts[0].toLowerCase();
        String dur = parts.length > 1 ? parts[1] : "1h";
        CommandSender console = Bukkit.getConsoleSender();
        if (action.equals("mute") || action.equals("ban") || action.equals("tempban")) {
            this.handlePunishment(console, action, new String[]{name, dur, "Reached", String.valueOf(active), "warnings"}, "AutoMod");
        } else if (action.equals("kick")) {
            this.handlePunishment(console, "kick", new String[]{name, "Reached", String.valueOf(active), "warnings"}, "AutoMod");
        }
    }

    // ---------- chat filter ----------

    // Runs on the main thread (scheduled from the async chat event).
    private void autoWarn(Player p, String reason) {
        this.recordPunishment(p, p.getName(), "WARN", "-", "AutoMod", reason, null);
        int active = this.getStats(p.getUniqueId())[3];
        p.sendMessage("§c§lWatch your language! §7You received a warning §8(§f" + active + " active§8)");
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission(PERM_STAFF_ALERTS) && this.wantsAlert(st)) {
                st.sendMessage("§8[§cFilter§8] §f" + p.getName() + " §7was auto-warned §8(§f" + active + " active warns§8)");
            }
        }
        this.sendDiscordWebhook("warn", "Warning (AutoMod)", p.getName(), "AutoMod", reason, null, this.historySummary(p.getUniqueId()));
        this.checkWarnEscalation(p, p.getName());
    }

    // ---------- alts / IP tracking ----------

    private void trackPlayer(Player p) {
        String id = p.getUniqueId().toString();
        boolean dirty = false;
        if (!p.getName().equals(this.data.getString("playernames." + id))) {
            this.data.set("playernames." + id, p.getName());
            dirty = true;
        }
        if (p.getAddress() != null && p.getAddress().getAddress() != null) {
            String ip = p.getAddress().getAddress().getHostAddress();
            List<String> ips = this.data.getStringList("playerips." + id);
            if (!ips.contains(ip)) {
                ips.add(ip);
                while (ips.size() > 10) {
                    ips.remove(0);
                }
                this.data.set("playerips." + id, ips);
                dirty = true;
            }
        }
        if (dirty) {
            this.saveD();
        }
    }

    // Other accounts that have joined from any of this player's IPs.
    private Map<UUID, String> findAlts(UUID id) {
        Map<UUID, String> out = new LinkedHashMap<>();
        Set<String> mine = new HashSet<>(this.data.getStringList("playerips." + id));
        if (mine.isEmpty()) {
            return out;
        }
        ConfigurationSection cs = this.data.getConfigurationSection("playerips");
        if (cs == null) {
            return out;
        }
        for (String key : cs.getKeys(false)) {
            if (key.equals(id.toString())) {
                continue;
            }
            for (String ip : this.data.getStringList("playerips." + key)) {
                if (mine.contains(ip)) {
                    try {
                        out.put(UUID.fromString(key), this.nameForUuid(key));
                    } catch (IllegalArgumentException ignored) {
                    }
                    break;
                }
            }
        }
        return out;
    }

    private String altTags(UUID id, String name) {
        StringBuilder sb = new StringBuilder();
        if (Bukkit.getPlayer(id) != null) {
            sb.append(" §a[ONLINE]");
        }
        if (Bukkit.getBanList(Type.NAME).isBanned(name)) {
            sb.append(" §c[BANNED]");
        }
        if (this.isMutedUuid(id)) {
            sb.append(" §6[MUTED]");
        }
        return sb.toString();
    }

    private void staffJoinAlert(Player jp) {
        UUID id = jp.getUniqueId();
        int bans = this.getStats(id)[0];
        int threshold = this.getConfig().getInt("join-alert-threshold", 3);
        List<String> bannedAlts = new ArrayList<>();
        for (Map.Entry<UUID, String> en : this.findAlts(id).entrySet()) {
            if (Bukkit.getBanList(Type.NAME).isBanned(en.getValue())) {
                bannedAlts.add(en.getValue());
            }
        }
        if (bans < threshold && bannedAlts.isEmpty()) {
            return;
        }
        // Two versions: staff without the alts permission only hear about past bans.
        StringBuilder base = new StringBuilder("§8[§c§lALERT§8] §f" + jp.getName() + " §7joined");
        if (bans >= threshold) {
            base.append(" with §c").append(bans).append(" §7past bans");
        }
        StringBuilder full = new StringBuilder(base);
        if (!bannedAlts.isEmpty()) {
            full.append(bans >= threshold ? " §7and" : "").append(" §7shares an IP with banned: §c").append(String.join("§7, §c", bannedAlts));
        }
        String tail = " §8(§f/history " + jp.getName() + "§8)";
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.equals(jp) || !st.hasPermission(PERM_STAFF_ALERTS) || !this.wantsAlert(st)) {
                continue;
            }
            if (st.hasPermission(PERM_ALTS)) {
                st.sendMessage(full + tail);
            } else if (bans >= threshold) {
                st.sendMessage(base + tail);
            }
        }
    }

    // ---------- ban screen / broadcast ----------

    private String banFooter(String banId) {
        String appeal = this.getConfig().getString("appeal-info", "Appeal on our Discord.");
        StringBuilder sb = new StringBuilder();
        if (banId != null) {
            sb.append("\n\n&7Ban ID: &f#").append(banId);
        }
        if (appeal != null && !appeal.isEmpty()) {
            sb.append(banId == null ? "\n\n" : "\n").append("&7").append(appeal);
        }
        return this.translate(sb.toString());
    }

    private void broadcastPunish(String target, String label, String admin, String reason) {
        String style = this.messages.getString("punish-style", "&8[&#B15CFFPunish&8] &f{target} &7» &f{action} &8| &7By &f{admin} &8| &7{reason}");
        Bukkit.broadcastMessage(this.translate(style).replace("{target}", target).replace("{action}", label)
                .replace("{admin}", admin).replace("{reason}", reason));
    }

    private String formatDate(String ts) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(Long.parseLong(ts)));
        } catch (NumberFormatException ex) {
            return "Unknown";
        }
    }

    private static final class HistEntry {
        final long ts;
        final String uuid;
        final String line;

        HistEntry(long ts, String uuid, String line) {
            this.ts = ts;
            this.uuid = uuid;
            this.line = line;
        }
    }

    // ---------- staff commands that also work from console ----------

    private void handleStaffCommand(CommandSender s, String n, String[] args) {
        // Every command has its own node; the moderator/admin groups in plugin.yml hand them out.
        String perm;
        switch (n) {
            case "alts":
                perm = PERM_ALTS;
                break;
            case "ipban":
            case "unipban":
                perm = PERM_IPBAN;
                break;
            default:
                perm = "unstableffa." + n; // unwarn, pardonoffense, note, clearhistory, staffstats
                break;
        }
        if (!s.hasPermission(perm)) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        String staff = s.getName();

        switch (n) {
            case "unwarn": {
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /unwarn <player> [warn number]");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                String tn = this.displayName(t, args[0]);
                int idx;
                if (args.length >= 2) {
                    int num;
                    try {
                        num = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        s.sendMessage("§cThe warn number must be a number (see /history " + tn + ").");
                        return;
                    }
                    idx = this.nthWarnIndex(t.getUniqueId(), num);
                } else {
                    idx = this.latestActiveWarnIndex(t.getUniqueId());
                }
                if (idx < 0 || !this.performUnwarn(s, t, tn, idx)) {
                    s.sendMessage("§c" + tn + " has no matching active warning.");
                }
                return;
            }
            case "pardonoffense": {
                if (args.length < 2) {
                    s.sendMessage("§cUsage: /pardonoffense <player> <category>  §7(lowers the /punish offense counter by 1)");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                String tn = this.displayName(t, args[0]);
                String catIn = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("_", " ");
                ConfigurationSection durSec = this.getConfig().getConfigurationSection("punishment-durations");
                String configKey = null;
                if (durSec != null) {
                    for (String k : durSec.getKeys(false)) {
                        if (k.equalsIgnoreCase(catIn)) {
                            configKey = k;
                            break;
                        }
                    }
                }
                if (configKey == null) {
                    s.sendMessage("§cInvalid reason category.");
                    return;
                }
                String path = "history." + t.getUniqueId() + "." + configKey.toLowerCase().replace(" ", "_");
                int cur = this.data.getInt(path, 0);
                if (cur <= 0) {
                    s.sendMessage("§c" + tn + " has no offenses in that category.");
                    return;
                }
                this.data.set(path, cur - 1);
                this.saveD();
                this.recordPunishment(t, args[0], "PARDON", "-", staff, configKey.replace("_", " ") + " offense " + cur + " -> " + (cur - 1), null);
                s.sendMessage("§a" + tn + "'s §f" + configKey.replace("_", " ") + " §aoffense counter is now §f" + (cur - 1) + "§a.");
                return;
            }
            case "note": {
                if (args.length < 2) {
                    s.sendMessage("§cUsage: /note <player> <text>");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                String tn = this.displayName(t, args[0]);
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                this.recordPunishment(t, args[0], "NOTE", "-", staff, text, null);
                s.sendMessage("§aNote added to §f" + tn + "§a. See it with /history " + tn);
                for (Player st : Bukkit.getOnlinePlayers()) {
                    if (!st.equals(s) && st.hasPermission(PERM_STAFF_ALERTS) && this.wantsAlert(st)) {
                        st.sendMessage("§8[§dNote§8] §f" + staff + " §7on §f" + tn + "§7: §d" + text);
                    }
                }
                return;
            }
            case "alts": {
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /alts <player>");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                String tn = this.displayName(t, args[0]);
                Map<UUID, String> alts = this.findAlts(t.getUniqueId());
                if (alts.isEmpty()) {
                    s.sendMessage("§aNo known alts for §f" + tn + "§a. §8(IPs are logged from when this update was installed)");
                    return;
                }
                s.sendMessage("§b§lPossible alts of " + tn + " §7(same IP):");
                for (Map.Entry<UUID, String> en : alts.entrySet()) {
                    s.sendMessage("§7- §f" + en.getValue() + this.altTags(en.getKey(), en.getValue()));
                }
                s.sendMessage("§8Shared IPs (family, school, VPN) can show innocent players too.");
                return;
            }
            case "ipban": {
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /ipban <player|ip> [reason]");
                    return;
                }
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("_", " ") : "Ban evasion";
                boolean rawIp = args[0].matches("\\d{1,3}(\\.\\d{1,3}){3}") || args[0].contains(":");
                OfflinePlayer t = null;
                String tn = null;
                Set<String> ips = new LinkedHashSet<>();
                if (rawIp) {
                    ips.add(args[0]);
                } else {
                    t = this.resolveOffline(args[0]);
                    tn = this.displayName(t, args[0]);
                    if (this.isOwner(tn)) {
                        s.sendMessage("§cYou cannot punish the server owner!");
                        return;
                    }
                    Player online = Bukkit.getPlayerExact(tn);
                    if (online != null && online.getAddress() != null && online.getAddress().getAddress() != null) {
                        ips.add(online.getAddress().getAddress().getHostAddress());
                    }
                    ips.addAll(this.data.getStringList("playerips." + t.getUniqueId()));
                }
                if (ips.isEmpty()) {
                    s.sendMessage("§cNo IP on record for that player (IPs are logged from when this update was installed).");
                    return;
                }
                String banId = this.newBanId();
                long now = System.currentTimeMillis();
                for (String ip : ips) {
                    this.data.set("ipbans." + this.ipKey(ip), ip + "|" + this.clean(staff, "?") + "|" + now + "|" + this.clean(reason, "Ban evasion") + "|" + banId);
                }
                this.saveD();
                String kickMsg = this.translate("&#D9381E&lYOU ARE IP BANNED\n&7Reason: &f" + reason) + this.banFooter(banId);
                for (Player pl : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                    if (pl.getAddress() != null && pl.getAddress().getAddress() != null && ips.contains(pl.getAddress().getAddress().getHostAddress())) {
                        pl.kickPlayer(kickMsg);
                    }
                }
                String shown = "an IP address";
                if (t != null) {
                    shown = tn;
                    if (!Bukkit.getBanList(Type.NAME).isBanned(tn)) {
                        Bukkit.getBanList(Type.NAME).addBan(tn, kickMsg, null, staff);
                    }
                    this.recordPunishment(t, args[0], "IPBAN", "Permanent", staff, reason, banId);
                }
                this.broadcastPunish(shown, "IP Ban", staff, reason);
                if (!this.banLog.handles("IPBAN")) {
                    this.sendDiscordWebhook("ipban", "IP Ban", shown, staff, reason, "Permanent", t == null ? null : this.historySummary(t.getUniqueId()));
                }
                s.sendMessage("§aIP banned §f" + ips.size() + " §aaddress(es). Ban ID: §f#" + banId);
                return;
            }
            case "unipban": {
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /unipban <player|ip>");
                    return;
                }
                boolean rawIp = args[0].matches("\\d{1,3}(\\.\\d{1,3}){3}") || args[0].contains(":");
                OfflinePlayer t = null;
                String tn = null;
                Set<String> ips = new LinkedHashSet<>();
                if (rawIp) {
                    ips.add(args[0]);
                } else {
                    t = this.resolveOffline(args[0]);
                    tn = this.displayName(t, args[0]);
                    ips.addAll(this.data.getStringList("playerips." + t.getUniqueId()));
                }
                int removed = 0;
                for (String ip : ips) {
                    String path = "ipbans." + this.ipKey(ip);
                    if (this.data.contains(path)) {
                        this.data.set(path, (Object) null);
                        removed++;
                    }
                }
                if (removed == 0) {
                    s.sendMessage("§cThat IP / player is not IP banned.");
                    return;
                }
                this.saveD();
                if (t != null) {
                    if (Bukkit.getBanList(Type.NAME).isBanned(tn)) {
                        Bukkit.getBanList(Type.NAME).pardon(tn);
                    }
                    this.recordPunishment(t, args[0], "UNBAN", "-", staff, "IP ban removed", null);
                }
                this.broadcastPunish(t == null ? "an IP address" : tn, "IP Unban", staff, "Staff Action");
                s.sendMessage("§aRemoved §f" + removed + " §aIP ban(s).");
                return;
            }
            case "clearhistory": {
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /clearhistory <player> confirm");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                String tn = this.displayName(t, args[0]);
                int entries = this.data.getStringList("punishlog." + t.getUniqueId()).size();
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    s.sendMessage("§eThis wipes §f" + entries + " §eentries and the /punish offense counters of §f" + tn + "§e. Type §f/clearhistory " + tn + " confirm §eto do it.");
                    return;
                }
                this.data.set("punishlog." + t.getUniqueId(), (Object) null);
                this.data.set("history." + t.getUniqueId(), (Object) null);
                this.saveD();
                s.sendMessage("§aCleared the history of §f" + tn + "§a (" + entries + " entries).");
                this.getLogger().info(staff + " cleared the punishment history of " + tn);
                return;
            }
            case "staffstats": {
                int days = 30;
                if (args.length >= 1) {
                    try {
                        days = Math.max(1, Integer.parseInt(args[0]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                long cutoff = System.currentTimeMillis() - days * 86400000L;
                // per staff: {bans, mutes, kicks, warns, pardons, lastTimestamp}
                Map<String, long[]> stats = new HashMap<>();
                ConfigurationSection ps = this.data.getConfigurationSection("punishlog");
                if (ps != null) {
                    for (String uuid : ps.getKeys(false)) {
                        for (String line : this.data.getStringList("punishlog." + uuid)) {
                            String[] p = this.parseEntry(line);
                            long ts;
                            try {
                                ts = Long.parseLong(p[0]);
                            } catch (NumberFormatException ex) {
                                continue;
                            }
                            if (ts < cutoff) {
                                continue;
                            }
                            int slot;
                            switch (p[1]) {
                                case "BAN":
                                case "TEMPBAN":
                                case "IPBAN":
                                    slot = 0;
                                    break;
                                case "MUTE":
                                    slot = 1;
                                    break;
                                case "KICK":
                                    slot = 2;
                                    break;
                                case "WARN":
                                    slot = 3;
                                    break;
                                case "UNBAN":
                                case "UNMUTE":
                                    slot = 4;
                                    break;
                                default:
                                    continue;
                            }
                            long[] arr = stats.computeIfAbsent(p[3], k -> new long[6]);
                            arr[slot]++;
                            arr[5] = Math.max(arr[5], ts);
                        }
                    }
                }
                if (stats.isEmpty()) {
                    s.sendMessage("§cNo staff actions logged in the last " + days + " days.");
                    return;
                }
                List<Map.Entry<String, long[]>> rows = new ArrayList<>(stats.entrySet());
                rows.sort((a, b) -> Long.compare(this.sumStats(b.getValue()), this.sumStats(a.getValue())));
                s.sendMessage("§b§lSTAFF ACTIVITY §7(last " + days + " days)");
                for (Map.Entry<String, long[]> en : rows) {
                    long[] v = en.getValue();
                    s.sendMessage("§f" + en.getKey() + " §8» §c" + v[0] + " bans§7, §6" + v[1] + " mutes§7, §e" + v[2] + " kicks§7, §b" + v[3]
                            + " warns§7, §a" + v[4] + " pardons §8(last: §7" + this.formatDate(String.valueOf(v[5])) + "§8)");
                }
                return;
            }
            default:
                return;
        }
    }

    private long sumStats(long[] v) {
        return v[0] + v[1] + v[2] + v[3] + v[4];
    }

    // =====================================================================================
    // /invsee: custom 54-slot view of a player's inventory (armor, offhand/totem, inventory, hotbar)
    //
    //   slots 0-3   helmet, chestplate, leggings, boots
    //   slot  4     player info
    //   slot  5     offhand (totem slot)
    //   slots 9-35  main inventory (same numbers as the real inventory)
    //   slots 45-53 hotbar
    //   everything else is a locked frame
    //
    // Edits are written to the target right away, and the view refreshes live every 5 ticks.
    // =====================================================================================

    private static final class InvseeSession {
        final UUID target;
        final Inventory gui;
        final boolean editable;
        final ItemStack[] shadow = new ItemStack[54]; // what the target had the last time we synced
        boolean dirty = false;

        InvseeSession(UUID target, Inventory gui, boolean editable) {
            this.target = target;
            this.gui = gui;
            this.editable = editable;
        }
    }

    private final Map<UUID, InvseeSession> invseeSessions = new HashMap<>();

    private static boolean isInvseeSlot(int slot) {
        return slot == 0 || slot == 1 || slot == 2 || slot == 3 || slot == 5
                || (slot >= 9 && slot <= 35) || (slot >= 45 && slot <= 53);
    }

    private ItemStack invseeNorm(ItemStack it) {
        return (it == null || it.getType().isAir() || it.getAmount() <= 0) ? null : it.clone();
    }

    private boolean sameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    private ItemStack invseeRead(PlayerInventory ti, int slot) {
        if (slot == 0) {
            return ti.getHelmet();
        }
        if (slot == 1) {
            return ti.getChestplate();
        }
        if (slot == 2) {
            return ti.getLeggings();
        }
        if (slot == 3) {
            return ti.getBoots();
        }
        if (slot == 5) {
            return ti.getItemInOffHand();
        }
        if (slot >= 9 && slot <= 35) {
            return ti.getItem(slot);
        }
        if (slot >= 45 && slot <= 53) {
            return ti.getItem(slot - 45);
        }
        return null;
    }

    private void invseeWrite(PlayerInventory ti, int slot, ItemStack it) {
        if (slot == 0) {
            ti.setHelmet(it);
        } else if (slot == 1) {
            ti.setChestplate(it);
        } else if (slot == 2) {
            ti.setLeggings(it);
        } else if (slot == 3) {
            ti.setBoots(it);
        } else if (slot == 5) {
            ti.setItemInOffHand(it);
        } else if (slot >= 9 && slot <= 35) {
            ti.setItem(slot, it);
        } else if (slot >= 45 && slot <= 53) {
            ti.setItem(slot - 45, it);
        }
    }

    private ItemStack invseeInfo(Player t, boolean editable) {
        ItemStack head = this.createGuiItem(Material.PLAYER_HEAD, "&#B15CFF&l" + t.getName(),
                "&7Health: &f" + (int) Math.ceil(t.getHealth()),
                "&7Food: &f" + t.getFoodLevel(),
                "&7Level: &f" + t.getLevel(),
                "&7Gamemode: &f" + t.getGameMode().name(),
                " ",
                "&8Left of the head: helmet, chestplate, leggings, boots",
                "&8Right of the head: offhand (totem slot)",
                "&8Middle: inventory, bottom row: hotbar",
                editable ? "&#FF9147You can edit this inventory" : "&#D9381EView only");
        ItemMeta hm = head.getItemMeta();
        if (hm instanceof SkullMeta) {
            ((SkullMeta) hm).setOwningPlayer(t);
            head.setItemMeta(hm);
        }
        return head;
    }

    // Copies the target's real inventory into the view (only touching slots that changed).
    private void invseeRefresh(InvseeSession ss, Player target) {
        PlayerInventory ti = target.getInventory();
        for (int slot = 0; slot < 54; ++slot) {
            if (!isInvseeSlot(slot)) {
                continue;
            }
            ItemStack it = this.invseeNorm(this.invseeRead(ti, slot));
            if (!this.sameItem(this.invseeNorm(ss.gui.getItem(slot)), it)) {
                ss.gui.setItem(slot, it);
            }
            ss.shadow[slot] = it;
        }
        ss.gui.setItem(4, this.invseeInfo(target, ss.editable));
    }

    // Writes whatever the staff member changed in the view into the target's real inventory.
    private void invseeSync(InvseeSession ss) {
        if (!ss.editable) {
            return;
        }
        Player target = Bukkit.getPlayer(ss.target);
        if (target == null) {
            return;
        }
        PlayerInventory ti = target.getInventory();
        for (int slot = 0; slot < 54; ++slot) {
            if (!isInvseeSlot(slot)) {
                continue;
            }
            ItemStack now = this.invseeNorm(ss.gui.getItem(slot));
            if (!this.sameItem(now, ss.shadow[slot])) {
                this.invseeWrite(ti, slot, now);
                ss.shadow[slot] = now;
            }
        }
        target.updateInventory();
    }

    private void openInvsee(Player viewer, Player target) {
        boolean editable = viewer.hasPermission("unstableffa.invsee.edit");
        Inventory gui = Bukkit.createInventory((InventoryHolder) null, 54, INVSEE_PREFIX + target.getName());
        ItemStack frame = this.createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i : new int[]{6, 7, 8}) {
            gui.setItem(i, frame);
        }
        for (int i = 36; i < 45; ++i) {
            gui.setItem(i, frame);
        }

        InvseeSession ss = new InvseeSession(target.getUniqueId(), gui, editable);
        this.invseeRefresh(ss, target);
        viewer.openInventory(gui);
        // Registered after opening: opening closes any previous menu, which would drop the session.
        this.invseeSessions.put(viewer.getUniqueId(), ss);
    }

    private void markInvseeDirty(InvseeSession ss) {
        ss.dirty = true;
        Bukkit.getScheduler().runTask(this, () -> {
            this.invseeSync(ss);
            ss.dirty = false;
        });
    }

    private void handleInvseeClick(InventoryClickEvent e, Player p) {
        InvseeSession ss = this.invseeSessions.get(p.getUniqueId());
        if (ss == null || !ss.editable) {
            e.setCancelled(true);
            return;
        }

        Inventory top = e.getView().getTopInventory();
        int raw = e.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();

        if (e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
            return;
        }
        if (inTop && !isInvseeSlot(raw)) {
            e.setCancelled(true);
            return;
        }

        // Shift-click from your own inventory: put the item in the first free inventory/hotbar slot
        // (vanilla would drop it into the empty armor/offhand slots).
        if (!inTop && e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack moving = e.getCurrentItem();
            if (moving == null || moving.getType().isAir()) {
                return;
            }
            int free = -1;
            for (int slot = 9; slot <= 53; ++slot) {
                if (slot >= 36 && slot <= 44) {
                    continue;
                }
                if (top.getItem(slot) == null || top.getItem(slot).getType().isAir()) {
                    free = slot;
                    break;
                }
            }
            if (free == -1) {
                p.sendMessage("§cTheir inventory is full.");
                return;
            }
            top.setItem(free, moving.clone());
            e.setCurrentItem(null);
            this.markInvseeDirty(ss);
            return;
        }

        this.markInvseeDirty(ss);
    }

    // Runs every 5 ticks: keeps every open /invsee view in sync with the target's real inventory.
    private void tickInvsee() {
        if (this.invseeSessions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, InvseeSession> en : new ArrayList<>(this.invseeSessions.entrySet())) {
            Player viewer = Bukkit.getPlayer(en.getKey());
            InvseeSession ss = en.getValue();
            if (viewer == null) {
                this.invseeSessions.remove(en.getKey());
                continue;
            }
            Player target = Bukkit.getPlayer(ss.target);
            if (target == null) {
                this.invseeSessions.remove(en.getKey());
                viewer.closeInventory();
                viewer.sendMessage("§cThat player went offline.");
                continue;
            }
            if (!ss.dirty) {
                this.invseeRefresh(ss, target);
            }
        }
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.translate(name));
            if (lore.length > 0) {
                meta.setLore((List)Arrays.stream(lore).map(this::translate).collect(Collectors.toList()));
            }

            if (mat == Material.ENDER_PEARL) {
                meta.setEnchantmentGlintOverride(true);
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    // Default startTp: OP always gets instant teleport (original behavior),
    // used by /home, /warp, /spawn, /tphere, etc.
    public void startTp(final Player p, final Location loc, final String msgKey, final String name) {
        this.startTp(p, loc, msgKey, name, true);
    }

    // allowOpInstant controls whether being OP alone grants an instant teleport here.
    // Players with unstableffa.fasttravel.instant ALWAYS get instant, regardless of this flag.
    // TPA call sites pass the "tpa-instant-op" config value instead of always true.
    public void startTp(final Player p, final Location loc, final String msgKey, final String name, final boolean allowOpInstant) {
        int delay = this.getConfig().getInt("teleport-delay", 5);
        if ((p.isOp() && allowOpInstant) || p.hasPermission("unstableffa.fasttravel.instant")) {
            delay = 0;
        }
        final int d = delay;

        if (d <= 0) {
            p.teleport(loc);
            p.sendMessage(this.getMsg(msgKey).replace("{name}", name));
        } else {
            if (this.activeTeleports.containsKey(p.getUniqueId())) {
                ((BukkitTask)this.activeTeleports.get(p.getUniqueId())).cancel();
            }

            this.activeTeleports.put(p.getUniqueId(), (new BukkitRunnable() {
                int i = d;

                public void run() {
                    if (this.i <= 0) {
                        p.teleport(loc);
                        p.sendMessage(zenithEssentials.this.getMsg(msgKey).replace("{name}", name));
                        zenithEssentials.this.activeTeleports.remove(p.getUniqueId());
                        this.cancel();
                    } else {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(zenithEssentials.this.translate(zenithEssentials.this.getConfig().getString("actionbar-countdown")).replace("{time}", String.valueOf(this.i))));
                        --this.i;
                    }

                }
            }).runTaskTimer(this, 0L, 20L));
        }

    }

    private void openWarpGUI(Player p) {
        ConfigurationSection ws = this.data.getConfigurationSection("warps");
        if (ws != null) {
            Inventory inv = Bukkit.createInventory((InventoryHolder)null, 54, "§b§lSelect a Warp");
            ItemStack frame = this.createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ");

            for(int i = 0; i < 54; ++i) {
                if (i < 9 || i > 44 || i % 9 == 0 || (i + 1) % 9 == 0) {
                    inv.setItem(i, frame);
                }
            }

            List<String> av = (List)ws.getKeys(false).stream().filter((w) -> {
                String var10001 = w.toLowerCase();
                return p.hasPermission("unstableffa.warp." + var10001.replace(" ", "_")) || p.isOp();
            }).collect(Collectors.toList());
            int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

            for(int i = 0; i < av.size() && i < slots.length; ++i) {
                inv.setItem(slots[i], this.createGuiItem(Material.ENDER_PEARL, "&#B15CFF&l" + (String)av.get(i), "&7Click to travel."));
            }

            p.openInventory(inv);
        }
    }

    // Blocks IP-banned addresses (/ipban) from connecting.
    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        if (e.getAddress() == null) {
            return;
        }
        String entry = this.data.getString("ipbans." + this.ipKey(e.getAddress().getHostAddress()));
        if (entry == null) {
            return;
        }
        if (this.isOwner(e.getPlayer().getName())) {
            return;
        }
        String[] p = entry.split("\\|", 5);
        String reason = p.length > 3 ? p[3] : "Ban evasion";
        String id = p.length > 4 ? p[4] : null;
        e.disallow(PlayerLoginEvent.Result.KICK_BANNED, this.translate("&#D9381E&lYOU ARE IP BANNED\n&7Reason: &f" + reason) + this.banFooter(id));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player jp = e.getPlayer();
        if (this.lockdownActive && !jp.hasPermission("unstableffa.lockdown.bypass")) {
            e.setJoinMessage((String)null);
            jp.kickPlayer(this.translate(this.getConfig().getString("lockdown-kick-message")));
            return;
        }

        this.loadPlayerSettings(jp); // first, so social spy / tpa settings come back even if something below fails
        this.trackPlayer(jp);
        this.touchUser(jp);
        this.quests.onJoin(jp);
        this.screenShare.onJoin(jp);
        // The server stopped while they were frozen: make sure they can walk again.
        if (!this.frozen.contains(jp.getUniqueId()) && jp.getWalkSpeed() == 0F) {
            jp.setWalkSpeed(0.2F);
            jp.setFlySpeed(0.1F);
        }
        this.banEvasion.check(jp);
        this.applyNick(jp);

        // Everyone who is vanished stays hidden from the player who just joined.
        for (UUID vid : this.vanished) {
            Player v = Bukkit.getPlayer(vid);
            if (v != null && !v.equals(jp)) {
                jp.hidePlayer(this, v);
            }
        }

        if (this.vanished.contains(jp.getUniqueId())) {
            // Rejoining while still vanished: no join message, stay hidden.
            e.setJoinMessage((String)null);
            Bukkit.getOnlinePlayers().forEach((o) -> {
                if (!o.equals(jp)) {
                    o.hidePlayer(this, jp);
                }
            });
            jp.sendMessage("§7You joined while vanished.");
        } else {
            String j = this.messages.getString("join-message");
            if (j != null && !j.equalsIgnoreCase("none")) {
                e.setJoinMessage(this.translate(j).replace("{player}", jp.getName()));
            }
            this.staffJoinAlert(jp);
        }

        int visibleOnline = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!this.vanished.contains(o.getUniqueId())) {
                visibleOnline++;
            }
        }
        this.peakPlayers = Math.max(this.peakPlayers, visibleOnline);

        if (!jp.hasPlayedBefore()) {
            this.giveFirstJoinKit(jp);
        }

        // People who blocked this player (or were blocked by them) don't get the join message.
        String jm = e.getJoinMessage();
        if (jm != null && !jm.isEmpty() && this.anyoneBlocks(jp.getUniqueId())) {
            e.setJoinMessage((String) null);
            this.announceFiltered(jm, jp);
        }

        if (this.getConfig().getBoolean("discord-webhook.status-embed.update-on-join-quit", false)) {
            this.updateStatusEmbedAsync(false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player qp = e.getPlayer();
        this.savePlayerSettings(qp); // first, so nothing below can stop it from saving
        this.touchUserQuit(qp);
        this.screenShare.onQuit(qp);
        this.quests.onQuit(qp);
        this.liveViewers.remove(qp.getUniqueId());
        if (this.vanished.contains(qp.getUniqueId())) {
            // Vanished players already "left" - don't announce it twice.
            e.setQuitMessage((String)null);
        } else {
            String q = this.messages.getString("leave-message");
            if (q != null && !q.equalsIgnoreCase("none")) {
                e.setQuitMessage(this.translate(q).replace("{player}", qp.getName()));
            }
        }

        String qm = e.getQuitMessage();
        if (qm != null && !qm.isEmpty() && this.anyoneBlocks(qp.getUniqueId())) {
            e.setQuitMessage((String) null);
            this.announceFiltered(qm, qp);
        }
        this.chatInput.remove(qp.getUniqueId());
        this.chatGuard.forget(qp.getUniqueId());

        this.setFrozen(qp, false, true); // gives their walk speed back before it is saved
        this.inStaffChat.remove(qp.getUniqueId());
        this.invseeReadOnly.remove(qp.getUniqueId());
        this.invseeSessions.remove(qp.getUniqueId());
        for (Map.Entry<UUID, InvseeSession> en : new ArrayList<>(this.invseeSessions.entrySet())) {
            if (en.getValue().target.equals(qp.getUniqueId())) {
                this.invseeSessions.remove(en.getKey());
                Player viewer = Bukkit.getPlayer(en.getKey());
                if (viewer != null) {
                    viewer.closeInventory();
                    viewer.sendMessage("§cThat player went offline.");
                }
            }
        }

        if (this.getConfig().getBoolean("discord-webhook.status-embed.update-on-join-quit", false)) {
            // One tick later, so the player who left is no longer counted.
            Bukkit.getScheduler().runTask(this, () -> this.updateStatusEmbedAsync(false));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        this.invseeReadOnly.remove(id);
        this.liveViewers.remove(id);
        InvseeSession ss = this.invseeSessions.remove(id);
        if (ss != null) {
            this.invseeSync(ss); // make sure the last edit reached the target
        }
        if (e.getInventory().getHolder() instanceof Gui && e.getPlayer() instanceof Player) {
            Gui gh = (Gui) e.getInventory().getHolder();
            if (gh.type.equals("kit-items")) {
                this.saveKitItems(gh, (Player) e.getPlayer());
            }
        }
    }

    // Stops items being dragged around inside the plugin's menus and read-only /invsee views.
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) {
            Gui gh = (Gui) e.getView().getTopInventory().getHolder();
            if (gh.type.equals("kit-items")) {
                for (int raw : e.getRawSlots()) {
                    if (raw >= 45 && raw < 54) {
                        e.setCancelled(true);
                        return;
                    }
                }
                return;
            }
            e.setCancelled(true);
            return;
        }
        String title = e.getView().getTitle();
        if (title.startsWith(INVSEE_PREFIX)) {
            InvseeSession ss = this.invseeSessions.get(e.getWhoClicked().getUniqueId());
            if (ss == null || !ss.editable) {
                e.setCancelled(true);
                return;
            }
            for (int raw : e.getRawSlots()) {
                if (raw < 54 && !isInvseeSlot(raw)) {
                    e.setCancelled(true);
                    return;
                }
            }
            this.markInvseeDirty(ss);
            return;
        }
        if (this.invseeReadOnly.contains(e.getWhoClicked().getUniqueId())
                || title.equals("§b§lSelect a Warp")
                || title.equals("§c§lClear Your Inventory?")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player) {
            Player p = (Player)e.getWhoClicked();
            if (e.getView().getTopInventory().getHolder() instanceof Gui) {
                Gui gh = (Gui) e.getView().getTopInventory().getHolder();
                if (gh.type.equals("kit-items")) {
                    this.clickKitItems(e, p, gh); // the kit item editor lets you move items
                    return;
                }
                this.handleGuiClick(e, p, gh);
                return;
            }
            String title = e.getView().getTitle();
            if (title.startsWith(INVSEE_PREFIX)) {
                this.handleInvseeClick(e, p);
                return;
            }
            if (this.invseeReadOnly.contains(p.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
            if (title.equals("§b§lSelect a Warp")) {
                e.setCancelled(true);
                if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ENDER_PEARL) {
                    p.closeInventory();
                    p.performCommand("warp " + ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName()));
                }
            } else if (title.equals("§8Trash Bin Disposal")) {
                if (e.getRawSlot() >= 45 && e.getRawSlot() <= 53) {
                    e.setCancelled(true);
                    if (e.getRawSlot() == 49) {
                        Inventory gui = Bukkit.createInventory((InventoryHolder)null, 9, "§c§lClear Your Inventory?");

                        for(int i = 0; i < 9; ++i) {
                            gui.setItem(i, this.createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
                        }

                        gui.setItem(2, this.createGuiItem(Material.LIME_WOOL, "<gradient:#FF7518:#FFFFFF>&lCONFIRM</gradient><gradient:#FFFFFF:#FF7518>&l CLEAR</gradient>", "&7Wipe your inventory."));
                        gui.setItem(6, this.createGuiItem(Material.RED_WOOL, "<gradient:#FF7518:#FFFFFF>&lCAN</gradient><gradient:#FFFFFF:#FF7518>&lCEL</gradient>", "&7Keep your items."));
                        p.openInventory(gui);
                    }
                }
            } else if (title.equals("§c§lClear Your Inventory?")) {
                e.setCancelled(true);
                if (e.getCurrentItem() == null) {
                    return;
                }

                if (e.getCurrentItem().getType() == Material.LIME_WOOL) {
                    p.getInventory().clear();
                    p.closeInventory();
                    p.sendMessage(this.getMsg("trash-cleared"));
                } else if (e.getCurrentItem().getType() == Material.RED_WOOL) {
                    p.closeInventory();
                    p.sendMessage(this.getMsg("trash-cancelled"));
                }
            }

        }
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        String n = cmd.getName().toLowerCase();
        // Safety net: a disabled command never runs, even if the server still had it in its list.
        if (this.commandManager != null && this.commandManager.isDisabled(n)) {
            s.sendMessage(this.translate("&#D9381EUnknown command. Type \"/help\" for help."));
            return true;
        }
        if (n.equals("announce")) {
            this.announcer.handle(s, args);
            return true;
        }
        if (n.equals("announcerestart")) {
            this.announcer.handleRestart(s, args);
            return true;
        }
        if (n.equals("modmode")) {
            this.modMode.handle(s, args);
            return true;
        }
        if (n.equals("leaderboard")) {
            this.leaderboards.handle(s, args);
            return true;
        }
        if (n.equals("rules") || n.equals("guide") || n.equals("staffguide")) {
            this.rulesGuide.handle(s, n, args);
            return true;
        }
        if (n.equals("warps")) {
            this.warps.handle(s, args);
            return true;
        }
        if (n.equals("stats")) {
            this.statsMenu.handle(s, args);
            return true;
        }
        if (n.equals("combat")) {
            this.combat.handle(s, args);
            return true;
        }
        if (n.equals("welcome")) {
            this.welcome.handle(s, args);
            return true;
        }
        if (n.equals("zenithmenu")) {
            this.zenithMenu.handle(s, args);
            return true;
        }
        if (n.equals("commandspy")) {
            this.commandSpy.handle(s, args);
            return true;
        }
        if (n.equals("discord")) {
            this.discordCommand.handle(s, args);
            return true;
        }
        if (n.equals("kickall")) {
            this.firstJoin.handleKickAll(s, args);
            return true;
        }
        if (n.equals("banlog")) {
            this.banLog.handle(s, args);
            return true;
        }
        if (n.equals("lagfinder")) {
            this.lagFinder.handle(s, args);
            return true;
        }
        if (n.matches("punish|ban|tempban|unban|mute|unmute|kick|warn")) {
            this.handlePunishment(s, n, args);
            return true;
        } else if (n.equals("unstableffa")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("config")) {
                this.configCommand.handle(s, args);
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
                this.help(s, args.length > 1 ? args[1] : "1");
                return true;
            }
            if (!s.hasPermission("unstableffa.admin")) {
                s.sendMessage(this.getMsg("no-permission"));
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
                this.reloadAll();
                s.sendMessage(this.getMsg("reload-success"));
            } else if (args[0].equalsIgnoreCase("backup")) {
                this.backups.backup(s);
            } else if (args[0].equalsIgnoreCase("debug")) {
                this.debugInfo.handle(s);
            } else if (args[0].equalsIgnoreCase("killstreak")) {
                this.killstreak.preview(s, args);
            } else {
                s.sendMessage("§cUsage: /unstableffa <help|reload|backup|debug|killstreak|config>");
            }
            return true;
        } else if (n.equals("clearchat")) {
            if (!s.hasPermission("unstableffa.clearchat")) {
                s.sendMessage(this.getMsg("no-permission"));
                return true;
            } else {
                for(int i = 0; i < 100; ++i) {
                    Bukkit.broadcastMessage("");
                }

                Bukkit.broadcastMessage(this.translate(this.getConfig().getString("chat-cleared-broadcast")).replace("{admin}", s.getName()));
                return true;
            }
        } else if (n.equals("lockdown")) {
            if (!s.hasPermission("unstableffa.lockdown")) {
                s.sendMessage(this.getMsg("no-permission"));
                return true;
            } else {
                this.lockdownActive = !this.lockdownActive;
                this.getConfig().set("lockdown-active", this.lockdownActive);
                this.saveConfig();
                if (this.lockdownActive) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance on");

                    for(Player op : Bukkit.getOnlinePlayers()) {
                        if (!op.hasPermission("unstableffa.lockdown.bypass")) {
                            op.kickPlayer(this.translate(this.getConfig().getString("lockdown-kick-message")));
                        }
                    }

                    s.sendMessage("§cLockdown Enabled.");
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance off");
                    s.sendMessage("§aLockdown Disabled.");
                }

                // Reflect the new Online/Maintenance state on the Discord status embed right away
                // instead of waiting for the next scheduled update.
                this.updateStatusEmbedAsync(false);

                return true;
            }
        } else if (n.equals("serverstatus")) {
            if (!s.hasPermission("unstableffa.serverstatus")) {
                s.sendMessage(this.getMsg("no-permission"));
                return true;
            } else {
                if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
                    this.discord.resetStatus();
                    s.sendMessage("§aForgot the old status message. A new one is posted now - delete the old one in Discord.");
                }
                this.updateStatusEmbedAsync(false);
                s.sendMessage("§aDiscord status embed update requested.");
                return true;
            }
        } else if (n.matches("unwarn|pardonoffense|note|alts|ipban|unipban|clearhistory|staffstats")) {
            this.handleStaffCommand(s, n, args);
            return true;
        } else if (n.matches("seen|whois|playtime|mutelist|nick|live")) {
            this.handleExtra(s, n, args);
            return true;
        } else if (n.equals("openwarpgui")) {
            if (s instanceof ConsoleCommandSender && args.length > 0) {
                Player t = Bukkit.getPlayer(args[0]);
                if (t != null) {
                    this.openWarpGUI(t);
                }
            }

            return true;
        } else if (n.matches("livelink|quests|rewards|chatlog|banevasion|staffactivity|ss|punishlimits")) {
            this.handleModule(s, n, args);
            return true;
        } else if (n.matches("mutechat|clearlag|staff|kit|report|reports|block|unblock|ignore|unignore|near")) {
            this.handleV4(s, n, args);
            return true;
        } else if (!(s instanceof Player)) {
            return true;
        } else {
            Player p = (Player)s;
            switch (n) {
                case "history":
                    if (!p.hasPermission("unstableffa.history")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    if (args.length < 1) {
                        this.openRecent(p, null);
                        break;
                    }

                    OfflinePlayer histTarget;
                    String histName;
                    if (args[0].startsWith("#")) {
                        String histUuid = this.findUuidByBanId(args[0].substring(1));
                        if (histUuid == null) {
                            p.sendMessage("§cNo punishment found with that ID.");
                            return true;
                        }
                        histTarget = Bukkit.getOfflinePlayer(UUID.fromString(histUuid));
                        histName = this.nameForUuid(histUuid);
                    } else {
                        histTarget = this.resolveOffline(args[0]);
                        histName = this.displayName(histTarget, args[0]);
                    }

                    if (this.data.getStringList("punishlog." + histTarget.getUniqueId()).isEmpty()) {
                        p.sendMessage("§aNo punishment history for §f" + histName + "§a.");
                        return true;
                    }

                    this.openHistory(p, histTarget, histName, null);
                    break;
                case "invsee":
                case "enderchest":
                    if (!p.hasPermission("unstableffa.invsee")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    if (args.length < 1) {
                        p.sendMessage("§cUsage: /" + n + " <player>");
                        return true;
                    }

                    Player invTarget = Bukkit.getPlayer(args[0]);
                    if (invTarget == null) {
                        OfflinePlayer invOff = this.resolveOffline(args[0]);
                        if (invOff.hasPlayedBefore()) {
                            p.sendMessage("§c" + this.displayName(invOff, args[0]) + " is offline. /" + n + " only works on online players.");
                            return true;
                        }
                    }
                    if (invTarget == null || (this.vanished.contains(invTarget.getUniqueId()) && !p.hasPermission("unstableffa.vanish"))) {
                        p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                        return true;
                    }

                    if (n.equals("invsee")) {
                        // Custom view: armor, offhand/totem, inventory and hotbar. Editable with unstableffa.invsee.edit.
                        this.openInvsee(p, invTarget);
                        break;
                    }

                    // Ender chest: view-only unless the staff member has unstableffa.invsee.edit.
                    p.openInventory(invTarget.getEnderChest());
                    if (p.hasPermission("unstableffa.invsee.edit")) {
                        this.invseeReadOnly.remove(p.getUniqueId());
                    } else {
                        this.invseeReadOnly.add(p.getUniqueId());
                    }
                    break;
                case "zenithsettings":
                    if (args.length == 0) {
                        this.openSettings(p);
                        break;
                    }
                    if (!this.toggleSettingByKey(p, args[0])) {
                        p.sendMessage("§cUsage: /zenithsettings [" + String.join("|", this.settingKeys(p)) + "]");
                    }
                    break;
                case "freeze":
                    if (!p.hasPermission("unstableffa.freeze")) {
                        return true;
                    }

                    if (args.length < 1) {
                        p.sendMessage(this.getMsg("usage-freeze"));
                        return true;
                    }

                    Player ft = Bukkit.getPlayer(args[0]);
                    if (ft == null) {
                        p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                        return true;
                    }

                    this.toggleFreeze(p, ft);
                    break;
                case "staffchat":
                case "sc":
                    if (!p.hasPermission("unstableffa.staffchat")) {
                        return true;
                    }

                    if (args.length == 0) {
                        if (this.inStaffChat.contains(p.getUniqueId())) {
                            this.inStaffChat.remove(p.getUniqueId());
                        } else {
                            this.inStaffChat.add(p.getUniqueId());
                        }

                        p.sendMessage("§bStaffChat Toggle.");
                    } else {
                        String var38 = p.getName();
                        String m = "§8[§bSTAFF§8] §f" + var38 + " §7» §b" + String.join(" ", args);
                        this.staffActivity.onStaffChat(p);
                        Bukkit.getOnlinePlayers().stream().filter((stx) -> stx.hasPermission("unstableffa.staffchat")).forEach((stx) -> stx.sendMessage(m));
                    }
                    break;
                case "socialspy": {
                    boolean spyOn = !this.socialSpy.contains(p.getUniqueId());
                    if (spyOn) {
                        this.socialSpy.add(p.getUniqueId());
                    } else {
                        this.socialSpy.remove(p.getUniqueId());
                    }
                    // Saved right away, so it stays on after leaving, rejoining or a restart.
                    this.setSetting(p.getUniqueId(), "socialspy", spyOn);
                    p.sendMessage(this.translate(spyOn ? "&8» <gradient:#FF7518:#FFFFFF>&lSocia</gradient><gradient:#FFFFFF:#FF7518>&ll Spy</gradient> &7is now &#FF9147on&7. It stays on until you turn it off."
                            : "&8» <gradient:#FF7518:#FFFFFF>&lSocia</gradient><gradient:#FFFFFF:#FF7518>&ll Spy</gradient> &7is now &#D9381Eoff&7."));
                    break;
                }
                case "trash":
                    Inventory tr = Bukkit.createInventory((InventoryHolder)null, 54, "§8Trash Bin Disposal");

                    for(int i = 45; i < 54; ++i) {
                        tr.setItem(i, this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " "));
                    }

                    tr.setItem(49, this.createGuiItem(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lCLEAR WHOLE</gradient><gradient:#FFFFFF:#FF7518>&l INVENTORY</gradient>", "&7Click to open confirmation."));
                    p.openInventory(tr);
                    break;
                case "tpa":
                case "tpahere":
                    if (!p.hasPermission(TPA_PERMISSION)) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    if (args.length < 1) {
                        p.sendMessage(this.getMsg("usage-" + n));
                        return true;
                    }

                    Player target = Bukkit.getPlayer(args[0]);
                    if (target == null) {
                        p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                        return true;
                    }

                    // You can't send a /tpa or /tpahere to yourself.
                    if (target.getUniqueId().equals(p.getUniqueId())) {
                        p.sendMessage(this.messages.contains("tpa-self") ? this.getMsg("tpa-self")
                                : this.translate(this.messages.getString("prefix", "<gradient:#FF7518:#FFFFFF>&lUnst</gradient><gradient:#FFFFFF:#FF7518>&lable</gradient>&f&lEssentials &8»") + " &#D9381EYou can't send a teleport request to yourself."));
                        return true;
                    }

                    if (this.tpDisabled.contains(target.getUniqueId())) {
                        p.sendMessage("§cThat player has TPA disabled.");
                        return true;
                    }

                    if (this.blockedEitherWay(p.getUniqueId(), target.getUniqueId())) {
                        p.sendMessage("§cYou can't send teleport requests to that player.");
                        return true;
                    }

                    boolean isHere = n.equals("tpahere");

                    this.chatGuard.markUsed(p, n); // tpa / tpahere cooldown starts now

                    if (this.tpAuto.contains(target.getUniqueId())) {
                        this.startTp(p, target.getLocation(), "teleport-safe", target.getName(), this.getConfig().getBoolean("tpa-instant-op", true));
                        return true;
                    }

                    this.tpaReq.put(target.getUniqueId(), p.getUniqueId());
                    this.tpType.put(target.getUniqueId(), isHere);
                    p.sendMessage(this.getMsg(isHere ? "tpahere-sent" : "tpa-sent").replace("{player}", target.getName()));
                    target.sendMessage(this.getMsg(isHere ? "tpahere-received" : "tpa-received").replace("{player}", p.getName()));
                    break;
                case "tpaccept":
                    if (!p.hasPermission(TPA_PERMISSION)) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    UUID pendingFrom = this.tpaReq.get(p.getUniqueId());
                    if (this.tpaGui.enabled() && pendingFrom != null && Bukkit.getPlayer(pendingFrom) != null) {
                        this.tpaGui.open(p, Bukkit.getPlayer(pendingFrom), Boolean.TRUE.equals(this.tpType.get(p.getUniqueId())));
                        return true;
                    }
                    this.acceptTpa(p);
                    break;
                case "tpdeny":
                    if (!p.hasPermission(TPA_PERMISSION)) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    this.denyTpa(p);
                    break;
                case "tpacancel":
                    if (!p.hasPermission(TPA_PERMISSION)) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    this.tpaReq.values().remove(p.getUniqueId());
                    p.sendMessage("§cTPA Cancelled.");
                    break;
                case "tpauto":
                    if (!p.hasPermission("unstableffa.tpauto")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    if (this.tpAuto.contains(p.getUniqueId())) {
                        this.tpAuto.remove(p.getUniqueId());
                    } else {
                        this.tpAuto.add(p.getUniqueId());
                    }

                    p.sendMessage(this.tpAuto.contains(p.getUniqueId()) ? this.getMsg("tpauto-on") : this.getMsg("tpauto-off"));
                    break;
                case "tpatoggle":
                    if (!p.hasPermission("unstableffa.tpauto")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    if (this.tpDisabled.contains(p.getUniqueId())) {
                        this.tpDisabled.remove(p.getUniqueId());
                    } else {
                        this.tpDisabled.add(p.getUniqueId());
                    }

                    p.sendMessage(this.tpDisabled.contains(p.getUniqueId()) ? this.getMsg("tpa-toggle-off") : this.getMsg("tpa-toggle-on"));
                    break;
                case "spawn":
                    if (!p.hasPermission("unstableffa.spawn")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    Location spl = this.data.getLocation("spawn");
                    if (spl != null) {
                        this.startTp(p, spl, "spawn-teleport", "Spawn");
                    } else {
                        p.sendMessage(this.messages.getString("spawn-not-set") != null ? this.getMsg("spawn-not-set")
                                : this.translate("&8» &#D9381E&lError! &7There is no spawn set yet."
                                        + (p.hasPermission("unstableffa.setspawn") ? " Use &f/setspawn&7 to set it." : "")));
                    }
                    break;
                case "setspawn":
                    if (!p.hasPermission("unstableffa.setspawn")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    this.data.set("spawn", p.getLocation());
                    this.saveD();
                    p.sendMessage(this.getMsg("spawn-set"));
                    break;
                case "home":
                    if (!p.hasPermission("unstableffa.home")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    String hName = args.length > 0 ? String.join(" ", args) : "home";
                    FileConfiguration var37 = this.data;
                    String var42 = String.valueOf(p.getUniqueId());
                    Location hLoc = var37.getLocation("homes." + var42 + "." + hName);
                    if (hLoc != null) {
                        this.startTp(p, hLoc, "home-teleport", hName);
                    } else {
                        p.sendMessage(this.getMsg("home-not-found").replace("{name}", hName));
                    }
                    break;
                case "sethome":
                    if (!p.hasPermission("unstableffa.sethome")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    String shName = args.length > 0 ? String.join(" ", args) : "home";
                    int homeLimit = this.getHomeLimit(p);
                    ConfigurationSection ownHomes = this.data.getConfigurationSection("homes." + p.getUniqueId());
                    int homeCount = ownHomes == null ? 0 : ownHomes.getKeys(false).size();
                    boolean homeExists = ownHomes != null && ownHomes.getKeys(false).contains(shName);
                    if (homeLimit >= 0 && !homeExists && homeCount >= homeLimit) {
                        p.sendMessage("§cYou've reached your home limit (§f" + homeLimit + "§c). Delete one with /delhome or get a higher rank.");
                        return true;
                    }
                    this.data.set("homes." + String.valueOf(p.getUniqueId()) + "." + shName, p.getLocation());
                    this.saveD();
                    p.sendMessage(this.getMsg("home-set").replace("{name}", shName));
                    break;
                case "delhome":
                    if (!p.hasPermission("unstableffa.delhome")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    String dhName = args.length > 0 ? String.join(" ", args) : "home";
                    FileConfiguration var10000 = this.data;
                    String var41 = String.valueOf(p.getUniqueId());
                    if (var10000.contains("homes." + var41 + "." + dhName)) {
                        this.data.set("homes." + String.valueOf(p.getUniqueId()) + "." + dhName, (Object)null);
                        this.saveD();
                        p.sendMessage(this.getMsg("home-del").replace("{name}", dhName));
                    } else {
                        p.sendMessage(this.getMsg("home-not-found").replace("{name}", dhName));
                    }
                    break;
                case "listhomes":
                    if (!p.hasPermission("unstableffa.listhomes")) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    ConfigurationSection hs = this.data.getConfigurationSection("homes." + String.valueOf(p.getUniqueId()));
                    p.sendMessage(this.getMsg("home-list").replace("{homes}", hs == null ? "None" : String.join(", ", hs.getKeys(false))));
                    break;
                case "warp":
                    if (args.length < 1) {
                        p.sendMessage(this.getMsg("usage-warp"));
                        return true;
                    }

                    String wNm = String.join(" ", args);
                    String var40 = wNm.toLowerCase();
                    if (!p.hasPermission("unstableffa.warp." + var40.replace(" ", "_")) && !p.isOp()) {
                        p.sendMessage(this.getMsg("no-permission"));
                        return true;
                    }

                    Location wLoc = this.data.getLocation("warps." + wNm);
                    if (wLoc != null) {
                        this.startTp(p, wLoc, "warp-teleport", wNm);
                    } else {
                        p.sendMessage(this.getMsg("warp-not-found").replace("{name}", wNm));
                    }
                    break;
                case "setwarp":
                    if (args.length < 1) {
                        p.sendMessage(this.getMsg("usage-setwarp"));
                        return true;
                    }

                    this.data.set("warps." + String.join(" ", args), p.getLocation());
                    this.saveD();
                    p.sendMessage(this.getMsg("warp-set").replace("{name}", String.join(" ", args)));
                    break;
                case "delwarp":
                    if (args.length < 1) {
                        p.sendMessage(this.getMsg("usage-delwarp"));
                        return true;
                    }

                    String dwName = String.join(" ", args);
                    if (this.data.contains("warps." + dwName)) {
                        this.data.set("warps." + dwName, (Object)null);
                        this.saveD();
                        p.sendMessage(this.getMsg("warp-del").replace("{name}", dwName));
                    } else {
                        p.sendMessage(this.getMsg("warp-not-found").replace("{name}", dwName));
                    }
                    break;
                case "fly":
                    p.setAllowFlight(!p.getAllowFlight());
                    p.sendMessage(p.getAllowFlight() ? this.getMsg("fly-on") : this.getMsg("fly-off"));
                    break;
                case "flyspeed":
                    if (args.length > 0) {
                        try {
                            p.setFlySpeed(Float.parseFloat(args[0]) / 10.0F);
                            p.sendMessage(this.getMsg("speed-set").replace("{speed}", args[0]));
                        } catch (Exception var28) {
                        }
                    } else {
                        p.sendMessage(this.getMsg("usage-flyspeed"));
                    }
                    break;
                case "walkspeed":
                    if (args.length > 0) {
                        try {
                            p.setWalkSpeed(Float.parseFloat(args[0]) / 10.0F);
                            p.sendMessage(this.getMsg("speed-set").replace("{speed}", args[0]));
                        } catch (Exception var27) {
                        }
                    } else {
                        p.sendMessage(this.getMsg("usage-walkspeed"));
                    }
                    break;
                case "god":
                    if (this.god.contains(p.getUniqueId())) {
                        this.god.remove(p.getUniqueId());
                    } else {
                        this.god.add(p.getUniqueId());
                    }

                    p.sendMessage(this.god.contains(p.getUniqueId()) ? this.getMsg("god-on") : this.getMsg("god-off"));
                    break;
                case "vanish":
                    if (this.vanished.contains(p.getUniqueId())) {
                        this.vanished.remove(p.getUniqueId());
                        Bukkit.getOnlinePlayers().forEach((o) -> o.showPlayer(this, p));
                        p.sendMessage(this.getMsg("vanish-off"));
                        // Fake "joined the game" message so it looks like you just logged in.
                        if (this.getSetting(p.getUniqueId(), "vanish-messages", true)) {
                            Bukkit.broadcastMessage(this.fakeJoinMessage(p));
                        }
                    } else {
                        this.vanished.add(p.getUniqueId());
                        Bukkit.getOnlinePlayers().forEach((o) -> o.hidePlayer(this, p));
                        p.sendMessage(this.getMsg("vanish-on"));
                        // Fake "left the game" message so everyone thinks you disconnected.
                        if (this.getSetting(p.getUniqueId(), "vanish-messages", true)) {
                            Bukkit.broadcastMessage(this.fakeLeaveMessage(p));
                        }
                    }
                    this.refreshStatusEmbedIfTracking();
                    break;
                case "ping":
                    p.sendMessage(this.getMsg("ping-msg").replace("{ping}", String.valueOf(p.getPing())));
                    break;
                case "adminlist":
                    String warps = this.data.getConfigurationSection("warps") != null ? String.join(", ", this.data.getConfigurationSection("warps").getKeys(false)) : "None";
                    p.sendMessage("§b§lADMIN LIST\n§fWarps: " + warps);
                    break;
                case "msg":
                    if (args.length < 2) {
                        p.sendMessage(this.getMsg("usage-msg"));
                        return true;
                    }

                    Player vtRaw = Bukkit.getPlayer(args[0]);
                    // Vanished players look like they're not online; "private messages OFF" blocks everyone but staff.
                    final Player vt = (vtRaw != null && this.vanished.contains(vtRaw.getUniqueId()) && !p.hasPermission("unstableffa.vanish")) ? null : vtRaw;
                    if (vt != null && !vt.equals(p) && this.isMsgDisabled(vt) && !p.hasPermission("unstableffa.msg.bypass")) {
                        p.sendMessage("§cThat player has private messages turned off.");
                        return true;
                    }
                    if (vt != null && !vt.equals(p)) {
                        if (this.hasBlocked(p.getUniqueId(), vt.getUniqueId())) {
                            p.sendMessage("§cYou blocked that player. Use §f/unblock " + vt.getName() + " §cfirst.");
                            return true;
                        }
                        if (this.hasIgnored(p.getUniqueId(), vt.getUniqueId())) {
                            p.sendMessage("§cYou are ignoring that player. Use §f/ignore " + vt.getName() + " §cto stop.");
                            return true;
                        }
                        if (this.hidesChatFrom(vt.getUniqueId(), p.getUniqueId()) && !p.hasPermission("unstableffa.msg.bypass")) {
                            p.sendMessage("§cThat player isn't accepting your messages.");
                            return true;
                        }
                    }
                    if (vt != null) {
                        String rawMsg = String.join(" ", (CharSequence[])Arrays.copyOfRange(args, 1, args.length));
                        if (this.chatGuard.checksPrivateMessages()) {
                            ChatGuard.CussResult cuss = this.chatGuard.applyAntiCuss(p, rawMsg);
                            if (cuss != null) {
                                String notice = this.chatGuard.cussNotice(cuss.blocked);
                                if (notice != null) {
                                    p.sendMessage(notice);
                                }
                                if (cuss.blocked) {
                                    return true;
                                }
                                rawMsg = cuss.text;
                            }
                        }
                        final String m = rawMsg;
                        this.chatGuard.markUsed(p, "msg");

                        vt.sendMessage(this.formatMsg(
                                "msg-sender-format",
                                "&8[&#B15CFFMSG&8] &f{sender} &7» &f{message}",
                                p.getName(), vt.getName(), m));
                        this.playMsgSound(vt);

                        p.sendMessage(this.formatMsg(
                                "msg-target-format",
                                "&8[&#B15CFFMSG&8] &7To &f{target} &7» &f{message}",
                                p.getName(), vt.getName(), m));

                        Bukkit.getOnlinePlayers().stream().filter((stx) -> this.socialSpy.contains(stx.getUniqueId())).forEach((stx) -> {
                            stx.sendMessage(this.formatMsg(
                                    "msg-spy-format",
                                    "&8[&#B15CFFSpy&8] &7{sender} -> {target}: {message}",
                                    p.getName(), vt.getName(), m));
                        });
                    } else {
                        p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                    }
                    break;
                case "sudo":
                    if (args.length < 2) {
                        p.sendMessage(this.getMsg("usage-sudo"));
                        return true;
                    }

                    Player st = Bukkit.getPlayer(args[0]);
                    if (st == null) {
                        p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                        break;
                    }
                    if (!st.equals(p) && st.hasPermission("unstableffa.sudo.exempt") && !p.isOp()) {
                        p.sendMessage(this.translate("&8» &#D9381E&lError! &7You can't sudo &f" + st.getName() + "&7."));
                        break;
                    }
                    String sudoLine = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                    if (sudoLine.toLowerCase(Locale.ROOT).startsWith("c:")) {
                        // /sudo <player> c:<message>  makes them say something in chat
                        String said = sudoLine.substring(2).trim();
                        st.chat(said);
                        p.sendMessage(this.translate("&8» &#FF9147&lSudo! &f" + st.getName() + " &7said: &f" + said));
                        break;
                    }
                    while (sudoLine.startsWith("/")) {
                        sudoLine = sudoLine.substring(1); // "/sudo Steve /spawn" used to fail because of the second slash
                    }
                    if (sudoLine.isEmpty()) {
                        p.sendMessage(this.getMsg("usage-sudo"));
                        break;
                    }
                    boolean ran = Bukkit.dispatchCommand(st, sudoLine);
                    p.sendMessage(this.translate(ran ? "&8» &#FF9147&lSudo! &f" + st.getName() + " &7ran &f/" + sudoLine
                            : "&8» &#D9381E&lError! &7The command &f/" + sudoLine.split(" ")[0] + " &7doesn't exist."));
                    break;
                case "forcetp":
                case "tp":
                    this.handleForceTp(p, args);
                    break;
                case "forcetphere":
                    this.handleForceTpHere(p, args);
                    break;
                case "tpall": {
                    int moved = 0;
                    for (Player o : Bukkit.getOnlinePlayers()) {
                        if (o.equals(p) || o.hasPermission("unstableffa.forcetp.exempt")) {
                            continue;
                        }
                        o.teleport(p.getLocation());
                        o.sendMessage(this.forceTpMsg("tpall-target", "&7Everyone was teleported to &f{staff}&7.", p.getName(), o.getName(), p.getName()));
                        moved++;
                    }
                    p.sendMessage(this.forceTpMsg("tpall-sender", "&#FF9147Teleported &f{count} &#FF9147player(s) to you.", p.getName(), null, null).replace("{count}", String.valueOf(moved)));
                    break;
                }
            }

            return true;
        }
    }

    // =====================================================================================
    // /forcetp + /forcetphere (alias /tphere): instant staff teleports, no request needed.
    // Messages come from messages.yml (keys below) and fall back to the defaults here.
    //   {staff} = who ran the command, {player} = who got moved, {target} = destination player
    // =====================================================================================

    private String forceTpMsg(String key, String def, String staff, String player, String target) {
        Map<String, String> ph = new HashMap<>();
        ph.put("staff", staff == null ? "" : staff);
        ph.put("player", player == null ? "" : player);
        ph.put("target", target == null ? "" : target);
        return this.formatMsgMap(key, def, ph);
    }

    private Player onlineVisible(Player viewer, String name) {
        Player t = Bukkit.getPlayer(name);
        if (t == null || (this.vanished.contains(t.getUniqueId()) && !viewer.hasPermission("unstableffa.vanish"))) {
            return null;
        }
        return t;
    }

    // Reads a coordinate; "~" and "~5" are relative to "base".
    private Double coord(String in, double base) {
        try {
            if (in.startsWith("~")) {
                return base + (in.length() > 1 ? Double.parseDouble(in.substring(1)) : 0.0);
            }
            return Double.parseDouble(in);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Location coordsFrom(String[] a, int i, Location base) {
        if (a.length < i + 3) {
            return null;
        }
        Double x = this.coord(a[i], base.getX());
        Double y = this.coord(a[i + 1], base.getY());
        Double z = this.coord(a[i + 2], base.getZ());
        if (x == null || y == null || z == null) {
            return null;
        }
        Location l = new Location(base.getWorld(), x, y, z, base.getYaw(), base.getPitch());
        if (!a[i].startsWith("~") && x == Math.floor(x)) {
            l.setX(x + 0.5); // whole numbers -> middle of the block, like vanilla
        }
        if (!a[i + 2].startsWith("~") && z == Math.floor(z)) {
            l.setZ(z + 0.5);
        }
        return l;
    }

    // /forcetp <player>               -> you go to them
    // /forcetp <player> <target>      -> <player> goes to <target>   (unstableffa.forcetp.others)
    // /forcetp <x> <y> <z>            -> you go to coordinates ("~" works)
    // /forcetp <player> <x> <y> <z>   -> <player> goes to coordinates (unstableffa.forcetp.others)
    // Also runs as /tp and /ftp.
    private void handleForceTp(Player p, String[] args) {
        if (!p.hasPermission("unstableffa.forcetp")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        boolean others = p.hasPermission("unstableffa.forcetp.others");
        if (args.length < 1) {
            p.sendMessage("§cUsage: /tp <player>" + (others ? " [target]" : "") + " §7or §c/tp <x> <y> <z>");
            return;
        }

        // /tp x y z
        Location here = this.coordsFrom(args, 0, p.getLocation());
        if (here != null && args.length == 3) {
            p.teleport(here);
            p.sendMessage(this.forceTpMsg("forcetp-coords", "&#FF9147Teleported to &f{target}&#FF9147.", p.getName(), p.getName(),
                    here.getBlockX() + " " + here.getBlockY() + " " + here.getBlockZ()));
            return;
        }

        Player a = this.onlineVisible(p, args[0]);
        if (a == null) {
            p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
            return;
        }

        if (args.length == 1) {
            if (a.equals(p)) {
                p.sendMessage("§cYou can't teleport to yourself.");
                return;
            }
            p.teleport(a.getLocation());
            p.sendMessage(this.forceTpMsg("forcetp-sender", "&#FF9147Teleported to &f{target}&#FF9147.", p.getName(), p.getName(), a.getName()));
            if (this.getConfig().getBoolean("force-tp.notify-target", true)) {
                boolean hidden = this.vanished.contains(p.getUniqueId()) && !this.getConfig().getBoolean("force-tp.notify-when-vanished", false);
                if (!hidden) {
                    a.sendMessage(this.forceTpMsg("forcetp-target", "&f{staff} &7teleported to you.", p.getName(), p.getName(), a.getName()));
                }
            }
            return;
        }

        if (!others) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (!a.equals(p) && a.hasPermission("unstableffa.forcetp.exempt") && !p.hasPermission("unstableffa.admin")) {
            p.sendMessage("§cYou can't move that player.");
            return;
        }

        // /tp <player> x y z
        Location dest = this.coordsFrom(args, 1, a.getLocation());
        String destName;
        if (dest != null && args.length == 4) {
            destName = dest.getBlockX() + " " + dest.getBlockY() + " " + dest.getBlockZ();
        } else {
            Player b = this.onlineVisible(p, args[1]);
            if (b == null) {
                p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[1]));
                return;
            }
            if (a.equals(b)) {
                p.sendMessage("§cThose are the same player.");
                return;
            }
            dest = b.getLocation();
            destName = b.getName();
        }
        a.teleport(dest);
        p.sendMessage(this.forceTpMsg("forcetp-others-sender", "&#FF9147Teleported &f{player} &#FF9147to &f{target}&#FF9147.", p.getName(), a.getName(), destName));
        if (!a.equals(p)) {
            a.sendMessage(this.forceTpMsg("forcetp-others-player", "&7You were teleported to &f{target} &7by &f{staff}&7.", p.getName(), a.getName(), destName));
        }
    }

    // /forcetphere <player>  -> they come to you
    private void handleForceTpHere(Player p, String[] args) {
        if (!p.hasPermission("unstableffa.forcetphere")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length < 1) {
            p.sendMessage("§cUsage: /forcetphere <player>");
            return;
        }
        Player t = this.onlineVisible(p, args[0]);
        if (t == null) {
            p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
            return;
        }
        if (t.equals(p)) {
            p.sendMessage("§cYou can't teleport yourself to yourself.");
            return;
        }
        if (t.hasPermission("unstableffa.forcetp.exempt") && !p.hasPermission("unstableffa.admin")) {
            p.sendMessage("§cYou can't move that player.");
            return;
        }
        t.teleport(p.getLocation());
        p.sendMessage(this.forceTpMsg("forcetphere-sender", "&#FF9147Teleported &f{player} &#FF9147to you.", p.getName(), t.getName(), p.getName()));
        if (this.vanished.contains(p.getUniqueId())) {
            t.sendMessage(this.forceTpMsg("forcetphere-target-vanished", "&7You were teleported by a staff member.", p.getName(), t.getName(), p.getName()));
        } else {
            t.sendMessage(this.forceTpMsg("forcetphere-target", "&7You were teleported to &f{staff}&7.", p.getName(), t.getName(), p.getName()));
        }
    }

    // /ze help [page] - every command the sender is allowed to use, 10 per page, click one to use it.
    private void help(CommandSender s, String pageArg) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : this.getDescription().getCommands().entrySet()) {
            if (en.getKey().equals("openwarpgui") || this.commandManager.isDisabled(en.getKey())) {
                continue; // console only, or turned off in commands.disabled
            }
            Object perm = en.getValue().get("permission");
            if (perm != null && !s.hasPermission(String.valueOf(perm))) {
                continue;
            }
            Object desc = en.getValue().get("description");
            rows.add(new String[]{en.getKey(), desc == null ? "" : String.valueOf(desc)});
        }
        rows.sort((x, y) -> x[0].compareTo(y[0]));
        int page;
        try {
            page = Math.max(1, Integer.parseInt(pageArg));
        } catch (NumberFormatException ex) {
            page = 1;
        }
        int pages = Math.max(1, (rows.size() + 9) / 10);
        page = Math.min(page, pages);
        s.sendMessage(this.translate("&8&m          &r <gradient:#FF7518:#FFFFFF>&lUnstab</gradient><gradient:#FFFFFF:#FF7518>&lleFFA</gradient> &7Help &8(&f" + page + "&7/&f" + pages + "&8) &8&m          "));
        for (int i = (page - 1) * 10; i < Math.min(page * 10, rows.size()); i++) {
            String[] r = rows.get(i);
            if (s instanceof Player) {
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(this.translate("&#FF791F/" + r[0] + " &8» &7" + r[1])));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + r[0] + " "));
                ((Player) s).spigot().sendMessage(tc);
            } else {
                s.sendMessage("/" + r[0] + " - " + r[1]);
            }
        }
        if (s instanceof Player && pages > 1) {
            TextComponent nav = new TextComponent("");
            if (page > 1) {
                TextComponent prev = new TextComponent(TextComponent.fromLegacyText(this.translate("&#FF8330« Previous")));
                prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ze help " + (page - 1)));
                nav.addExtra(prev);
                nav.addExtra("   ");
            }
            if (page < pages) {
                TextComponent next = new TextComponent(TextComponent.fromLegacyText(this.translate("&#FF8330Next »")));
                next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ze help " + (page + 1)));
                nav.addExtra(next);
            }
            ((Player) s).spigot().sendMessage(nav);
        } else if (page < pages) {
            s.sendMessage("Next page: /ze help " + (page + 1));
        }
    }

    // ---------- punishment permissions ----------
    // unstableffa.punish          everything (all the commands below and /punish)
    // unstableffa.ban / tempban / unban / mute / unmute / kick / warn    one command each
    // unstableffa.mute.3hours     longest mute this person may give (also 30minutes, 1day, 1week, ...)
    // unstableffa.ban.7days       longest /ban and /tempban (unstableffa.tempban.<time> for /tempban only)
    // No time permission = no limit. op and the console have no limit. "perm" needs no time permission at all.
    private static final String[] PUNISH_CMDS = {"ban", "tempban", "unban", "mute", "unmute", "kick", "warn"};
    private static final Pattern LIMIT_NODE = Pattern.compile("(\\d+)(months?|mo|minutes?|mins?|m|hours?|hrs?|h|days?|d|weeks?|w)");

    // Can this person use any punishment command at all? (used for staff-only views and buttons)
    private boolean canPunishAny(CommandSender s) {
        if (s.hasPermission(PERM_PUNISH)) {
            return true;
        }
        for (String c : PUNISH_CMDS) {
            if (s.hasPermission("unstableffa." + c)) {
                return true;
            }
        }
        return false;
    }

    private boolean mayUse(CommandSender s, String cmd) {
        return s.hasPermission(PERM_PUNISH) || (!cmd.equals("punish") && s.hasPermission("unstableffa." + cmd));
    }

    // The longest time this sender may mute/ban for, in milliseconds. Long.MAX_VALUE = no limit.
    private long punishLimit(CommandSender s, String cmd) {
        if (!(s instanceof Player) || s.isOp()) {
            return Long.MAX_VALUE;
        }
        String[] prefixes = cmd.equals("tempban") ? new String[]{"tempban", "ban"} : new String[]{cmd};
        boolean any = false;
        long best = 0L;
        for (org.bukkit.permissions.PermissionAttachmentInfo pai : s.getEffectivePermissions()) {
            if (!pai.getValue()) {
                continue;
            }
            String node = pai.getPermission().toLowerCase(Locale.ROOT);
            for (String pre : prefixes) {
                String start = "unstableffa." + pre + ".";
                if (!node.startsWith(start)) {
                    continue;
                }
                String rest = node.substring(start.length());
                long ms;
                if (rest.equals("*") || rest.equals("perm") || rest.equals("permanent") || rest.equals("unlimited") || rest.equals("forever")) {
                    ms = Long.MAX_VALUE;
                } else {
                    Matcher m = LIMIT_NODE.matcher(rest);
                    if (!m.matches()) {
                        continue;
                    }
                    long v = Long.parseLong(m.group(1));
                    String u = m.group(2);
                    long unit = u.startsWith("mo") ? 2592000000L : u.startsWith("m") ? 60000L : u.startsWith("h") ? 3600000L
                            : u.startsWith("d") ? 86400000L : 604800000L;
                    ms = v * unit;
                }
                any = true;
                best = Math.max(best, ms);
            }
        }
        return any ? best : Long.MAX_VALUE;
    }

    private void handlePunishment(CommandSender s, String cmd, String[] args) {
        this.handlePunishment(s, cmd, args, null);
    }

    // staffLabel lets automatic actions (warn escalation) show up as "AutoMod" instead of "CONSOLE".
    private void handlePunishment(CommandSender s, String cmd, String[] args, String staffLabel) {
        if (!this.mayUse(s, cmd)) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length < 1) {
            s.sendMessage(this.getMsg("usage-" + cmd));
            return;
        }

        String staff = staffLabel != null ? staffLabel : s.getName();
        OfflinePlayer target = this.resolveOffline(args[0]);
        String targetName = this.displayName(target, args[0]);
        if (this.isOwner(targetName)) {
            s.sendMessage("§cYou cannot punish the server owner!");
            return;
        }

        String actionType = cmd.toUpperCase();
        String duration = "perm";
        String displayReason = "Violation of Rules";
        String offensePath = null; // /punish offense counter, only saved once every check has passed
        int offenseNow = 0;

        if (cmd.equals("punish")) {
            if (args.length < 2) {
                s.sendMessage(this.getMsg("usage-punish"));
                return;
            }

            String rIn = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("_", " ");
            ConfigurationSection durSec = this.getConfig().getConfigurationSection("punishment-durations");
            String configKey = null;
            if (durSec != null) {
                for (String k : durSec.getKeys(false)) {
                    if (k.equalsIgnoreCase(rIn)) {
                        configKey = k;
                        break;
                    }
                }
            }

            if (configKey == null) {
                s.sendMessage("§cInvalid reason category.");
                return;
            }

            ConfigurationSection sec = durSec.getConfigurationSection(configKey);
            actionType = sec.getString("type", "BAN").toUpperCase();
            List<String> lvls = sec.getStringList("levels");
            if (lvls.isEmpty()) {
                s.sendMessage("§cThat category has no levels configured.");
                return;
            }
            offensePath = "history." + target.getUniqueId() + "." + configKey.toLowerCase().replace(" ", "_");
            int prior = this.data.getInt(offensePath, 0);
            duration = prior >= lvls.size() ? lvls.get(lvls.size() - 1) : lvls.get(prior);
            displayReason = configKey.replace("_", " ") + " (Offense #" + (prior + 1) + ")";
            offenseNow = prior + 1;
        } else if (cmd.matches("ban|mute|tempban")) {
            if (args.length < 3) {
                s.sendMessage(this.getMsg("usage-" + cmd));
                return;
            }

            duration = args[1];
            displayReason = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).replace("_", " ");
        } else if (cmd.matches("kick|warn|unban|unmute")) {
            displayReason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("_", " ") : "Staff Action";
        }

        long exp = duration.equalsIgnoreCase("perm") ? -1L : this.parseTime(duration);
        boolean isPerm = exp == -1L;
        if (cmd.matches("mute|ban|tempban")) {
            long max = this.punishLimit(s, cmd);
            if (max != Long.MAX_VALUE && (isPerm || exp > max)) {
                String what = cmd.equals("mute") ? "mute" : "ban";
                s.sendMessage(this.color(isPerm
                        ? "&8» &#D9381E&lDenied! &7You can't give a permanent " + what + ". Your limit is &f" + this.duration(max) + "&7."
                        : "&8» &#D9381E&lDenied! &7You can only " + what + " for up to &f" + this.duration(max) + "&7."));
                return;
            }
        }
        // Daily limit per staff member (staff.yml -> punish-limits). Counted further down, once everything passed.
        String limitAction = cmd.equals("unban") || cmd.equals("unmute") ? null
                : actionType.contains("BAN") ? "ban" : actionType.contains("MUTE") ? "mute"
                : actionType.equals("KICK") ? "kick" : actionType.equals("WARN") ? "warn" : null;
        if (staffLabel == null && !this.punishLimits.allow(s, limitAction)) {
            return;
        }
        String label = (isPerm ? "Permanent " : "Temporary ") + (actionType.contains("BAN") ? "Ban" : "Mute");
        if (!isPerm) {
            label = label + " (" + duration + ")";
        }

        if (cmd.equals("kick")) {
            label = "Kicked";
        }

        if (cmd.equals("warn")) {
            label = "Warning";
        }

        if (cmd.equals("unban")) {
            label = "Unbanned";
        }

        if (cmd.equals("unmute")) {
            label = "Unmuted";
        }

        if (actionType.contains("BAN") && !cmd.equals("unban") && !cmd.equals("kick") && !cmd.equals("warn")) {
            if (Bukkit.getBanList(Type.NAME).isBanned(targetName)) {
                s.sendMessage("§cThat player is already banned!");
                return;
            }
        } else if (cmd.equals("unban")) {
            if (!Bukkit.getBanList(Type.NAME).isBanned(targetName)) {
                s.sendMessage("§cThat player is not banned!");
                return;
            }
        } else if (actionType.contains("MUTE") && !cmd.equals("unmute")) {
            if (this.isMutedUuid(target.getUniqueId())) {
                s.sendMessage("§cThat player is already muted!");
                return;
            }
        } else if (cmd.equals("unmute") && !this.isMutedUuid(target.getUniqueId())) {
            s.sendMessage("§cThat player is not muted!");
            return;
        }

        if (cmd.equals("kick") && !target.isOnline()) {
            s.sendMessage("§cThat player is not online!");
            return;
        }

        // Every check passed - now it's safe to count the offense.
        if (staffLabel == null) {
            this.punishLimits.count(s, limitAction);
        }
        if (offensePath != null) {
            this.data.set(offensePath, offenseNow);
            this.saveD();
        }

        // Show the staff member what this player already has before the new punishment lands.
        if (!cmd.equals("unban") && !cmd.equals("unmute")) {
            int[] st = this.getStats(target.getUniqueId());
            if (st[0] + st[1] + st[2] + st[3] + st[4] > 0) {
                s.sendMessage("§8[§bHistory§8] §f" + targetName + " §7has §c" + st[0] + " ban(s)§7, §6" + st[1] + " mute(s)§7, §e" + st[2]
                        + " kick(s)§7, §b" + st[3] + " active warn(s)§7. §8(§f/history " + targetName + "§8)");
            }
        }

        this.broadcastPunish(targetName, label, staff, displayReason);

        boolean showDuration = cmd.equals("ban") || cmd.equals("tempban") || cmd.equals("mute") || cmd.equals("punish");
        String durationText = showDuration ? (isPerm ? "Permanent" : duration) : null;

        boolean isBanning = actionType.contains("BAN") && !cmd.equals("unban");
        String banId = isBanning ? this.newBanId() : null;

        // Log it to the player's punishment history (/history).
        String logAction = cmd.equals("punish") ? actionType : cmd.toUpperCase();
        if (logAction.equals("BAN") && !isPerm) {
            logAction = "TEMPBAN";
        }
        this.recordPunishment(target, args[0], logAction, durationText, staff, this.stripColors(displayReason), banId);

        // Bans / tempbans / unbans get the richer ban embed (Ban ID, Appeal button) from BanLog instead.
        if (!this.banLog.handles(logAction)) {
            this.sendDiscordWebhook(cmd, label, targetName, staff, this.stripColors(displayReason), durationText,
                    this.historySummary(target.getUniqueId()));
        }

        if (isBanning) {
            // Placeholders available to ban-screen-format / ban-kick-format in messages.yml:
            // {reason}, {duration}, {staff}, {player}, {banid}
            Map<String, String> ph = new HashMap<>();
            ph.put("reason", displayReason);
            ph.put("duration", isPerm ? "Permanent" : duration);
            ph.put("staff", staff);
            ph.put("player", targetName);
            ph.put("banid", banId);

            // This is the message shown on the "Multiplayer" disconnect screen when a
            // banned player tries to connect / click "Join Server". The ban ID and the
            // appeal line (config.yml: appeal-info) are added at the bottom automatically.
            String banScreenMsg = this.formatMsgMap("ban-screen-format",
                    "&#D9381E&lYOU ARE BANNED FROM THIS SERVER\n&7Reason: &f{reason}\n&7Duration: &f{duration}\n&7Banned by: &f{staff}",
                    ph) + this.banFooter(banId);

            Bukkit.getBanList(Type.NAME).addBan(targetName, banScreenMsg, isPerm ? null : new Date(System.currentTimeMillis() + exp), staff);

            if (target.isOnline()) {
                // This is what the player sees immediately if they were online when banned.
                String banKickMsg = this.formatMsgMap("ban-kick-format",
                        "&#D9381E&lYOU HAVE BEEN BANNED\n&7Reason: &f{reason}\n&7Duration: &f{duration}",
                        ph) + this.banFooter(banId);
                target.getPlayer().kickPlayer(banKickMsg);
            }
        } else if (actionType.contains("MUTE") && !cmd.equals("unmute")) {
            long muteUntil = isPerm ? -1L : System.currentTimeMillis() + exp;
            this.data.set("mutes." + target.getUniqueId(), muteUntil);
            this.voiceMuteCache.put(target.getUniqueId(), muteUntil);
            this.saveD();
            if (target.isOnline() && target.getPlayer() != null && this.voiceChatHooked) {
                target.getPlayer().sendMessage(this.formatMsg("voicechat-muted-notice",
                        "&#D9381EYou are also muted in voice chat.", null, null, null));
            }
        } else if (cmd.equals("unban")) {
            Bukkit.getBanList(Type.NAME).pardon(targetName);
        } else if (cmd.equals("unmute")) {
            this.data.set("mutes." + target.getUniqueId(), (Object)null);
            this.voiceMuteCache.remove(target.getUniqueId());
            this.saveD();
        } else if (cmd.equals("kick") && target.isOnline()) {
            // Placeholders available to kick-format in messages.yml: {reason}, {staff}, {player}
            Map<String, String> ph = new HashMap<>();
            ph.put("reason", displayReason);
            ph.put("staff", staff);
            ph.put("player", targetName);

            String kickMsg = this.formatMsgMap("kick-format",
                    "&#D9381E&lYOU HAVE BEEN KICKED\n&7Reason: &f{reason}\n&7By: &f{staff}",
                    ph);
            target.getPlayer().kickPlayer(kickMsg);
        }

        if (cmd.equals("warn")) {
            int active = this.getStats(target.getUniqueId())[3];
            if (target.isOnline() && target.getPlayer() != null) {
                target.getPlayer().sendMessage("§eYou now have §c" + active + " §eactive warning(s).");
            }
            this.checkWarnEscalation(target, targetName);
        }
    }

    private long parseTime(String in) {
        long ms = 0L;
        // s = seconds, m = minutes, h = hours, d = days, w = weeks, y = years (e.g. "1w", "1d12h")
        Matcher m = Pattern.compile("(\\d+)([smhdwy])").matcher(in.toLowerCase());

        while(m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s":
                    ms += v * 1000L;
                    break;
                case "m":
                    ms += v * 60000L;
                    break;
                case "h":
                    ms += v * 3600000L;
                    break;
                case "d":
                    ms += v * 86400000L;
                    break;
                case "w":
                    ms += v * 604800000L;
                    break;
                case "y":
                    ms += v * 31536000000L;
                    break;
            }
        }

        return ms == 0L ? 3600000L : ms;
    }

    // ---------- data.yml safety ----------
    // data.yml lives in plugins/UnstableFFA/, not inside the jar, so a new jar never touches it.
    // Two extra guards so it can never get wiped:
    //   1. If data.yml can't be read (a typo after editing it by hand), it is NOT overwritten:
    //      the plugin keeps the file as it is, makes a copy and refuses to save until it's fixed.
    //   2. Saving writes to data.yml.tmp first and then swaps it in, so a crash mid-save can't
    //      leave a half-written file. data.yml.bak is a copy from the last start.
    private boolean dataBroken;
    private boolean dataBackedUp;

    private YamlConfiguration loadDataSafely() {
        YamlConfiguration y = new YamlConfiguration();
        this.dataBroken = false;
        if (!this.dataFile.exists() || this.dataFile.length() == 0L) {
            return y;
        }
        try {
            y.load(this.dataFile);
            if (!this.dataBackedUp) {
                java.nio.file.Files.copy(this.dataFile.toPath(), new File(this.getDataFolder(), "data.yml.bak").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                this.dataBackedUp = true;
            }
        } catch (Exception ex) {
            this.dataBroken = true;
            File copy = new File(this.getDataFolder(), "data.yml.broken-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.copy(this.dataFile.toPath(), copy.toPath());
            } catch (IOException ignored) {
            }
            this.getLogger().severe("data.yml could not be read: " + ex.getMessage());
            this.getLogger().severe("Your data is NOT deleted. Saving is paused until data.yml is fixed (copy: " + copy.getName()
                    + ", last good copy: data.yml.bak). Fix it and run /ze reload.");
        }
        return y;
    }

    private void saveD() {
        if (this.dataBroken || this.data == null) {
            return; // never overwrite a data.yml we couldn't read
        }
        try {
            File tmp = new File(this.getDataFolder(), "data.yml.tmp");
            this.data.save(tmp);
            try {
                java.nio.file.Files.move(tmp.toPath(), this.dataFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(tmp.toPath(), this.dataFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            this.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // Sends a Discord embed via webhook for a punishment action. Runs async so it never
    // blocks the main thread. actionKey is the command name (ban, tempban, unban, mute,
    // unmute, kick, warn, punish) and is used both as the config toggle key and color key.
    private void sendDiscordWebhook(String actionKey, String title, String target, String admin, String reason, String duration, String historyLine) {
        this.discord.punishment(actionKey, title, target, admin, reason, duration, historyLine);
    }

    // ---------------------------------------------------------------------------------
    // Discord "server status" embed (Online / Maintenance / Offline + player & staff counts)
    // ---------------------------------------------------------------------------------

    // Formats a millisecond duration as "1d 2h 3m" style uptime text.
    private String formatUptime(long ms) {
        long totalSeconds = ms / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");
        return sb.toString();
    }

    // Paper exposes TPS / MSPT; Spigot doesn't. Read them with reflection so both work.
    private double[] serverTps() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            if (r instanceof double[]) {
                return (double[]) r;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private double serverMspt() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getAverageTickTime").invoke(Bukkit.getServer());
            if (r instanceof Number) {
                return ((Number) r).doubleValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String tpsEmoji(double tps) {
        return tps >= 18.0 ? "🟢" : (tps >= 15.0 ? "🟡" : "🔴");
    }

    // Discord renders <t:unix:R> as "5 minutes ago" in every viewer's own timezone.
    private String discordTime(long millis, String style) {
        return "<t:" + (millis / 1000L) + ":" + style + ">";
    }

    private String jsonField(String name, String value, boolean inline) {
        return DiscordWebhooks.field(name, value, inline);
    }

    // Joins names into a compact list, cut off after "max" names.
    private String nameList(List<String> names, int max) {
        if (names.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String n : sorted) {
            if (shown >= max) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('`').append(n.replace("`", "")).append('`');
            shown++;
        }
        if (sorted.size() > max) {
            sb.append(" *+").append(sorted.size() - max).append(" more*");
        }
        return sb.toString();
    }

    // Builds the full webhook payload (username/avatar + one embed) describing the server.
    // Must run on the main thread (it reads players, permissions and data.yml).
    // "offline" is true only when called from onDisable (server is actually shutting down).
    private String buildStatusEmbed(boolean offline) {
        String cfg = "discord-webhook.status-embed.";
        boolean maintenance = this.lockdownActive;
        long now = System.currentTimeMillis();

        String statusWord = offline ? "Offline" : (maintenance ? "Maintenance" : "Online");
        String statusEmoji = offline ? "🔴" : (maintenance ? "🟠" : "🟢");
        int color = offline ? 0xED4245 : (maintenance ? 0xFEA500 : 0x57F287);

        // ----- players -----
        List<String> visible = new ArrayList<>();
        List<String> staffNames = new ArrayList<>();
        String staffPerm = this.getConfig().getString(cfg + "staff-permission", "unstableffa.staffcount");
        if (!offline) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (this.vanished.contains(pl.getUniqueId())) {
                    continue;
                }
                visible.add(pl.getName());
                if (pl.hasPermission(staffPerm)) {
                    staffNames.add(pl.getName());
                }
            }
        }
        int online = visible.size();
        int max = Bukkit.getMaxPlayers();
        this.peakPlayers = Math.max(this.peakPlayers, online);

        // ----- community numbers from data.yml -----
        int known = 0;
        int newToday = 0;
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        ConfigurationSection us = this.data.getConfigurationSection("users");
        if (us != null) {
            for (String k : us.getKeys(false)) {
                known++;
                if (us.getString(k + ".first-join", "").startsWith(today)) {
                    newToday++;
                }
            }
        }
        int punish24h = 0;
        long dayAgo = now - 86400000L;
        ConfigurationSection ps = this.data.getConfigurationSection("punishlog");
        if (ps != null) {
            for (String uuid : ps.getKeys(false)) {
                for (String line : this.data.getStringList("punishlog." + uuid)) {
                    String[] p = this.parseEntry(line);
                    if (!p[1].matches("BAN|TEMPBAN|IPBAN|MUTE|KICK|WARN")) {
                        continue;
                    }
                    try {
                        if (Long.parseLong(p[0]) >= dayAgo) {
                            punish24h++;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        // ----- description -----
        String address = this.getConfig().getString(cfg + "server-address", "");
        StringBuilder desc = new StringBuilder();
        if (offline) {
            desc.append(this.getConfig().getString(cfg + "text-offline", "The server is **offline**. Check back soon!"));
            desc.append("\nWent offline ").append(this.discordTime(now, "R"));
        } else if (maintenance) {
            desc.append(this.getConfig().getString(cfg + "text-maintenance", "🚧 The server is in **maintenance** - only staff can join right now."));
        } else {
            desc.append(this.getConfig().getString(cfg + "text-online", "The server is **up** - come play!"));
        }
        if (address != null && !address.isEmpty()) {
            desc.append("\n\n**IP:** `").append(address).append('`');
        }

        // ----- fields -----
        List<String> f = new ArrayList<>();
        f.add(this.jsonField("Status", statusEmoji + " **" + statusWord + "**", true));
        f.add(this.jsonField("Players", offline ? "**0** / " + max : "**" + online + "** / " + max, true));
        f.add(this.jsonField("Uptime", offline
                ? "Ran for **" + this.formatUptime(now - this.serverStartTime) + "**"
                : "**" + this.formatUptime(now - this.serverStartTime) + "**\nsince " + this.discordTime(this.serverStartTime, "R"), true));

        if (!offline && this.getConfig().getBoolean(cfg + "show-performance", true)) {
            double[] tps = this.serverTps();
            if (tps != null && tps.length > 0) {
                double t1 = Math.min(20.0, tps[0]);
                double mspt = this.serverMspt();
                f.add(this.jsonField("TPS", this.tpsEmoji(t1) + " **" + String.format(java.util.Locale.US, "%.1f", t1) + "**"
                        + (mspt >= 0 ? "\n" + String.format(java.util.Locale.US, "%.1f", mspt) + " ms/tick" : ""), true));
            }
            f.add(this.jsonField("Peak", "**" + this.peakPlayers + "** since restart", true));
        }

        f.add(this.jsonField("Staff online - " + staffNames.size(),
                staffNames.isEmpty() ? "*No staff online right now.*" : this.nameList(staffNames, 15), false));

        if (this.getConfig().getBoolean(cfg + "show-community", true)) {
            f.add(this.jsonField("Total players", "**" + known + "**", true));
            f.add(this.jsonField("New today", "**" + newToday + "**", true));
            f.add(this.jsonField("Punishments (24h)", "**" + punish24h + "**", true));
        }

        String version = this.getConfig().getString(cfg + "version-text", "1.7 - Latest");
        if (version == null || version.trim().isEmpty()) {
            version = "1.7 - Latest";
        }
        f.add(this.jsonField("Version", "`" + version + "`", true));

        // ----- embed -----
        String titleText = this.getConfig().getString(cfg + "title", "Server Status");
        String footer = this.messages.getString("discord-embed-footer", "UnstableFFA");
        int interval = Math.max(1, this.getConfig().getInt(cfg + "update-interval-minutes", 5));
        String thumbnailUrl = this.getConfig().getString(cfg + "thumbnail-url", "");
        String imageUrl = this.getConfig().getString(cfg + "image-url", "");

        StringBuilder embed = new StringBuilder("{");
        embed.append("\"title\":\"").append(this.jsonEscape(titleText)).append("\",");
        embed.append("\"description\":\"").append(this.jsonEscape(desc.toString())).append("\",");
        embed.append("\"color\":").append(color).append(",");
        if (thumbnailUrl != null && thumbnailUrl.startsWith("http")) {
            embed.append("\"thumbnail\":{\"url\":\"").append(this.jsonEscape(thumbnailUrl)).append("\"},");
        }
        if (imageUrl != null && imageUrl.startsWith("http")) {
            embed.append("\"image\":{\"url\":\"").append(this.jsonEscape(imageUrl)).append("\"},");
        }
        embed.append("\"fields\":[").append(String.join(",", f)).append("],");
        embed.append("\"timestamp\":\"").append(Instant.now().toString()).append("\",");
        embed.append("\"footer\":{\"text\":\"").append(this.jsonEscape(footer + (offline ? "" : " • updates every " + interval + "m"))).append("\"}");
        embed.append("}");

        return embed.toString();
    }

    // Builds the status embed on the main thread (it reads players and permissions); DiscordWebhooks
    // then posts it once and edits that same message every time after.
    private void updateStatusEmbed(boolean offline) {
        if (this.discord == null || !this.getConfig().getBoolean("discord-webhook.status-embed.enabled", false)) {
            return;
        }
        this.discord.updateStatus(this.buildStatusEmbed(offline), true);
    }

    private void updateStatusEmbedAsync(boolean offline) {
        if (this.discord == null || !this.getConfig().getBoolean("discord-webhook.status-embed.enabled", false)) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this, () -> this.updateStatusEmbedAsync(offline));
            return;
        }
        this.discord.updateStatus(this.buildStatusEmbed(offline), false);
    }

    private String jsonEscape(String s) {
        return DiscordWebhooks.esc(s);
    }

    private String stripColors(String s) {
        if (s == null) return "";
        return org.bukkit.ChatColor.stripColor(this.translate(s)).replaceAll("&[0-9a-fk-orA-FK-OR#]", "");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        // Menus that asked the player to type something (report reason, kit cooldown, ...).
        final java.util.function.Consumer<String> input = this.chatInput.remove(e.getPlayer().getUniqueId());
        if (input != null) {
            e.setCancelled(true);
            final String text = e.getMessage().trim();
            final Player ip = e.getPlayer();
            Bukkit.getScheduler().runTask(this, () -> {
                if (text.equalsIgnoreCase("cancel")) {
                    ip.sendMessage("§7Cancelled.");
                    return;
                }
                input.accept(text);
            });
            return;
        }

        // Screenshare: the player and the staff member only talk to each other.
        if (this.screenShare.onChat(e)) {
            return;
        }

        // A player who clicked "Search" in the /seen browser: their next message is the search text.
        Gui pending = this.searchPending.remove(e.getPlayer().getUniqueId());
        if (pending != null) {
            e.setCancelled(true);
            final String text = e.getMessage().trim();
            final Player sp = e.getPlayer();
            Bukkit.getScheduler().runTask(this, () -> {
                if (!text.equalsIgnoreCase("cancel")) {
                    pending.search = text;
                    pending.page = 0;
                }
                this.renderGui(pending, sp);
                sp.openInventory(pending.inv);
            });
            return;
        }

        if (this.inStaffChat.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            String var10000 = e.getPlayer().getName();
            String m = "§8[§bSTAFF§8] §f" + var10000 + " §7» §b" + e.getMessage();
            final Player scp = e.getPlayer();
            Bukkit.getScheduler().runTask(this, () -> this.staffActivity.onStaffChat(scp));
            Bukkit.getOnlinePlayers().stream().filter((p) -> p.hasPermission("unstableffa.staffchat")).forEach((p) -> p.sendMessage(m));
        } else if (this.chatLocked && !e.getPlayer().hasPermission("unstableffa.mutechat.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(this.formatMsg("mutechat-blocked", "&#D9381EChat is muted right now.", null, null, null));
        } else if (isMuted(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(this.getMsg("mute-msg"));
        } else {
            final Player cp = e.getPlayer();

            // 1) chat cooldown + "don't repeat yourself"
            String deny = this.chatGuard.checkChat(cp, e.getMessage());
            if (deny != null) {
                e.setCancelled(true);
                cp.sendMessage(deny);
                return;
            }

            // 1b) flood filter: repeated letters/words, symbol spam, over-long messages
            ChatGuard.FloodResult flood = this.chatGuard.applyFlood(cp, e.getMessage());
            if (flood != null) {
                String fn = this.chatGuard.floodNotice(flood.blocked);
                if (fn != null) {
                    cp.sendMessage(fn);
                }
                if (flood.strike) {
                    final String dur = this.getConfig().getString("chat-flood.strikes.duration", "5m");
                    final String why = this.getConfig().getString("chat-flood.strikes.reason", "Chat spam");
                    Bukkit.getScheduler().runTask(this, () -> {
                        List<String> a = new ArrayList<>(Arrays.asList(cp.getName(), dur));
                        a.addAll(Arrays.asList(why.split(" ")));
                        this.handlePunishment(Bukkit.getConsoleSender(), "mute", a.toArray(new String[0]), "AutoMod");
                    });
                }
                if (flood.blocked) {
                    e.setCancelled(true);
                    return;
                }
                e.setMessage(flood.text);
            }

            // 2) slur filter: never sent, automatic warning
            if (this.getConfig().getBoolean("chat-filter.enabled", true)
                    && !cp.hasPermission("unstableffa.filter.bypass")
                    && this.chatGuard.containsBlockedWord(e.getMessage())) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(this, () -> this.autoWarn(cp, "Chat filter: blocked word"));
                return;
            }

            // 3) anti-cuss: censor (or block) swear words
            ChatGuard.CussResult cuss = this.chatGuard.applyAntiCuss(cp, e.getMessage());
            if (cuss != null) {
                if (cuss.blocked) {
                    e.setCancelled(true);
                } else {
                    e.setMessage(cuss.text);
                }
                String notice = this.chatGuard.cussNotice(cuss.blocked);
                if (notice != null) {
                    cp.sendMessage(notice);
                }
                if (cuss.autoWarn) {
                    Bukkit.getScheduler().runTask(this, () -> this.autoWarn(cp, "Swearing"));
                }
            }
        }
    }

    // Blocks muted players from using /msg, /me, /tell, /w, /reply, etc. to bypass the chat mute,
    // and enforces "private messages OFF" + vanish for /msg, /message, /tell, /w, /whisper.
    @EventHandler(priority = EventPriority.LOW)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String raw = e.getMessage().substring(1); // strip leading '/'
        String[] parts = raw.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }

        // Command cooldowns (config.yml -> command-cooldowns)
        String wait = this.chatGuard.checkCommand(p, parts[0]);
        if (wait != null) {
            e.setCancelled(true);
            p.sendMessage(wait);
            return;
        }

        String base = parts[0].toLowerCase();
        int colon = base.indexOf(':');
        if (colon != -1) {
            base = base.substring(colon + 1);
        }

        if (!this.inStaffChat.contains(p.getUniqueId()) && MUTED_BLOCKED_COMMANDS.contains(base) && this.isMuted(p)) {
            e.setCancelled(true);
            p.sendMessage(this.getMsg("mute-msg"));
            return;
        }

        if (PRIVATE_MESSAGE_COMMANDS.contains(base) && parts.length >= 2) {
            Player t = Bukkit.getPlayerExact(parts[1]);
            if (t == null || t.equals(p)) {
                return;
            }
            if (this.vanished.contains(t.getUniqueId()) && !p.hasPermission("unstableffa.vanish")) {
                e.setCancelled(true);
                p.sendMessage("§cNo player was found");
                return;
            }
            if (this.isMsgDisabled(t) && !p.hasPermission("unstableffa.msg.bypass")) {
                e.setCancelled(true);
                p.sendMessage("§cThat player has private messages turned off.");
                return;
            }
            if (this.hasBlocked(p.getUniqueId(), t.getUniqueId()) || this.hasIgnored(p.getUniqueId(), t.getUniqueId())) {
                e.setCancelled(true);
                p.sendMessage("§cYou blocked or ignored that player.");
                return;
            }
            if (this.hidesChatFrom(t.getUniqueId(), p.getUniqueId()) && !p.hasPermission("unstableffa.msg.bypass")) {
                e.setCancelled(true);
                p.sendMessage("§cThat player isn't accepting your messages.");
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (this.frozen.contains(e.getPlayer().getUniqueId())) {
            // Frozen: looking around is free and falling is allowed. Only walking or jumping is undone,
            // and their walk speed is 0, so that almost never happens (no stutter).
            Location f = e.getFrom();
            Location to = e.getTo();
            if (to != null && (f.getX() != to.getX() || f.getZ() != to.getZ() || to.getY() > f.getY())) {
                Location back = f.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
            }
        } else {
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
                BukkitTask t = (BukkitTask)this.activeTeleports.remove(e.getPlayer().getUniqueId());
                if (t != null) {
                    t.cancel();
                    e.getPlayer().sendMessage(this.getMsg("teleport-cancelled"));
                }
            }

        }
    }

    @EventHandler
    public void onDmg(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && this.god.contains(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }

    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (this.getConfig().getBoolean("hide-invisible-killers", false)) {
            Player victim = e.getEntity();
            Player killer = victim.getKiller();
            if (killer != null && killer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                String msg = e.getDeathMessage();
                if (msg != null && msg.contains(killer.getName())) {
                    e.setDeathMessage(msg.replace(killer.getName(), "Someone"));
                }
            }

        }
    }

    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        List<String> sug = new ArrayList<>();
        String n = c.getName().toLowerCase();
        String last = args[args.length - 1].toLowerCase();
        if (this.commandManager != null && this.commandManager.isDisabled(n)) {
            return sug;
        }
        if (n.equals("announce")) {
            return this.announcer.tab(s, args);
        }
        if (n.equals("announcerestart")) {
            return this.announcer.tabRestart(s, args);
        }
        if (n.equals("commandspy") || n.equals("modmode") || n.equals("rules") || n.equals("guide") || n.equals("staffguide")) {
            return new ArrayList<>();
        }
        if (n.equals("warps")) {
            return this.warps.tab(s, args);
        }
        if (n.equals("stats")) {
            return this.statsMenu.tab(s, args);
        }
        if (n.equals("combat")) {
            return this.combat.tab(s, args);
        }
        if (n.equals("welcome")) {
            return this.welcome.tab(s, args);
        }
        if (n.equals("discord")) {
            return new ArrayList<>();
        }
        if (n.equals("kickall")) {
            return s.hasPermission("unstableffa.kickall") && args.length == 1
                    ? new ArrayList<>(Arrays.asList("confirm")) : new ArrayList<>();
        }
        if (n.equals("zenithmenu")) {
            return this.zenithMenu.tab(s, args);
        }
        if (n.equals("leaderboard")) {
            return this.leaderboards.tab(s, args);
        }
        if (n.equals("banlog")) {
            return this.banLog.tab(s, args);
        }
        if (n.equals("lagfinder")) {
            return this.lagFinder.tab(s, args);
        }
        if (n.matches("livelink|quests|rewards|chatlog|banevasion|staffactivity|ss|punishlimits")) {
            return this.moduleTab(s, n, args).stream().distinct()
                    .filter((str) -> str.toLowerCase().startsWith(last)).collect(Collectors.toList());
        }
        if (args.length == 1) {
            if (n.equals("unstableffa")) {
                sug.add("help");
                sug.add("reload");
                sug.add("backup");
                if (s.hasPermission("unstableffa.admin")) {
                    sug.add("debug");
                    sug.add("killstreak");
                }
                if (s.hasPermission(ConfigCommand.PERMISSION)) {
                    sug.add("config");
                }
            } else if (n.equals("reports")) {
                sug.add("clear");
            } else if (n.equals("kit")) {
                sug.addAll(this.kitNames());
                sug.add("preview");
                sug.add("list");
                if (s.hasPermission("unstableffa.kit.admin")) {
                    sug.addAll(Arrays.asList("create", "edit", "delete", "give", "reset"));
                }
            } else if (n.equals("clearlag")) {
                sug.addAll(Arrays.asList("items", "mobs", "all", "status"));
            } else if (n.equals("near")) {
                sug.addAll(Arrays.asList("50", "100", "200", "500"));
            } else if (n.matches("block|ignore")) {
                sug.add("list");
                if (s instanceof Player && n.equals("ignore")) {
                    sug.addAll(this.relationNames(this.ignoreMap, ((Player) s).getUniqueId()));
                }
                sug.addAll(this.visibleOnlineNames(s));
                if (s instanceof Player) {
                    sug.remove(s.getName());
                }
            } else if (n.equals("unblock") && s instanceof Player) {
                sug.addAll(this.relationNames(this.blockMap, ((Player) s).getUniqueId()));
            } else if (n.equals("unignore") && s instanceof Player) {
                sug.addAll(this.relationNames(this.ignoreMap, ((Player) s).getUniqueId()));
            } else if (n.equals("unban")) {
                Bukkit.getBanList(Type.NAME).getBanEntries().forEach((entry) -> sug.add(entry.getTarget()));
            } else if (n.equals("unmute")) {
                // Only players who are muted right now.
                ConfigurationSection ms = this.data.getConfigurationSection("mutes");
                if (ms != null) {
                    for (String key : ms.getKeys(false)) {
                        try {
                            UUID id = UUID.fromString(key);
                            if (!this.isMutedUuid(id)) {
                                continue; // expired
                            }
                            String nm = Bukkit.getOfflinePlayer(id).getName();
                            if (nm == null) {
                                nm = this.nameOf(key);
                            }
                            sug.add(nm);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            } else if (n.matches("history|invsee|enderchest|seen|whois|playtime")) {
                sug.addAll(this.suggestableNames(s));
            } else if (n.equals("unwarn")) {
                // Only players who have an active warn.
                ConfigurationSection ps = this.data.getConfigurationSection("punishlog");
                if (ps != null) {
                    for (String key : ps.getKeys(false)) {
                        try {
                            if (this.getStats(UUID.fromString(key))[3] > 0) {
                                sug.add(this.nameOf(key));
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            } else if (n.matches("pardonoffense|note|alts|ipban|clearhistory")) {
                sug.addAll(this.suggestableNames(s));
            } else if (n.equals("unipban")) {
                ConfigurationSection ips = this.data.getConfigurationSection("ipbans");
                if (ips != null && s.hasPermission(PERM_IP_VIEW)) {
                    for (String key : ips.getKeys(false)) {
                        String entry = this.data.getString("ipbans." + key);
                        if (entry != null) {
                            sug.add(entry.split("\\|", 2)[0]);
                        }
                    }
                }
                sug.addAll(this.knownNames());
            } else if (n.equals("zenithsettings")) {
                if (s instanceof Player) {
                    sug.addAll(this.settingKeys((Player) s));
                }
            } else if (n.equals("nick")) {
                sug.add("off");
                if (s.hasPermission("unstableffa.nick.others")) {
                    sug.addAll(this.visibleOnlineNames(s));
                }
            } else if (n.equals("live")) {
                sug.addAll(this.visibleOnlineNames(s));
            } else if (n.equals("staffstats")) {
                sug.addAll(Arrays.asList("7", "30", "90"));
            } else if (n.matches("home|delhome") && s instanceof Player) {
                ConfigurationSection hs = this.data.getConfigurationSection("homes." + String.valueOf(((Player)s).getUniqueId()));
                if (hs != null) {
                    sug.addAll(hs.getKeys(false));
                }
            } else if (n.equals("warp")) {
                ConfigurationSection ws = this.data.getConfigurationSection("warps");
                if (ws != null) {
                    for (String w : ws.getKeys(false)) {
                        if (s.hasPermission("unstableffa.warp." + w.toLowerCase().replace(" ", "_")) || s.isOp()) {
                            sug.add(w);
                        }
                    }
                }
            } else if (n.equals("delwarp")) {
                ConfigurationSection ws = this.data.getConfigurationSection("warps");
                if (ws != null) {
                    sug.addAll(ws.getKeys(false));
                }
            } else if (n.matches("punish|report|freeze|msg|sudo|tpa|tpahere|forcetp|tp|forcetphere|tpall|ban|tempban|mute|kick|warn|heal|feed|god|vanish|fly|flyspeed|walkspeed|ping|openwarpgui|lockdown")) {
                // Vanished staff don't show up in suggestions for players who can't see them.
                sug.addAll(this.visibleOnlineNames(s));
            }
        } else if (args.length >= 2) {
            if (n.equals("unstableffa") && args[0].equalsIgnoreCase("config")) {
                sug.addAll(this.configCommand.complete(s, args));
            } else if (n.equals("punish") || (n.equals("pardonoffense") && args.length == 2)) {
                ConfigurationSection sec = this.getConfig().getConfigurationSection("punishment-durations");
                if (sec != null) {
                    sug.addAll(sec.getKeys(false));
                }
            } else if (n.matches("ban|mute|tempban") && args.length == 2) {
                sug.addAll(Arrays.asList("10m", "1h", "1d", "perm"));
            } else if (n.equals("clearhistory") && args.length == 2) {
                sug.add("confirm");
            } else if (n.equals("report") && args.length == 2) {
                for (String[] r : this.reportReasons()) {
                    sug.add(r[0].replace(" ", "_"));
                }
            } else if (n.equals("reports") && args.length == 2 && args[0].equalsIgnoreCase("clear")) {
                sug.add("all");
            } else if (n.equals("kit")) {
                String sub = args[0].toLowerCase();
                if (args.length == 2 && sub.matches("preview|edit|delete")) {
                    sug.addAll(this.kitNames());
                } else if (args.length == 2 && sub.matches("give|reset")) {
                    sug.addAll(this.visibleOnlineNames(s));
                } else if (args.length == 3 && sub.matches("give|reset")) {
                    sug.addAll(this.kitNames());
                    if (sub.equals("reset")) {
                        sug.add("all");
                    }
                } else if (args.length == 3 && sub.equals("delete")) {
                    sug.add("confirm");
                }
            } else if ((n.equals("forcetp") || n.equals("tp")) && args.length == 2 && s.hasPermission("unstableffa.forcetp.others")) {
                sug.addAll(this.visibleOnlineNames(s));
            }
        }

        return sug.stream().distinct().filter((str) -> str.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }

    // =====================================================================================
    // users index (data.yml -> users:)
    // =====================================================================================

    // "." is a path separator in Bukkit configs (Bedrock/Floodgate names can start with one).
    private String userKey(String name) {
        return name.replace('.', '_');
    }

    private ConfigurationSection userSection(UUID id) {
        String name = this.uuidIndex.get(id);
        return name == null ? null : this.data.getConfigurationSection("users." + this.userKey(name));
    }

    private void rebuildUserIndex() {
        this.nameIndex.clear();
        this.uuidIndex.clear();
        ConfigurationSection us = this.data.getConfigurationSection("users");
        if (us == null) {
            return;
        }
        for (String key : us.getKeys(false)) {
            String name = us.getString(key + ".name", key);
            String u = us.getString(key + ".uuid");
            if (u == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(u);
                this.nameIndex.put(name.toLowerCase(), id);
                this.uuidIndex.put(id, name);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // Imports players from the old UUID-keyed sections into users:, once.
    private void migrateUsers() {
        this.rebuildUserIndex();
        boolean changed = false;
        for (String sec : new String[]{"playernames", "punishnames"}) {
            ConfigurationSection cs = this.data.getConfigurationSection(sec);
            if (cs == null) {
                continue;
            }
            for (String k : cs.getKeys(false)) {
                String name = this.data.getString(sec + "." + k);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                UUID id;
                try {
                    id = UUID.fromString(k);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (this.uuidIndex.containsKey(id) || this.nameIndex.containsKey(name.toLowerCase())) {
                    continue;
                }
                String base = "users." + this.userKey(name);
                List<String> ips = this.data.getStringList("playerips." + k);
                this.data.set(base + ".name", name);
                this.data.set(base + ".uuid", k);
                this.data.set(base + ".last-ip", ips.isEmpty() ? "unknown" : ips.get(ips.size() - 1));
                this.data.set(base + ".playtime-seconds", 0);
                this.nameIndex.put(name.toLowerCase(), id);
                this.uuidIndex.put(id, name);
                changed = true;
            }
        }
        if (changed) {
            this.saveD();
        }
    }

    private String now() {
        return this.formatDate(String.valueOf(System.currentTimeMillis()));
    }

    // Called on quit: adds the session to playtime and stamps last-seen.
    private void touchUserQuit(Player p) {
        UUID id = p.getUniqueId();
        String base = "users." + this.userKey(this.uuidIndex.getOrDefault(id, p.getName()));
        if (!this.data.contains(base + ".uuid")) {
            return;
        }
        Long start = this.sessionStart.remove(id);
        if (start != null) {
            long add = (System.currentTimeMillis() - start) / 1000L;
            this.data.set(base + ".playtime-seconds", this.data.getLong(base + ".playtime-seconds", 0L) + add);
        }
        this.data.set(base + ".last-seen", this.now());
        this.saveD();
    }

    private long playtimeMs(UUID id) {
        ConfigurationSection u = this.userSection(id);
        long ms = u == null ? 0L : u.getLong("playtime-seconds", 0L) * 1000L;
        Long start = this.sessionStart.get(id);
        if (start != null) {
            ms += System.currentTimeMillis() - start;
        }
        return ms;
    }

    // Accepts a username (any case) or a UUID.
    private OfflinePlayer resolveOffline(String input) {
        UUID id = this.nameIndex.get(input.toLowerCase());
        if (id == null && input.matches("(?i)[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            id = UUID.fromString(input);
        }
        return id != null ? Bukkit.getOfflinePlayer(id) : Bukkit.getOfflinePlayer(input);
    }

    private String displayName(OfflinePlayer t, String fallback) {
        String n = this.uuidIndex.get(t.getUniqueId());
        if (n != null) {
            return n;
        }
        return t.getName() != null ? t.getName() : fallback;
    }

    // =====================================================================================
    // /live <player>  -  what is this player doing right now? (auto-refreshing menu)
    // =====================================================================================

    // viewer uuid -> the live menu they have open (refreshed every 10 ticks by tickLive)
    private final Map<UUID, Gui> liveViewers = new HashMap<>();

    private String dimensionName(World w) {
        if (w == null) {
            return "Unknown";
        }
        switch (w.getEnvironment()) {
            case NORMAL:
                return "Overworld";
            case NETHER:
                return "The Nether";
            case THE_END:
                return "The End";
            default:
                return "Custom";
        }
    }

    private Material dimensionIcon(World w) {
        if (w == null) {
            return Material.BARRIER;
        }
        switch (w.getEnvironment()) {
            case NORMAL:
                return Material.GRASS_BLOCK;
            case NETHER:
                return Material.NETHERRACK;
            case THE_END:
                return Material.END_STONE;
            default:
                return Material.STONE;
        }
    }

    // Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east.
    private String compass(float yaw) {
        String[] dirs = {"South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East"};
        float y = ((yaw % 360F) + 360F) % 360F;
        return dirs[Math.round(y / 45F) % 8];
    }

    private String pretty(String s) {
        return s.toLowerCase().replace('_', ' ').replace('-', ' ');
    }

    private void handleLive(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.live")) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length < 1) {
            s.sendMessage("§cUsage: /live <player>");
            return;
        }
        Player t = Bukkit.getPlayer(args[0]);
        if (t == null || (this.vanished.contains(t.getUniqueId()) && !s.hasPermission("unstableffa.vanish"))) {
            s.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
            return;
        }
        if (!(s instanceof Player)) {
            this.liveText(s, t);
            return;
        }
        this.openLive((Player) s, t, null);
    }

    // Console version (no menu possible).
    private void liveText(CommandSender s, Player t) {
        Location l = t.getLocation();
        s.sendMessage("§b§lLIVE §f" + t.getName());
        s.sendMessage("§7Gamemode: §f" + this.pretty(t.getGameMode().name()));
        s.sendMessage("§7World: §f" + (l.getWorld() == null ? "?" : l.getWorld().getName()) + " §8(" + this.dimensionName(l.getWorld()) + ")");
        s.sendMessage("§7Position: §f" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " §8facing " + this.compass(l.getYaw()));
        s.sendMessage("§7Health: §f" + Math.round(t.getHealth() * 10.0) / 10.0 + " §7Food: §f" + t.getFoodLevel() + " §7Level: §f" + t.getLevel());
        s.sendMessage("§7Ping: §f" + t.getPing() + "ms");
    }

    private void openLive(Player viewer, Player target, Gui parent) {
        Gui g = this.newGui("live", 45, "§8Live » " + target.getName(), parent);
        g.target = target.getUniqueId();
        g.targetName = target.getName();
        this.renderLive(g, viewer);
        viewer.openInventory(g.inv);
        // Registered after opening: opening closes any previous menu, which would drop the entry.
        this.liveViewers.put(viewer.getUniqueId(), g);
    }

    // Runs every 10 ticks: refreshes every open /live menu.
    private void tickLive() {
        if (this.liveViewers.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Gui> en : new ArrayList<>(this.liveViewers.entrySet())) {
            Player viewer = Bukkit.getPlayer(en.getKey());
            Gui g = en.getValue();
            if (viewer == null) {
                this.liveViewers.remove(en.getKey());
                continue;
            }
            Player target = Bukkit.getPlayer(g.target);
            if (target == null) {
                this.liveViewers.remove(en.getKey());
                viewer.closeInventory();
                viewer.sendMessage("§c" + g.targetName + " went offline.");
                continue;
            }
            this.renderLive(g, viewer);
        }
    }

    private void renderLiveRaw(Gui g, Player viewer) {
        Player t = Bukkit.getPlayer(g.target);
        if (t == null) {
            return;
        }
        UUID id = t.getUniqueId();
        boolean staff = this.canPunishAny(viewer);
        Arrays.fill(g.slotData, null);

        ItemStack[] items = new ItemStack[45];
        Arrays.fill(items, this.pane());

        Location l = t.getLocation();
        World w = l.getWorld();
        String dim = this.dimensionName(w);

        // ----- head -----
        List<String> hl = new ArrayList<>();
        Long start = this.sessionStart.get(id);
        hl.add("&7Status: &#FF9147Online" + (start == null ? "" : " &8(session " + this.formatUptime(System.currentTimeMillis() - start) + ")"));
        String nick = this.data.getString("users." + this.userKey(t.getName()) + ".nick");
        if (nick != null && !nick.isEmpty()) {
            hl.add("&7Nickname: &r" + nick);
        }
        hl.add("&8Updates live, twice a second");
        items[4] = this.skull(t, "&#B15CFF&l" + t.getName(), hl);

        // ----- gamemode -----
        String gmn = t.getGameMode().name();
        Material gmIcon;
        switch (gmn) {
            case "CREATIVE":
                gmIcon = Material.DIAMOND;
                break;
            case "ADVENTURE":
                gmIcon = Material.MAP;
                break;
            case "SPECTATOR":
                gmIcon = Material.ENDER_EYE;
                break;
            default:
                gmIcon = Material.COOKED_BEEF;
                break;
        }
        items[19] = this.createGuiItem(gmIcon, "<gradient:#FF7518:#FFFFFF>&lGame</gradient><gradient:#FFFFFF:#FF7518>&lmode</gradient>",
                "&f" + this.pretty(gmn),
                "&7Can fly: " + (t.getAllowFlight() ? "&#FF9147Yes" : "&#D9381ENo"),
                "&7Flying now: " + (t.isFlying() ? "&#FF9147Yes" : "&#D9381ENo"));

        // ----- dimension / world -----
        String weather = w == null ? "?" : (w.isThundering() ? "Thunderstorm" : (w.hasStorm() ? "Raining" : "Clear"));
        items[20] = this.createGuiItem(this.dimensionIcon(w), "<gradient:#FF7518:#FFFFFF>&lDimen</gradient><gradient:#FFFFFF:#FF7518>&lsion</gradient>",
                "&f" + dim,
                "&7World: &f" + (w == null ? "?" : w.getName()),
                "&7Weather: &f" + weather);

        // ----- coordinates -----
        items[21] = this.createGuiItem(Material.COMPASS, "<gradient:#FF7518:#FFFFFF>&lCoordi</gradient><gradient:#FFFFFF:#FF7518>&lnates</gradient>",
                "&7X: &f" + l.getBlockX() + "  &7Y: &f" + l.getBlockY() + "  &7Z: &f" + l.getBlockZ(),
                "&7Facing: &f" + this.compass(l.getYaw()),
                "&7Yaw: &f" + Math.round(l.getYaw()) + "  &7Pitch: &f" + Math.round(l.getPitch()),
                " ", "&#FF8330Click &7» get a copy link in chat");
        g.slotData[21] = "coords";

        // ----- health / hunger / xp -----
        items[22] = this.createGuiItem(Material.GOLDEN_APPLE, "<gradient:#FF7518:#FFFFFF>&lHea</gradient><gradient:#FFFFFF:#FF7518>&llth</gradient> & hunger",
                "&7Health: &#D9381E" + Math.round(t.getHealth() * 10.0) / 10.0,
                "&7Food: &#FF7518" + t.getFoodLevel() + " &8(saturation " + Math.round(t.getSaturation() * 10F) / 10.0 + ")",
                "&7XP level: &#FF9147" + t.getLevel() + " &8(" + (int) (t.getExp() * 100F) + "%)");

        // ----- movement / state -----
        List<String> mv = new ArrayList<>();
        if (t.isFlying()) {
            mv.add("Flying");
        }
        if (t.isGliding()) {
            mv.add("Gliding (elytra)");
        }
        if (t.isSwimming()) {
            mv.add("Swimming");
        }
        if (t.isSprinting()) {
            mv.add("Sprinting");
        }
        if (t.isSneaking()) {
            mv.add("Sneaking");
        }
        if (t.isSleeping()) {
            mv.add("Sleeping");
        }
        if (t.isBlocking()) {
            mv.add("Blocking with shield");
        }
        if (t.isInWater()) {
            mv.add("In water");
        }
        if (t.getFireTicks() > 0) {
            mv.add("On fire");
        }
        if (t.isInsideVehicle() && t.getVehicle() != null) {
            mv.add("Riding " + this.pretty(t.getVehicle().getType().name()));
        }
        List<String> ml = new ArrayList<>();
        if (mv.isEmpty()) {
            ml.add("&7Standing / walking");
        } else {
            for (String m : mv) {
                ml.add("&f" + m);
            }
        }
        items[23] = this.createGuiItem(Material.FEATHER, "<gradient:#FF7518:#FFFFFF>&lMove</gradient><gradient:#FFFFFF:#FF7518>&lment</gradient> & state", ml.toArray(new String[0]));

        // ----- held item -----
        ItemStack hand = t.getInventory().getItemInMainHand();
        ItemStack off = t.getInventory().getItemInOffHand();
        List<String> hi = new ArrayList<>();
        if (hand == null || hand.getType().isAir()) {
            hi.add("&7Main hand: &8empty");
        } else {
            String held = "&7Main hand: &f" + hand.getAmount() + "x " + this.pretty(hand.getType().name());
            if (hand.hasItemMeta() && hand.getItemMeta().hasDisplayName()) {
                held += " &8(" + hand.getItemMeta().getDisplayName() + "&8)";
            }
            hi.add(held);
            if (!hand.getEnchantments().isEmpty()) {
                hi.add("&7Enchantments: &f" + hand.getEnchantments().size());
            }
        }
        if (off == null || off.getType().isAir()) {
            hi.add("&7Off hand: &8empty");
        } else {
            hi.add("&7Off hand: &f" + off.getAmount() + "x " + this.pretty(off.getType().name()));
        }
        items[24] = this.createGuiItem(Material.ITEM_FRAME, "<gradient:#FF7518:#FFFFFF>&lHold</gradient><gradient:#FFFFFF:#FF7518>&ling</gradient>", hi.toArray(new String[0]));

        // ----- potion effects -----
        List<String> pl = new ArrayList<>();
        for (PotionEffect pe : t.getActivePotionEffects()) {
            int ticks = pe.getDuration();
            int sec = ticks / 20;
            String time = (ticks < 0 || ticks > 1000000) ? "infinite" : (sec / 60) + ":" + String.format("%02d", sec % 60);
            pl.add("&f" + this.pretty(pe.getType().getKey().getKey()) + " " + (pe.getAmplifier() + 1) + " &8(" + time + ")");
        }
        if (pl.isEmpty()) {
            pl.add("&8No active effects");
        }
        items[25] = this.createGuiItem(Material.BREWING_STAND, "<gradient:#FF7518:#FFFFFF>&lPotion </gradient><gradient:#FFFFFF:#FF7518>&leffects</gradient>", pl.toArray(new String[0]));

        // ----- ping / session -----
        items[28] = this.createGuiItem(Material.CLOCK, "<gradient:#FF7518:#FFFFFF>&lConne</gradient><gradient:#FFFFFF:#FF7518>&lction</gradient>",
                "&7Ping: &f" + t.getPing() + "ms",
                "&7Session: &f" + (start == null ? "unknown" : this.formatUptime(System.currentTimeMillis() - start)),
                "&7Total playtime: &f" + this.formatUptime(this.playtimeMs(id)));

        // ----- looking at -----
        Block target = t.getTargetBlockExact(6);
        items[29] = this.createGuiItem(Material.SPYGLASS, "<gradient:#FF7518:#FFFFFF>&lLooki</gradient><gradient:#FFFFFF:#FF7518>&lng at</gradient>",
                target == null ? "&8Nothing within 6 blocks" : "&f" + this.pretty(target.getType().name()),
                target == null ? " " : "&7At: &f" + target.getX() + " " + target.getY() + " " + target.getZ());

        // ----- nearby -----
        int nearPlayers = 0;
        int nearMobs = 0;
        List<String> nearNames = new ArrayList<>();
        for (Entity en : t.getNearbyEntities(24, 24, 24)) {
            if (en instanceof Player) {
                if (this.vanished.contains(en.getUniqueId())) {
                    continue;
                }
                nearPlayers++;
                if (nearNames.size() < 5) {
                    nearNames.add(((Player) en).getName());
                }
            } else if (en instanceof Monster) {
                nearMobs++;
            }
        }
        List<String> nl = new ArrayList<>();
        nl.add("&7Players: &f" + nearPlayers + (nearNames.isEmpty() ? "" : " &8(" + String.join(", ", nearNames) + ")"));
        nl.add("&7Hostile mobs: &f" + nearMobs);
        items[30] = this.createGuiItem(Material.SKELETON_SKULL, "<gradient:#FF7518:#FFFFFF>&lNea</gradient><gradient:#FFFFFF:#FF7518>&lrby</gradient> &8(24 blocks)", nl.toArray(new String[0]));

        // ----- plugin status -----
        List<String> pf = new ArrayList<>();
        if (this.vanished.contains(id)) {
            pf.add("&#B15CFFVanished");
        }
        if (this.frozen.contains(id)) {
            pf.add("&#D9381EFrozen");
        }
        if (this.god.contains(id)) {
            pf.add("&#FF8330God mode");
        }
        if (this.inStaffChat.contains(id)) {
            pf.add("&#8E3BD1In staff chat");
        }
        if (this.socialSpy.contains(id)) {
            pf.add("&#8E3BD1Social spy");
        }
        if (this.isMutedUuid(id)) {
            pf.add("&#FF7518Muted");
        }
        List<String> pfl = new ArrayList<>();
        if (pf.isEmpty()) {
            pfl.add("&8Nothing special");
        } else {
            for (String f : pf) {
                pfl.add("&f" + f);
            }
        }
        items[31] = this.createGuiItem(Material.BEACON, "<gradient:#FF7518:#FFFFFF>&lPlugin </gradient><gradient:#FFFFFF:#FF7518>&lstatus</gradient>", pfl.toArray(new String[0]));

        // ----- network (IP needs unstableffa.ip.view, alts need unstableffa.alts) -----
        boolean seeIp = viewer.hasPermission(PERM_IP_VIEW);
        boolean seeAlts = viewer.hasPermission(PERM_ALTS);
        if (seeIp || seeAlts) {
            List<String> net = new ArrayList<>();
            if (seeIp) {
                String ip = (t.getAddress() != null && t.getAddress().getAddress() != null)
                        ? t.getAddress().getAddress().getHostAddress() : "unknown";
                net.add("&7IP: &f" + ip);
            }
            if (seeAlts) {
                net.add("&7Possible alts: &f" + this.findAlts(id).size());
            }
            items[32] = this.createGuiItem(Material.ENDER_EYE, "<gradient:#FF7518:#FFFFFF>&lNetw</gradient><gradient:#FFFFFF:#FF7518>&lork</gradient>", net.toArray(new String[0]));
        }

        // ----- actions -----
        if (viewer.hasPermission("unstableffa.live.teleport")) {
            items[38] = this.createGuiItem(Material.ENDER_PEARL, "<gradient:#FF7518:#FFFFFF>&lTeleport </gradient><gradient:#FFFFFF:#FF7518>&lto player</gradient>", "&7Go to their exact location", " ", "&#FF8330Click to teleport");
            g.slotData[38] = "tp";
        }
        if (viewer.hasPermission("unstableffa.invsee")) {
            items[39] = this.createGuiItem(Material.CHEST, "<gradient:#FF7518:#FFFFFF>&lInven</gradient><gradient:#FFFFFF:#FF7518>&ltory</gradient>", "&7Open their live inventory", " ", "&#FF8330Click to open");
            g.slotData[39] = "invsee";
        }
        items[40] = this.createGuiItem(g.parent != null ? Material.ARROW : Material.OAK_DOOR,
                g.parent != null ? "&#FF8330&l« Back" : "<gradient:#FF7518:#FFFFFF>&lClo</gradient><gradient:#FFFFFF:#FF7518>&lse</gradient>", "&7Updates stop when you leave this menu");
        g.slotData[40] = "back";
        items[41] = this.createGuiItem(Material.NAME_TAG, "<gradient:#FF7518:#FFFFFF>&lProf</gradient><gradient:#FFFFFF:#FF7518>&lile</gradient>", "&7Open the player card", " ", "&#FF8330Click to open");
        g.slotData[41] = "profile";
        if (viewer.hasPermission("unstableffa.freeze")) {
            boolean fr = this.frozen.contains(id);
            items[42] = this.createGuiItem(fr ? Material.BLUE_ICE : Material.ICE,
                    fr ? "<gradient:#FF7518:#FFFFFF>&lUnfreeze</gradient><gradient:#FFFFFF:#FF7518>&l player</gradient>" : "<gradient:#FF7518:#FFFFFF>&lFreeze </gradient><gradient:#FFFFFF:#FF7518>&lplayer</gradient>",
                    fr ? "&7They can't move right now." : "&7Stop them from moving.", " ", "&#FF8330Click to toggle");
            g.slotData[42] = "freeze";
        }

        // Only touch slots that changed so the menu doesn't flicker.
        Inventory inv = g.inv;
        for (int i = 0; i < 45; i++) {
            if (!this.sameItem(this.invseeNorm(items[i]), this.invseeNorm(inv.getItem(i)))) {
                inv.setItem(i, items[i]);
            }
        }
    }

    private void clickLive(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 45 || !(g.slotData[slot] instanceof String)) {
            return;
        }
        final Player t = Bukkit.getPlayer(g.target);
        String action = (String) g.slotData[slot];
        if (action.equals("back")) {
            this.later(() -> this.goBack(p, g));
            return;
        }
        if (t == null) {
            return;
        }
        switch (action) {
            case "coords": {
                Location l = t.getLocation();
                String c = l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
                final TextComponent tc = new TextComponent(TextComponent.fromLegacyText("§f" + g.targetName + " §7is at §f" + c
                        + " §7in §f" + this.dimensionName(l.getWorld()) + " §8[click to copy]"));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, c));
                this.later(() -> {
                    p.closeInventory();
                    p.spigot().sendMessage(tc);
                });
                break;
            }
            case "tp":
                if (p.hasPermission("unstableffa.live.teleport")) {
                    this.later(() -> {
                        p.closeInventory();
                        p.teleport(t.getLocation());
                        p.sendMessage("§aTeleported to §f" + t.getName() + "§a.");
                    });
                }
                break;
            case "invsee":
                if (p.hasPermission("unstableffa.invsee")) {
                    this.later(() -> this.openInvsee(p, t));
                }
                break;
            case "profile":
                this.later(() -> this.openProfile(p, g.target, g));
                break;
            case "freeze":
                if (p.hasPermission("unstableffa.freeze")) {
                    this.toggleFreeze(p, t);
                    this.renderLive(g, p);
                }
                break;
            default:
                break;
        }
    }

    // =====================================================================================
    // UnstableFFA GUI UPDATE
    // Paste this whole block INSIDE the UnstableFFA class (right above the final "}").
    // Follow UnstableFFA-gui-update.md for the small edits elsewhere in the file.
    // Builds on the earlier "users index" additions (uuidIndex, nameIndex, userKey, userSection,
    // resolveOffline, displayName, touchUser...).
    // =====================================================================================

    // ---------- state ----------

    private final Map<UUID, Gui> searchPending = new ConcurrentHashMap<>();

    private static final String T_SEEN = "§8Unstable » Players";
    private static final String T_RECENT = "§8Unstable » Recent Punishments";
    private static final String T_SETTINGS = "§8Unstable » Settings";

    private static final String[] SEEN_FILTERS = {"All players", "Online", "Offline", "Banned", "Muted", "Has punishments"};
    private static final String[] SEEN_SORTS = {"Last seen", "Name (A-Z)", "Playtime", "Oldest members"};
    private static final String[] HIST_FILTERS = {"Everything", "Bans", "Mutes", "Warnings", "Kicks", "Notes", "Active only"};
    private static final String[] RECENT_FILTERS = {"Everything", "Bans", "Mutes", "Warnings", "Kicks", "Unbans / unmutes"};


    // One open menu. The inventory is re-rendered in place when you page / filter / sort.
    private static final class Gui implements InventoryHolder {
        final String type; // seen | profile | history | recent | settings
        Gui parent;        // where the Back button goes
        UUID target;
        String targetName = "";
        int page = 0;
        int filter = 0;
        int sort = 0;
        String search = "";
        String key = "";   // kit name for the kit menus
        Inventory inv;
        final Object[] slotData = new Object[54]; // what each slot means (uuid, log index, action key...)

        Gui(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private static final class UserRow {
        UUID id;
        String name;
        String lastSeen = "";
        String firstJoin = "";
        long playSec;
        boolean online;
        String nick;
        String search = "";
    }

    private static final class Setting {
        final String key;
        final Material icon;
        final String name;
        final String desc;
        final String perm;
        final boolean staff;
        final Predicate<Player> get;
        final BiConsumer<Player, Boolean> set;

        Setting(String key, Material icon, String name, String desc, String perm, boolean staff, Predicate<Player> get, BiConsumer<Player, Boolean> set) {
            this.key = key;
            this.icon = icon;
            this.name = name;
            this.desc = desc;
            this.perm = perm;
            this.staff = staff;
            this.get = get;
            this.set = set;
        }
    }

    // =====================================================================================
    // shared GUI helpers
    // =====================================================================================

    // =====================================================================================
    // menu style: every menu goes through tidy() so they all look the same and calm.
    //   - no glass wall: filler panes are removed, only the bottom row keeps a dark bar
    //   - every item keeps an icon that fits what it is
    //   - Close is always a barrier, Back / Previous / Next are always arrows
    // =====================================================================================

    private static final Set<Material> TIDY_KEEP = java.util.EnumSet.of(Material.PLAYER_HEAD, Material.ARROW,
            Material.BARRIER, Material.LIME_DYE, Material.GRAY_DYE);

    void tidy(Inventory inv, boolean onlyFiller) {
        int size = inv.getSize();
        int bottom = size >= 36 ? size - 9 : size;
        ItemStack bar = this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < size; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                if (i >= bottom) {
                    inv.setItem(i, bar);
                }
                continue;
            }
            if (isFiller(it)) {
                inv.setItem(i, i >= bottom ? bar : null);
                continue;
            }
            if (onlyFiller || TIDY_KEEP.contains(it.getType())) {
                continue;
            }
            Material nav = navIcon(it);
            if (nav != null) {
                inv.setItem(i, restyle(it, nav));
            }
        }
    }

    private static boolean isFiller(ItemStack it) {
        if (!it.getType().name().endsWith("STAINED_GLASS_PANE")) {
            return false;
        }
        ItemMeta m = it.getItemMeta();
        return m == null || !m.hasDisplayName() || ChatColor.stripColor(m.getDisplayName()).trim().isEmpty();
    }

    /** Close / Done = barrier, Back / Previous / Next = arrow, anything else keeps its icon. */
    private static Material navIcon(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null || !m.hasDisplayName()) {
            return null;
        }
        String n = ChatColor.stripColor(m.getDisplayName()).replace("«", "").replace("»", "").trim().toLowerCase(Locale.ROOT);
        if (n.equals("close") || n.equals("done") || n.startsWith("close ")) {
            return Material.BARRIER;
        }
        if (n.startsWith("back") || n.startsWith("previous") || n.startsWith("next page") || n.equals("next")) {
            return Material.ARROW;
        }
        return null;
    }

    private static boolean isClickable(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null || !m.hasLore() || m.getLore() == null) {
            return false;
        }
        for (String l : m.getLore()) {
            if (ChatColor.stripColor(l).toLowerCase(Locale.ROOT).contains("click")) {
                return true;
            }
        }
        return false;
    }

    /** Same name, lore and glow, new material. */
    static ItemStack restyle(ItemStack it, Material to) {
        if (it.getType() == to) {
            return it;
        }
        ItemStack out = new ItemStack(to, Math.max(1, it.getAmount()));
        ItemMeta from = it.getItemMeta();
        if (from != null) {
            ItemMeta nm = out.getItemMeta();
            if (nm != null) {
                if (from.hasDisplayName()) {
                    nm.setDisplayName(from.getDisplayName());
                }
                if (from.hasLore()) {
                    nm.setLore(from.getLore());
                }
                if (from.hasEnchantmentGlintOverride() && from.getEnchantmentGlintOverride() || from.hasEnchants()) {
                    nm.setEnchantmentGlintOverride(true);
                }
                nm.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                out.setItemMeta(nm);
            }
        }
        return out;
    }

    private void renderLive(Gui g, Player viewer) {
        this.renderLiveRaw(g, viewer);
        this.tidy(g.inv, false);
    }
    private void renderSeen(Gui g, Player viewer) {
        this.renderSeenRaw(g, viewer);
        this.tidy(g.inv, false);
    }
    private void renderProfile(Gui g, Player viewer) {
        this.renderProfileRaw(g, viewer);
        this.tidy(g.inv, false);
    }
    private void renderHistory(Gui g, Player viewer) {
        this.renderHistoryRaw(g, viewer);
        this.tidy(g.inv, false);
    }
    private void renderRecent(Gui g, Player viewer) {
        this.renderRecentRaw(g, viewer);
        this.tidy(g.inv, false);
    }
    private void renderSettings(Gui g, Player p) {
        this.renderSettingsRaw(g, p);
        this.tidy(g.inv, false);
    }
    private void renderReportPick(Gui g, Player p) {
        this.renderReportPickRaw(g, p);
        this.tidy(g.inv, false);
    }
    private void renderReports(Gui g, Player p) {
        this.renderReportsRaw(g, p);
        this.tidy(g.inv, false);
    }
    private void renderStaffList(Gui g, Player viewer, List<Player> staff) {
        this.renderStaffListRaw(g, viewer, staff);
        this.tidy(g.inv, false);
    }
    private void renderKits(Gui g, Player p) {
        this.renderKitsRaw(g, p);
        this.tidy(g.inv, true); // kit icons are chosen by you, so only the clutter goes
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    private Gui newGui(String type, int size, String title, Gui parent) {
        Gui g = new Gui(type);
        g.parent = parent;
        g.inv = Bukkit.createInventory(g, size, title);
        return g;
    }

    private ItemStack pane() {
        return this.createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    private ItemStack glow(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack skull(OfflinePlayer t, String name, List<String> lore) {
        ItemStack it = this.createGuiItem(Material.PLAYER_HEAD, name, lore.toArray(new String[0]));
        ItemMeta m = it.getItemMeta();
        if (m instanceof SkullMeta) {
            ((SkullMeta) m).setOwningPlayer(t);
            it.setItemMeta(m);
        }
        return it;
    }

    // Word-wraps long text (reasons, descriptions) into lore lines.
    private List<String> wrap(String text, int width, String color) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : (text == null ? "" : text).split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                out.add(color + line);
                line = new StringBuilder();
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(w);
        }
        if (line.length() > 0) {
            out.add(color + line);
        }
        return out;
    }

    private int cycle(int cur, int size, boolean back) {
        return back ? (cur - 1 + size) % size : (cur + 1) % size;
    }

    // A "click to cycle" button that lists every option with an arrow on the current one.
    private ItemStack optionItem(Material icon, String title, String[] options, int current, String hint) {
        List<String> lore = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            lore.add(i == current ? "&#FF9147▶ &f" + options[i] : "&8   " + options[i]);
        }
        lore.add(" ");
        lore.add(hint);
        return this.createGuiItem(icon, title + options[current], lore.toArray(new String[0]));
    }

    private void renderPager(Gui g, int pages, int total) {
        Inventory inv = g.inv;
        ItemStack frame = this.pane();
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, frame);
        }
        if (g.page > 0) {
            inv.setItem(45, this.createGuiItem(Material.ARROW, "&#FF8330&l« Previous page", "&7Page " + g.page + " of " + pages));
        }
        inv.setItem(49, this.createGuiItem(Material.PAPER, "<gradient:#FF7518:#FFFFFF>&lPa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> " + (g.page + 1) + " / " + pages, "&7" + total + " result(s)"));
        if (g.page < pages - 1) {
            inv.setItem(53, this.createGuiItem(Material.ARROW, "&#FF8330&lNext page »", "&7Page " + (g.page + 2) + " of " + pages));
        }
    }

    private boolean handlePager(Gui g, int slot) {
        if (slot == 45 && g.page > 0) {
            g.page--;
            return true;
        }
        if (slot == 53) {
            g.page++; // clamped when rendering
            return true;
        }
        return false;
    }

    private String nameForUuid(String uuidStr) {
        try {
            String n = this.uuidIndex.get(UUID.fromString(uuidStr));
            if (n != null) {
                return n;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return this.nameOf(uuidStr);
    }

    private String agoMs(String ts) {
        try {
            long diff = System.currentTimeMillis() - Long.parseLong(ts);
            return diff < 60000L ? "just now" : this.formatUptime(diff) + " ago";
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private String ago(String date) {
        try {
            long t = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(date).getTime();
            long diff = System.currentTimeMillis() - t;
            return diff < 60000L ? "just now" : this.formatUptime(diff) + " ago";
        } catch (Exception ex) {
            return "";
        }
    }

    private void renderGui(Gui g, Player p) {
        switch (g.type) {
            case "seen":
                this.renderSeen(g, p);
                break;
            case "profile":
                this.renderProfile(g, p);
                break;
            case "history":
                this.renderHistory(g, p);
                break;
            case "recent":
                this.renderRecent(g, p);
                break;
            case "settings":
                this.renderSettings(g, p);
                break;
            case "live":
                this.renderLive(g, p);
                break;
            case "reports":
                this.renderReports(g, p);
                break;
            case "report-pick":
                this.renderReportPick(g, p);
                break;
            case "kits":
                this.renderKits(g, p);
                break;
            case "kit-settings":
                this.renderKitSettings(g);
                break;
            default:
                break;
        }
    }

    private void goBack(Player p, Gui g) {
        Gui par = g.parent;
        if (par == null) {
            if (g.type.equals("profile") || (g.type.equals("recent") && p.hasPermission("unstableffa.seen"))) {
                this.openSeen(p, "");
            } else if (g.type.equals("history") && this.canPunishAny(p)) {
                this.openRecent(p, null);
            } else {
                p.closeInventory();
            }
            return;
        }
        this.renderGui(par, p);
        p.openInventory(par.inv);
    }

    // Entry point for every click inside one of our menus.
    private void handleGuiClick(InventoryClickEvent e, Player p, Gui g) {
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) {
            return;
        }
        switch (g.type) {
            case "seen":
                this.clickSeen(e, p, g);
                break;
            case "profile":
                this.clickProfile(e, p, g);
                break;
            case "history":
                this.clickHistory(e, p, g);
                break;
            case "recent":
                this.clickRecent(e, p, g);
                break;
            case "settings":
                this.clickSettings(e, p, g);
                break;
            case "live":
                this.clickLive(e, p, g);
                break;
            case "staff":
                this.clickStaffList(e, p, g);
                break;
            case "report-pick":
                this.clickReportPick(e, p, g);
                break;
            case "report-reason":
                this.clickReportReason(e, p, g);
                break;
            case "reports":
                this.clickReports(e, p, g);
                break;
            case "kits":
                this.clickKits(e, p, g);
                break;
            case "kit-preview":
                this.clickKitPreview(e, p, g);
                break;
            case "kit-settings":
                this.clickKitSettings(e, p, g);
                break;
            default:
                break;
        }
    }

    // =====================================================================================
    // /seen  - player browser
    // =====================================================================================

    private void handleSeen(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.seen")) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (!(s instanceof Player)) {
            this.seenText(s, args);
            return;
        }
        Player p = (Player) s;
        if (args.length == 0) {
            this.openSeen(p, "");
            return;
        }
        UUID id = this.nameIndex.get(args[0].toLowerCase());
        if (id == null && args[0].matches("(?i)[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            UUID u = UUID.fromString(args[0]);
            if (this.uuidIndex.containsKey(u)) {
                id = u;
            }
        }
        if (id != null) {
            this.openProfile(p, id, null);
        } else {
            // Not an exact match: open the browser with that text already searched.
            this.openSeen(p, String.join(" ", args));
        }
    }

    // Console fallback (no GUI possible).
    private void seenText(CommandSender s, String[] args) {
        if (args.length < 1) {
            s.sendMessage("§cUsage: /seen <player>");
            return;
        }
        OfflinePlayer t = this.resolveOffline(args[0]);
        UUID id = t.getUniqueId();
        ConfigurationSection u = this.userSection(id);
        if (u == null) {
            s.sendMessage("§c" + args[0] + " has never joined (or joined before tracking started).");
            return;
        }
        String name = u.getString("name", args[0]);
        if (Bukkit.getPlayer(id) != null) {
            s.sendMessage("§f" + name + " §7is §aonline");
        } else {
            s.sendMessage("§f" + name + " §7was last seen §f" + u.getString("last-seen", "unknown"));
        }
        s.sendMessage("§7First joined: §f" + u.getString("first-join", "unknown"));
        s.sendMessage("§7Playtime: §f" + this.formatUptime(this.playtimeMs(id)));
    }

    private void openSeen(Player p, String search) {
        Gui g = this.newGui("seen", 54, T_SEEN, null);
        g.search = search == null ? "" : search;
        this.renderSeen(g, p);
        p.openInventory(g.inv);
    }

    private List<UserRow> collectUsers(Player viewer) {
        List<UserRow> out = new ArrayList<>();
        ConfigurationSection us = this.data.getConfigurationSection("users");
        if (us == null) {
            return out;
        }
        boolean seeVanished = viewer.hasPermission("unstableffa.vanish");
        for (String key : us.getKeys(false)) {
            String u = us.getString(key + ".uuid");
            if (u == null) {
                continue;
            }
            UserRow r = new UserRow();
            try {
                r.id = UUID.fromString(u);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            r.name = us.getString(key + ".name", key);
            r.lastSeen = us.getString(key + ".last-seen", "");
            r.firstJoin = us.getString(key + ".first-join", "");
            r.playSec = us.getLong(key + ".playtime-seconds", 0L);
            r.nick = us.getString(key + ".nick");
            Player on = Bukkit.getPlayer(r.id);
            r.online = on != null && (seeVanished || !this.vanished.contains(r.id));

            StringBuilder sb = new StringBuilder(r.name.toLowerCase()).append(' ').append(r.id.toString().toLowerCase());
            for (String pn : us.getStringList(key + ".previous-names")) {
                sb.append(' ').append(pn.toLowerCase());
            }
            if (r.nick != null) {
                sb.append(' ').append(ChatColor.stripColor(this.translate(r.nick)).toLowerCase());
            }
            r.search = sb.toString();
            out.add(r);
        }
        return out;
    }

    private boolean seenMatches(UserRow r, int filter, String search) {
        if (!search.isEmpty() && !r.search.contains(search.toLowerCase())) {
            return false;
        }
        switch (filter) {
            case 1:
                return r.online;
            case 2:
                return !r.online;
            case 3:
                return Bukkit.getBanList(Type.NAME).isBanned(r.name);
            case 4:
                return this.isMutedUuid(r.id);
            case 5:
                return !this.data.getStringList("punishlog." + r.id).isEmpty();
            default:
                return true;
        }
    }

    private void sortRows(List<UserRow> rows, int sort) {
        switch (sort) {
            case 1:
                rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                break;
            case 2:
                rows.sort((a, b) -> Long.compare(b.playSec, a.playSec));
                break;
            case 3:
                rows.sort((a, b) -> {
                    if (a.firstJoin.isEmpty() != b.firstJoin.isEmpty()) {
                        return a.firstJoin.isEmpty() ? 1 : -1;
                    }
                    return a.firstJoin.compareTo(b.firstJoin);
                });
                break;
            default:
                rows.sort((a, b) -> {
                    if (a.online != b.online) {
                        return a.online ? -1 : 1;
                    }
                    return b.lastSeen.compareTo(a.lastSeen);
                });
                break;
        }
    }

    private void renderSeenRaw(Gui g, Player viewer) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        boolean staff = this.canPunishAny(viewer);
        int fcount = staff ? SEEN_FILTERS.length : 3;
        if (g.filter >= fcount) {
            g.filter = 0;
        }

        List<UserRow> all = this.collectUsers(viewer);
        int onlineCount = 0;
        for (UserRow r : all) {
            if (r.online) {
                onlineCount++;
            }
        }
        List<UserRow> rows = new ArrayList<>();
        for (UserRow r : all) {
            if (this.seenMatches(r, g.filter, g.search)) {
                rows.add(r);
            }
        }
        this.sortRows(rows, g.sort);

        int total = rows.size();
        int pages = Math.max(1, (total + 35) / 36);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        ItemStack frame = this.pane();
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(0, this.createGuiItem(Material.COMPASS, "<gradient:#FF7518:#FFFFFF>&lSea</gradient><gradient:#FFFFFF:#FF7518>&lrch</gradient>" + (g.search.isEmpty() ? "" : " &8» &f" + g.search),
                "&7Find a player by name, old name,", "&7nickname or UUID.", " ", "&#FF8330Click &7» type in chat", "&#FF8330Right-click &7» clear search"));
        inv.setItem(2, this.optionItem(Material.HOPPER, "&#B15CFF&lFilter: &f", Arrays.copyOf(SEEN_FILTERS, fcount), g.filter,
                "&#FF8330Click &7» next &8| &#FF8330Right-click &7» previous"));
        inv.setItem(3, this.optionItem(Material.CLOCK, "&#B15CFF&lSort: &f", SEEN_SORTS, g.sort,
                "&#FF8330Click &7» next &8| &#FF8330Right-click &7» previous"));
        inv.setItem(4, this.createGuiItem(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lPlayer </gradient><gradient:#FFFFFF:#FF7518>&lBrowser</gradient>",
                "&7Known players: &f" + all.size(), "&7Online now: &#FF9147" + onlineCount, "&7Showing: &f" + total));
        if (staff) {
            inv.setItem(8, this.createGuiItem(Material.WRITABLE_BOOK, "<gradient:#FF7518:#FFFFFF>&lRecent pu</gradient><gradient:#FFFFFF:#FF7518>&lnishments</gradient>",
                    "&7Latest actions on the whole server", " ", "&#FF8330Click to open"));
        }

        int start = g.page * 36;
        for (int i = 0; i < 36; i++) {
            int idx = start + i;
            if (idx >= total) {
                break;
            }
            UserRow r = rows.get(idx);
            inv.setItem(9 + i, this.seenItem(r, staff));
            g.slotData[9 + i] = r.id;
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lNo playe</gradient><gradient:#FFFFFF:#FF7518>&lrs found</gradient>", "&7Try another search or filter."));
        }
        this.renderPager(g, pages, total);
    }

    private ItemStack seenItem(UserRow r, boolean staff) {
        List<String> lore = new ArrayList<>();
        if (r.online) {
            Long start = this.sessionStart.get(r.id);
            lore.add("&7Status: &#FF9147Online" + (start == null ? "" : " &8(" + this.formatUptime(System.currentTimeMillis() - start) + ")"));
        } else {
            String ag = r.lastSeen.isEmpty() ? "" : this.ago(r.lastSeen);
            lore.add("&7Last seen: &f" + (r.lastSeen.isEmpty() ? "unknown" : r.lastSeen) + (ag.isEmpty() ? "" : " &8(" + ag + ")"));
        }
        lore.add("&7First joined: &f" + (r.firstJoin.isEmpty() ? "unknown" : r.firstJoin));
        lore.add("&7Playtime: &f" + this.formatUptime(r.playSec * 1000L));
        if (r.nick != null && !r.nick.isEmpty()) {
            lore.add("&7Nickname: &r" + r.nick);
        }
        String tags = "";
        if (staff) {
            lore.add("&7Punishments: &f" + this.historySummary(r.id));
            if (Bukkit.getBanList(Type.NAME).isBanned(r.name)) {
                tags += " &#D9381E[BANNED]";
            }
            if (this.isMutedUuid(r.id)) {
                tags += " &#FF7518[MUTED]";
            }
        }
        lore.add(" ");
        lore.add("&#FF8330Click &7» open profile");
        if (staff) {
            lore.add("&#FF8330Shift-click &7» punishment history");
        }
        return this.skull(Bukkit.getOfflinePlayer(r.id), (r.online ? "&#FF9147&l" : "&f&l") + r.name + tags, lore);
    }

    private void clickSeen(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        boolean staff = this.canPunishAny(p);
        boolean right = e.isRightClick();
        if (slot == 0) {
            if (right) {
                g.search = "";
                g.page = 0;
                this.renderSeen(g, p);
            } else {
                this.startSearch(p, g);
            }
            return;
        }
        if (slot == 2) {
            g.filter = this.cycle(g.filter, staff ? SEEN_FILTERS.length : 3, right);
            g.page = 0;
            this.renderSeen(g, p);
            return;
        }
        if (slot == 3) {
            g.sort = this.cycle(g.sort, SEEN_SORTS.length, right);
            g.page = 0;
            this.renderSeen(g, p);
            return;
        }
        if (slot == 8 && staff) {
            this.later(() -> this.openRecent(p, g));
            return;
        }
        if (this.handlePager(g, slot)) {
            this.renderSeen(g, p);
            return;
        }
        if (slot >= 9 && slot <= 44 && g.slotData[slot] instanceof UUID) {
            final UUID id = (UUID) g.slotData[slot];
            if (e.isShiftClick() && staff) {
                this.later(() -> this.openHistory(p, Bukkit.getOfflinePlayer(id), this.uuidIndex.getOrDefault(id, this.nameOf(id.toString())), g));
            } else {
                this.later(() -> this.openProfile(p, id, g));
            }
        }
    }

    // Closes the menu and waits for the next chat message to use as the search text.
    private void startSearch(Player p, Gui g) {
        final UUID pid = p.getUniqueId();
        this.searchPending.put(pid, g);
        this.later(() -> {
            p.closeInventory();
            p.sendMessage("§b§lSEARCH §7Type a name, old name, nickname or UUID in chat. Type §fcancel §7to abort.");
        });
        Bukkit.getScheduler().runTaskLater(this, () -> this.searchPending.remove(pid, g), 20L * 30L);
    }

    // =====================================================================================
    // player profile card
    // =====================================================================================

    private void openProfile(Player viewer, UUID id, Gui parent) {
        String name = this.nameForUuid(id.toString());
        Gui g = this.newGui("profile", 45, "§8Unstable » " + name, parent);
        g.target = id;
        g.targetName = name;
        this.renderProfile(g, viewer);
        viewer.openInventory(g.inv);
    }

    private void renderProfileRaw(Gui g, Player viewer) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        boolean staff = this.canPunishAny(viewer);
        UUID id = g.target;
        String name = g.targetName;
        ConfigurationSection u = this.userSection(id);

        ItemStack frame = this.pane();
        for (int i = 0; i < 45; i++) {
            inv.setItem(i, frame);
        }

        Player on = Bukkit.getPlayer(id);
        boolean online = on != null && (!this.vanished.contains(id) || viewer.hasPermission("unstableffa.vanish"));
        String nick = u == null ? null : u.getString("nick");
        boolean banned = Bukkit.getBanList(Type.NAME).isBanned(name);
        boolean muted = this.isMutedUuid(id);

        List<String> hl = new ArrayList<>();
        hl.add(online ? "&7Status: &#FF9147Online" : "&7Status: &#D9381EOffline");
        if (nick != null && !nick.isEmpty()) {
            hl.add("&7Nickname: &r" + nick);
        }
        if (staff) {
            hl.add("&7Banned: " + (banned ? "&#D9381EYes" : "&#FF9147No"));
            hl.add("&7Muted: " + (muted ? "&#FF7518Yes" : "&#FF9147No"));
        }
        hl.add("&8" + id);
        inv.setItem(4, this.skull(Bukkit.getOfflinePlayer(id), "&#B15CFF&l" + name, hl));

        String first = u == null ? "unknown" : u.getString("first-join", "unknown");
        String last = u == null ? "unknown" : u.getString("last-seen", "unknown");
        inv.setItem(19, this.createGuiItem(Material.CLOCK, "<gradient:#FF7518:#FFFFFF>&lFirst </gradient><gradient:#FFFFFF:#FF7518>&ljoined</gradient>", "&f" + first));

        if (online) {
            Long start = this.sessionStart.get(id);
            inv.setItem(20, this.createGuiItem(Material.COMPASS, "<gradient:#FF7518:#FFFFFF>&lOnlin</gradient><gradient:#FFFFFF:#FF7518>&le now</gradient>",
                    start == null ? "&7Currently playing" : "&7This session: &f" + this.formatUptime(System.currentTimeMillis() - start)));
        } else {
            List<String> ll = new ArrayList<>();
            ll.add("&f" + last);
            String ag = last.equals("unknown") ? "" : this.ago(last);
            if (!ag.isEmpty()) {
                ll.add("&8" + ag);
            }
            inv.setItem(20, this.createGuiItem(Material.COMPASS, "<gradient:#FF7518:#FFFFFF>&lLast </gradient><gradient:#FFFFFF:#FF7518>&lseen</gradient>", ll.toArray(new String[0])));
        }

        inv.setItem(21, this.createGuiItem(Material.EXPERIENCE_BOTTLE, "<gradient:#FF7518:#FFFFFF>&lPlay</gradient><gradient:#FFFFFF:#FF7518>&ltime</gradient>", "&f" + this.formatUptime(this.playtimeMs(id))));

        List<String> nl = new ArrayList<>();
        nl.add("&7Nickname: " + (nick == null || nick.isEmpty() ? "&8none" : "&r" + nick));
        List<String> prev = u == null ? new ArrayList<String>() : u.getStringList("previous-names");
        nl.add("&7Previous names:");
        if (prev.isEmpty()) {
            nl.add("&8  none");
        } else {
            for (String pn : prev) {
                nl.add("&f  " + pn);
            }
        }
        inv.setItem(22, this.createGuiItem(Material.NAME_TAG, "<gradient:#FF7518:#FFFFFF>&lNam</gradient><gradient:#FFFFFF:#FF7518>&les</gradient>", nl.toArray(new String[0])));

        boolean seeIp = viewer.hasPermission(PERM_IP_VIEW);
        boolean seeAlts = viewer.hasPermission(PERM_ALTS);
        if (seeIp || seeAlts) {
            List<String> il = new ArrayList<>();
            if (seeIp) {
                List<String> ips = this.data.getStringList("playerips." + id);
                il.add("&7IP addresses:");
                if (ips.isEmpty()) {
                    il.add("&8  none on record");
                } else {
                    for (String ip : ips) {
                        il.add("&f  " + ip);
                    }
                }
            }
            if (seeAlts) {
                Map<UUID, String> alts = this.findAlts(id);
                if (seeIp) {
                    il.add(" ");
                }
                il.add("&7Possible alts: &f" + alts.size());
                int shown = 0;
                for (Map.Entry<UUID, String> en : alts.entrySet()) {
                    if (shown++ >= 8) {
                        il.add("&8  ...and " + (alts.size() - 8) + " more");
                        break;
                    }
                    il.add("&f  " + en.getValue() + this.altTags(en.getKey(), en.getValue()));
                }
            }
            inv.setItem(23, this.createGuiItem(Material.ENDER_EYE, seeIp ? "<gradient:#FF7518:#FFFFFF>&lIP</gradient><gradient:#FFFFFF:#FF7518>&ls</gradient> & alts" : "<gradient:#FF7518:#FFFFFF>&lPossibl</gradient><gradient:#FFFFFF:#FF7518>&le alts</gradient>", il.toArray(new String[0])));
        }

        if (staff) {
            int[] st = this.getStats(id);
            inv.setItem(24, this.createGuiItem(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lPunish</gradient><gradient:#FFFFFF:#FF7518>&lments</gradient>",
                    "&#D9381EBans: &f" + st[0], "&#FF7518Mutes: &f" + st[1], "&#FF8330Kicks: &f" + st[2],
                    "&#B15CFFWarnings: &f" + st[3] + " active", "&#8E3BD1Notes: &f" + st[5], " ", "&#FF8330Click &7» open full history"));
            g.slotData[24] = "history";
        }

        if (on != null && online && viewer.hasPermission("unstableffa.invsee")) {
            inv.setItem(25, this.createGuiItem(Material.CHEST, "<gradient:#FF7518:#FFFFFF>&lInven</gradient><gradient:#FFFFFF:#FF7518>&ltory</gradient>", "&7Open their live inventory", " ", "&#FF8330Click to open"));
            g.slotData[25] = "invsee";
        }

        if (on != null && online && viewer.hasPermission("unstableffa.live")) {
            inv.setItem(26, this.createGuiItem(Material.SPYGLASS, "<gradient:#FF7518:#FFFFFF>&lLive </gradient><gradient:#FFFFFF:#FF7518>&lview</gradient>",
                    "&7Gamemode, coordinates, dimension,", "&7health and more - updates live.", " ", "&#FF8330Click to open"));
            g.slotData[26] = "live";
        }

        inv.setItem(38, this.createGuiItem(Material.PAPER, "&f&lCopy UUID", "&8" + id, " ", "&#FF8330Click &7» get a copy link in chat"));
        g.slotData[38] = "uuid";
        inv.setItem(40, this.createGuiItem(Material.ARROW, "&#FF8330&l« Back", "&7Return to the previous menu"));
        g.slotData[40] = "back";
    }

    private void clickProfile(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 45 || !(g.slotData[slot] instanceof String)) {
            return;
        }
        switch ((String) g.slotData[slot]) {
            case "history":
                this.later(() -> this.openHistory(p, Bukkit.getOfflinePlayer(g.target), g.targetName, g));
                break;
            case "invsee": {
                final Player t = Bukkit.getPlayer(g.target);
                if (t != null) {
                    this.later(() -> this.openInvsee(p, t));
                }
                break;
            }
            case "live": {
                final Player lt = Bukkit.getPlayer(g.target);
                if (lt != null) {
                    this.later(() -> this.openLive(p, lt, g));
                }
                break;
            }
            case "uuid": {
                final TextComponent tc = new TextComponent(TextComponent.fromLegacyText("§7UUID of §f" + g.targetName + "§7: §f" + g.target + " §8[click to copy]"));
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, g.target.toString()));
                this.later(() -> {
                    p.closeInventory();
                    p.spigot().sendMessage(tc);
                });
                break;
            }
            case "back":
                this.later(() -> this.goBack(p, g));
                break;
            default:
                break;
        }
    }

    // =====================================================================================
    // /history  - punishment history of one player
    // =====================================================================================

    private void openHistory(Player viewer, OfflinePlayer t, String shownName, Gui parent) {
        Gui g = this.newGui("history", 54, "§8History » " + shownName, parent);
        g.target = t.getUniqueId();
        g.targetName = shownName;
        this.renderHistory(g, viewer);
        viewer.openInventory(g.inv);
    }

    private Material actionMaterial(String action, boolean removed) {
        switch (action) {
            case "BAN":
            case "TEMPBAN":
            case "IPBAN":
                return Material.RED_CONCRETE;
            case "MUTE":
                return Material.ORANGE_CONCRETE;
            case "KICK":
                return Material.YELLOW_CONCRETE;
            case "WARN":
                return removed ? Material.GRAY_CONCRETE : Material.LIGHT_BLUE_CONCRETE;
            case "NOTE":
                return Material.OAK_SIGN;
            default:
                return Material.LIME_CONCRETE;
        }
    }

    private String actionColor(String action, boolean removed) {
        switch (action) {
            case "BAN":
            case "TEMPBAN":
            case "IPBAN":
                return "&#D9381E";
            case "MUTE":
                return "&#FF7518";
            case "KICK":
                return "&#FF8330";
            case "WARN":
                return removed ? "&8" : "&#B15CFF";
            case "NOTE":
                return "&#8E3BD1";
            default:
                return "&#FF9147";
        }
    }

    // Which log entries are still in effect right now (index-aligned with the log list).
    private boolean[] activeFlags(UUID id, String name, List<String> log) {
        boolean[] act = new boolean[log.size()];
        boolean banned = Bukkit.getBanList(Type.NAME).isBanned(name);
        boolean muted = this.isMutedUuid(id);
        boolean banDone = false;
        boolean muteDone = false;
        for (int i = log.size() - 1; i >= 0; --i) {
            String[] p = this.parseEntry(log.get(i));
            switch (p[1]) {
                case "BAN":
                case "TEMPBAN":
                case "IPBAN":
                    if (banned && !banDone) {
                        act[i] = true;
                        banDone = true;
                    }
                    break;
                case "MUTE":
                    if (muted && !muteDone) {
                        act[i] = true;
                        muteDone = true;
                    }
                    break;
                case "WARN":
                    if (p[6].equals("-")) {
                        act[i] = true;
                    }
                    break;
                default:
                    break;
            }
        }
        return act;
    }

    private boolean histMatches(String a, int filter, boolean active) {
        switch (filter) {
            case 1:
                return a.equals("BAN") || a.equals("TEMPBAN") || a.equals("IPBAN");
            case 2:
                return a.equals("MUTE");
            case 3:
                return a.equals("WARN");
            case 4:
                return a.equals("KICK");
            case 5:
                return a.equals("NOTE");
            case 6:
                return active;
            default:
                return true;
        }
    }

    // state: 0 = not trackable, 1 = active, 2 = ended/removed. player != null for the server-wide list.
    private ItemStack historyItem(String[] p, int state, int warnNo, String player, String hint) {
        String action = p[1];
        boolean warnRemoved = action.equals("WARN") && !p[6].equals("-");
        String tag = "";
        if (state == 1) {
            tag = " &8» &#FF9147ACTIVE";
        } else if (state == 2) {
            tag = warnRemoved ? " &8» &7Removed" : " &8» &7Ended";
        }
        String title = this.actionColor(action, warnRemoved) + "&l" + action + (player != null ? " &8» &f" + player : "") + tag;

        List<String> lore = new ArrayList<>();
        String ag = this.agoMs(p[0]);
        lore.add("&7When: &f" + this.formatDate(p[0]) + (ag.isEmpty() ? "" : " &8(" + ag + ")"));
        lore.add("&7Staff: &f" + p[3]);
        if (!action.equals("NOTE") && !action.equals("PARDON") && !p[2].equals("-")) {
            lore.add("&7Duration: &f" + p[2]);
        }
        lore.add(action.equals("NOTE") ? "&7Note:" : "&7Reason:");
        lore.addAll(this.wrap(p[4], 34, "&f  "));
        if (!p[5].equals("-")) {
            lore.add("&7Ban ID: &f#" + p[5]);
        }
        if (warnNo > 0) {
            lore.add("&7Warning &f#" + warnNo);
        }
        if (warnRemoved) {
            lore.add("&8Removed by &f" + p[6]);
        }
        if (hint != null) {
            lore.add(" ");
            lore.add(hint);
        }
        ItemStack it = this.createGuiItem(this.actionMaterial(action, warnRemoved), title, lore.toArray(new String[0]));
        if (state == 1) {
            this.glow(it);
        }
        return it;
    }

    private void renderHistoryRaw(Gui g, Player viewer) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        UUID id = g.target;
        String name = g.targetName;
        boolean staff = this.canPunishAny(viewer);

        List<String> log = this.data.getStringList("punishlog." + id);
        boolean[] act = this.activeFlags(id, name, log);
        int[] st = this.getStats(id);
        boolean banned = Bukkit.getBanList(Type.NAME).isBanned(name);
        boolean muted = this.isMutedUuid(id);

        int[] warnNo = new int[log.size()];
        int wc = 0;
        for (int i = 0; i < log.size(); i++) {
            if (this.parseEntry(log.get(i))[1].equals("WARN")) {
                warnNo[i] = ++wc;
            }
        }

        // Build the list of matching entries, in the chosen order (sort 0 = newest first).
        List<Integer> idxs = new ArrayList<>();
        for (int n = 0; n < log.size(); n++) {
            int i = g.sort == 0 ? log.size() - 1 - n : n;
            if (this.histMatches(this.parseEntry(log.get(i))[1], g.filter, act[i])) {
                idxs.add(i);
            }
        }
        int total = idxs.size();
        int pages = Math.max(1, (total + 35) / 36);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        // ----- header -----
        ItemStack frame = this.pane();
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(0, g.parent != null
                ? this.createGuiItem(Material.ARROW, "&#FF8330&l« Back", "&7Return to the previous menu")
                : this.createGuiItem(Material.ARROW, "&#FF8330&l« Recent punishments", "&7Server-wide list"));
        inv.setItem(1, this.optionItem(Material.HOPPER, "&#B15CFF&lFilter: &f", HIST_FILTERS, g.filter,
                "&#FF8330Click &7» next &8| &#FF8330Right-click &7» previous"));
        inv.setItem(2, this.optionItem(Material.CLOCK, "&#B15CFF&lSort: &f", new String[]{"Newest first", "Oldest first"}, g.sort,
                "&#FF8330Click &7» switch"));

        List<String> sl = new ArrayList<>();
        sl.add("&#D9381EBans: &f" + st[0]);
        sl.add("&#FF7518Mutes: &f" + st[1]);
        sl.add("&#FF8330Kicks: &f" + st[2]);
        sl.add("&#B15CFFWarnings: &f" + st[3] + " active &8/ &f" + st[4] + " removed");
        sl.add("&#8E3BD1Notes: &f" + st[5]);
        ConfigurationSection oc = this.data.getConfigurationSection("history." + id);
        if (oc != null && !oc.getKeys(false).isEmpty()) {
            sl.add(" ");
            sl.add("&7Offense counters &8(/punish)");
            for (String k : oc.getKeys(false)) {
                sl.add("&f" + k.replace("_", " ") + " &8» &f" + oc.getInt(k));
            }
        }
        inv.setItem(3, this.createGuiItem(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lOver</gradient><gradient:#FFFFFF:#FF7518>&lview</gradient>", sl.toArray(new String[0])));

        List<String> hl = new ArrayList<>();
        hl.add(Bukkit.getPlayer(id) != null ? "&7Status: &#FF9147Online" : "&7Status: &#D9381EOffline");
        hl.add("&7Banned: " + (banned ? "&#D9381EYes" : "&#FF9147No"));
        if (muted) {
            long exp = this.data.getLong("mutes." + id);
            hl.add("&7Muted: &#FF7518" + (exp == -1L ? "Permanent" : this.formatUptime(exp - System.currentTimeMillis()) + " left"));
        } else {
            hl.add("&7Muted: &#FF9147No");
        }
        hl.add("&7Active warnings: &f" + st[3]);
        hl.add("&8" + id);
        inv.setItem(4, this.skull(Bukkit.getOfflinePlayer(id), "&#B15CFF&l" + name, hl));

        if (viewer.hasPermission(PERM_ALTS)) {
            Map<UUID, String> alts = this.findAlts(id);
            List<String> al = new ArrayList<>();
            al.add("&7Accounts sharing an IP");
            int shown = 0;
            for (Map.Entry<UUID, String> en : alts.entrySet()) {
                if (shown++ >= 8) {
                    al.add("&8...and " + (alts.size() - 8) + " more");
                    break;
                }
                al.add("&f" + en.getValue() + this.altTags(en.getKey(), en.getValue()));
            }
            if (alts.isEmpty()) {
                al.add("&8None found");
            }
            inv.setItem(5, this.createGuiItem(Material.ENDER_EYE, "&#8E3BD1&lPossible alts: &f" + alts.size(), al.toArray(new String[0])));
        }

        inv.setItem(6, this.createGuiItem(Material.NAME_TAG, "<gradient:#FF7518:#FFFFFF>&lProf</gradient><gradient:#FFFFFF:#FF7518>&lile</gradient>", "&7Open the player card", " ", "&#FF8330Click to open"));
        if (staff && banned) {
            inv.setItem(7, this.createGuiItem(Material.LIME_DYE, "<gradient:#FF7518:#FFFFFF>&lUnb</gradient><gradient:#FFFFFF:#FF7518>&lan</gradient> " + name, "&7Lift the ban right now.", " ", "&#FF8330Click to unban"));
            g.slotData[7] = "unban";
        }
        if (staff && muted) {
            inv.setItem(8, this.createGuiItem(Material.LIGHT_BLUE_DYE, "<gradient:#FF7518:#FFFFFF>&lUnm</gradient><gradient:#FFFFFF:#FF7518>&lute</gradient> " + name, "&7Lift the mute right now.", " ", "&#FF8330Click to unmute"));
            g.slotData[8] = "unmute";
        }

        // ----- entries -----
        int start = g.page * 36;
        for (int k = 0; k < 36; k++) {
            int pos = start + k;
            if (pos >= total) {
                break;
            }
            int i = idxs.get(pos);
            String[] p = this.parseEntry(log.get(i));
            String a = p[1];
            boolean trackable = a.equals("BAN") || a.equals("TEMPBAN") || a.equals("IPBAN") || a.equals("MUTE") || a.equals("WARN");
            int state = trackable ? (act[i] ? 1 : 2) : 0;
            String hint = null;
            if (staff && state == 1) {
                hint = a.equals("MUTE") ? "&#FF8330Right-click &7» unmute" : a.equals("WARN") ? "&#FF8330Right-click &7» remove this warning" : "&#FF8330Right-click &7» unban";
            }
            inv.setItem(9 + k, this.historyItem(p, state, warnNo[i], null, hint));
            g.slotData[9 + k] = i;
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lNothin</gradient><gradient:#FFFFFF:#FF7518>&lg here</gradient>", "&7No entries match this filter."));
        }
        this.renderPager(g, pages, total);
    }

    private void clickHistory(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        boolean staff = this.canPunishAny(p);
        boolean right = e.isRightClick();
        final String name = g.targetName;

        if (slot == 0) {
            this.later(() -> this.goBack(p, g));
            return;
        }
        if (slot == 1) {
            g.filter = this.cycle(g.filter, HIST_FILTERS.length, right);
            g.page = 0;
            this.renderHistory(g, p);
            return;
        }
        if (slot == 2) {
            g.sort = this.cycle(g.sort, 2, right);
            g.page = 0;
            this.renderHistory(g, p);
            return;
        }
        if (slot == 6) {
            this.later(() -> this.openProfile(p, g.target, g));
            return;
        }
        if (slot == 7 || slot == 8) {
            if (!staff) {
                return;
            }
            Object d = g.slotData[slot];
            if ("unban".equals(d)) {
                this.handlePunishment(p, "unban", new String[]{name, "History", "GUI"});
                this.renderHistory(g, p);
            } else if ("unmute".equals(d)) {
                this.handlePunishment(p, "unmute", new String[]{name, "History", "GUI"});
                this.renderHistory(g, p);
            }
            return;
        }
        if (this.handlePager(g, slot)) {
            this.renderHistory(g, p);
            return;
        }
        if (slot >= 9 && slot <= 44 && g.slotData[slot] instanceof Integer && staff && right) {
            int idx = (Integer) g.slotData[slot];
            List<String> log = this.data.getStringList("punishlog." + g.target);
            if (idx < 0 || idx >= log.size()) {
                return;
            }
            boolean[] act = this.activeFlags(g.target, name, log);
            if (!act[idx]) {
                return;
            }
            String[] ent = this.parseEntry(log.get(idx));
            switch (ent[1]) {
                case "BAN":
                case "TEMPBAN":
                case "IPBAN":
                    this.handlePunishment(p, "unban", new String[]{name, "History", "GUI"});
                    if (ent[1].equals("IPBAN")) {
                        p.sendMessage("§7Tip: use §f/unipban " + name + " §7to lift the IP ban too.");
                    }
                    break;
                case "MUTE":
                    this.handlePunishment(p, "unmute", new String[]{name, "History", "GUI"});
                    break;
                case "WARN":
                    if (this.performUnwarn(p, Bukkit.getOfflinePlayer(g.target), name, idx)) {
                        p.sendMessage("§aWarning removed.");
                    }
                    break;
                default:
                    return;
            }
            this.renderHistory(g, p);
        }
    }

    // =====================================================================================
    // /history (no name)  - server-wide recent punishments
    // =====================================================================================

    private void openRecent(Player viewer, Gui parent) {
        Gui g = this.newGui("recent", 54, T_RECENT, parent);
        this.renderRecent(g, viewer);
        viewer.openInventory(g.inv);
    }

    private boolean recentMatches(String a, int filter) {
        switch (filter) {
            case 1:
                return a.equals("BAN") || a.equals("TEMPBAN") || a.equals("IPBAN");
            case 2:
                return a.equals("MUTE");
            case 3:
                return a.equals("WARN");
            case 4:
                return a.equals("KICK");
            case 5:
                return a.equals("UNBAN") || a.equals("UNMUTE") || a.equals("PARDON");
            default:
                return true;
        }
    }

    private void renderRecentRaw(Gui g, Player viewer) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);

        List<HistEntry> all = new ArrayList<>();
        ConfigurationSection ps = this.data.getConfigurationSection("punishlog");
        if (ps != null) {
            for (String uuid : ps.getKeys(false)) {
                for (String line : this.data.getStringList("punishlog." + uuid)) {
                    String[] p = this.parseEntry(line);
                    if (p[1].equals("NOTE") || !this.recentMatches(p[1], g.filter)) {
                        continue;
                    }
                    long ts;
                    try {
                        ts = Long.parseLong(p[0]);
                    } catch (NumberFormatException ex) {
                        ts = 0L;
                    }
                    all.add(new HistEntry(ts, uuid, line));
                }
            }
        }
        all.sort((a, b) -> Long.compare(b.ts, a.ts));

        int total = all.size();
        int pages = Math.max(1, (total + 35) / 36);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        ItemStack frame = this.pane();
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(0, g.parent != null
                ? this.createGuiItem(Material.ARROW, "&#FF8330&l« Back", "&7Return to the previous menu")
                : this.createGuiItem(Material.COMPASS, "&#FF8330&l« Player browser", "&7Search every known player"));
        inv.setItem(1, this.optionItem(Material.HOPPER, "&#B15CFF&lFilter: &f", RECENT_FILTERS, g.filter,
                "&#FF8330Click &7» next &8| &#FF8330Right-click &7» previous"));
        inv.setItem(4, this.createGuiItem(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lRecent Pu</gradient><gradient:#FFFFFF:#FF7518>&lnishments</gradient>",
                "&7Latest actions on the whole server", "&7Showing: &f" + total, " ", "&#FF8330Click an entry &7» that player's history"));

        int start = g.page * 36;
        for (int k = 0; k < 36; k++) {
            int pos = start + k;
            if (pos >= total) {
                break;
            }
            HistEntry he = all.get(pos);
            String[] p = this.parseEntry(he.line);
            int state = p[1].equals("WARN") ? (p[6].equals("-") ? 1 : 2) : 0;
            inv.setItem(9 + k, this.historyItem(p, state, 0, this.nameForUuid(he.uuid), "&#FF8330Click &7» open history"));
            g.slotData[9 + k] = he.uuid;
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lNothin</gradient><gradient:#FFFFFF:#FF7518>&lg here</gradient>", "&7No punishments match this filter."));
        }
        this.renderPager(g, pages, total);
    }

    private void clickRecent(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot == 0) {
            this.later(() -> this.goBack(p, g));
            return;
        }
        if (slot == 1) {
            g.filter = this.cycle(g.filter, RECENT_FILTERS.length, e.isRightClick());
            g.page = 0;
            this.renderRecent(g, p);
            return;
        }
        if (this.handlePager(g, slot)) {
            this.renderRecent(g, p);
            return;
        }
        if (slot >= 9 && slot <= 44 && g.slotData[slot] instanceof String && this.canPunishAny(p)) {
            final String u = (String) g.slotData[slot];
            try {
                final UUID id = UUID.fromString(u);
                this.later(() -> this.openHistory(p, Bukkit.getOfflinePlayer(id), this.nameForUuid(u), g));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // =====================================================================================
    // /zenithsettings
    // =====================================================================================

    // Settings shown in /zenithsettings. "staff" settings get their own row and only show up
    // for people with the right permission.
    private List<Setting> allSettings(Player p) {
        List<Setting> out = new ArrayList<>();
        out.add(new Setting("messages", Material.WRITABLE_BOOK, "Private Messages",
                "Let other players send you /msg. Staff can always reach you.", null, false,
                pl -> !this.isMsgDisabled(pl),
                (pl, v) -> this.setSetting(pl.getUniqueId(), "msg-disabled", !v)));
        out.add(new Setting("tpa", Material.ENDER_PEARL, "Teleport Requests",
                "Let other players send you /tpa and /tpahere requests.", null, false,
                pl -> !this.tpDisabled.contains(pl.getUniqueId()),
                (pl, v) -> {
                    if (v) {
                        this.tpDisabled.remove(pl.getUniqueId());
                    } else {
                        this.tpDisabled.add(pl.getUniqueId());
                    }
                    this.setSetting(pl.getUniqueId(), "tpa-disabled", !v);
                }));
        out.add(new Setting("tpauto", Material.ENDER_EYE, "Auto-accept Teleports",
                "Accept every teleport request automatically.", "unstableffa.tpauto", false,
                pl -> this.tpAuto.contains(pl.getUniqueId()),
                (pl, v) -> {
                    if (v) {
                        this.tpAuto.add(pl.getUniqueId());
                    } else {
                        this.tpAuto.remove(pl.getUniqueId());
                    }
                    this.setSetting(pl.getUniqueId(), "tpa-auto", v);
                }));
        out.add(new Setting("sounds", Material.NOTE_BLOCK, "Sounds",
                "Play a sound when you get a message or someone says your name.", null, false,
                pl -> this.getSetting(pl.getUniqueId(), "sounds", true),
                (pl, v) -> this.setSetting(pl.getUniqueId(), "sounds", v)));

        out.add(new Setting("alerts", Material.BELL, "Staff Alerts",
                "Join, report, chat filter and note alerts.", PERM_STAFF_ALERTS, true,
                pl -> this.getSetting(pl.getUniqueId(), "staff-alerts", true),
                (pl, v) -> this.setSetting(pl.getUniqueId(), "staff-alerts", v)));
        out.add(new Setting("vanish", Material.FEATHER, "Vanish Messages",
                "Send a fake join/leave message when you use /vanish.", "unstableffa.vanish", true,
                pl -> this.getSetting(pl.getUniqueId(), "vanish-messages", true),
                (pl, v) -> this.setSetting(pl.getUniqueId(), "vanish-messages", v)));
        out.add(new Setting("socialspy", Material.SPYGLASS, "Social Spy",
                "See private messages between other players.", "unstableffa.socialspy", true,
                pl -> this.socialSpy.contains(pl.getUniqueId()),
                (pl, v) -> {
                    if (v) {
                        this.socialSpy.add(pl.getUniqueId());
                    } else {
                        this.socialSpy.remove(pl.getUniqueId());
                    }
                    this.setSetting(pl.getUniqueId(), "socialspy", v);
                }));

        out.removeIf(st -> st.perm != null && !p.hasPermission(st.perm));
        return out;
    }

    private List<String> settingKeys(Player p) {
        List<String> keys = new ArrayList<>();
        for (Setting s : this.allSettings(p)) {
            keys.add(s.key);
        }
        return keys;
    }

    // /zenithsettings <key>  toggles one setting without opening the menu.
    private boolean toggleSettingByKey(Player p, String key) {
        for (Setting s : this.allSettings(p)) {
            if (s.key.equalsIgnoreCase(key)) {
                boolean now = !s.get.test(p);
                s.set.accept(p, now);
                p.sendMessage("§7" + s.name + ": " + (now ? "§aOn" : "§cOff"));
                return true;
            }
        }
        return false;
    }

    private void persistFlag(UUID id, String key, boolean v) {
        String path = "settings." + id + "." + key;
        if (v) {
            this.data.set(path, true);
        } else if (this.data.contains(path)) {
            this.data.set(path, (Object) null);
        }
    }

    // Call on join: restores settings that live in memory sets.
    private void loadPlayerSettings(Player p) {
        UUID id = p.getUniqueId();
        if (this.getSetting(id, "tpa-disabled", false)) {
            this.tpDisabled.add(id);
        }
        if (this.getSetting(id, "tpa-auto", false) && p.hasPermission("unstableffa.tpauto")) {
            this.tpAuto.add(id);
        }
        if (this.getSetting(id, "socialspy", false) && p.hasPermission("unstableffa.socialspy")) {
            this.socialSpy.add(id);
        }
    }

    // Call on quit: saves those sets (also catches /tpatoggle, /tpauto and /socialspy) and frees memory.
    private void savePlayerSettings(Player p) {
        UUID id = p.getUniqueId();
        this.persistFlag(id, "tpa-disabled", this.tpDisabled.contains(id));
        this.persistFlag(id, "tpa-auto", this.tpAuto.contains(id));
        this.persistFlag(id, "socialspy", this.socialSpy.contains(id));
        this.tpDisabled.remove(id);
        this.tpAuto.remove(id);
        this.socialSpy.remove(id);
        this.searchPending.remove(id);
        this.voiceNotifyCooldown.remove(id);
        this.saveD();
    }

    private boolean wantsAlert(Player staff) {
        return this.getSetting(staff.getUniqueId(), "staff-alerts", true);
    }

    private void playMsgSound(Player t) {
        if (this.getSetting(t.getUniqueId(), "sounds", true)) {
            t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.6F);
        }
    }

    private void openSettings(Player p) {
        boolean hasStaff = false;
        for (Setting s : this.allSettings(p)) {
            hasStaff |= s.staff;
        }
        Gui g = this.newGui("settings", hasStaff ? 45 : 27, T_SETTINGS, null);
        this.renderSettings(g, p);
        p.openInventory(g.inv);
    }

    // Puts a row of settings centred inside one inventory row.
    private void placeSettingRow(Gui g, Player p, List<Setting> row, int rowStart) {
        int n = Math.min(row.size(), 7);
        // spread them out with a gap when there are few, so the menu doesn't look cramped
        int step = n <= 4 ? 2 : 1;
        int width = (n - 1) * step + 1;
        int first = rowStart + (9 - width) / 2;
        for (int i = 0; i < n; i++) {
            Setting s = row.get(i);
            boolean on = s.get.test(p);
            List<String> lore = new ArrayList<>(this.wrap(s.desc, 32, "&7"));
            if (s.staff) {
                lore.add("&8Staff setting");
            }
            lore.add(" ");
            lore.add(on ? "&7Status: &#FF9147On" : "&7Status: &#D9381EOff");
            lore.add("&#FF8330Click to turn " + (on ? "off" : "on"));
            int slot = first + i * step;
            // Just two items: lime dye = on, gray dye = off.
            g.inv.setItem(slot, this.createGuiItem(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "&#FF9147" : "&7") + s.name, lore.toArray(new String[0])));
            g.slotData[slot] = s.key;
        }
    }

    private void renderSettingsRaw(Gui g, Player p) {
        Inventory inv = g.inv;
        int size = inv.getSize();
        inv.clear();
        Arrays.fill(g.slotData, null);

        List<Setting> player = new ArrayList<>();
        List<Setting> staff = new ArrayList<>();
        for (Setting s : this.allSettings(p)) {
            (s.staff ? staff : player).add(s);
        }

        this.placeSettingRow(g, p, player, 9);
        if (!staff.isEmpty() && size >= 45) {
            this.placeSettingRow(g, p, staff, 27); // staff settings get their own row below
        }
        int closeSlot = size - 5;
        inv.setItem(closeSlot, this.createGuiItem(Material.BARRIER, "&#D9381EClose"));
        g.slotData[closeSlot] = "close";
    }

    private void clickSettings(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= g.inv.getSize() || !(g.slotData[slot] instanceof String)) {
            return;
        }
        String key = (String) g.slotData[slot];
        if (key.equals("close")) {
            this.later(p::closeInventory);
            return;
        }
        for (Setting s : this.allSettings(p)) {
            if (s.key.equals(key)) {
                boolean now = !s.get.test(p);
                s.set.accept(p, now);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, now ? 1.4F : 0.8F);
                this.renderSettings(g, p);
                return;
            }
        }
    }

    // Mention sounds. Runs after the normal onChat.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChatSettings(AsyncPlayerChatEvent e) {
        final Player sender = e.getPlayer();
        // Players being screenshared don't see public chat.
        this.screenShare.filterRecipients(e);
        // /ignore and /block: hide this message from people who don't want to see it.
        if (!this.ignoreMap.isEmpty() || !this.blockMap.isEmpty()) {
            final UUID sid = sender.getUniqueId();
            e.getRecipients().removeIf(r -> !r.equals(sender) && this.hidesChatFrom(r.getUniqueId(), sid));
        }
        final String msg = e.getMessage().toLowerCase();
        final List<UUID> mentioned = new ArrayList<>();
        for (Player r : e.getRecipients()) {
            if (r.equals(sender)) {
                continue;
            }
            Pattern pat = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(r.getName().toLowerCase()) + "(?![a-z0-9_])");
            if (pat.matcher(msg).find()) {
                mentioned.add(r.getUniqueId());
            }
        }
        if (!mentioned.isEmpty()) {
            Bukkit.getScheduler().runTask(this, () -> {
                for (UUID id : mentioned) {
                    Player r = Bukkit.getPlayer(id);
                    if (r != null && this.getSetting(id, "sounds", true)) {
                        r.playSound(r.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.5F);
                    }
                }
            });
        }
    }

    // =====================================================================================
    // /nick
    // =====================================================================================

    private void applyNick(Player p) {
        String nick = this.data.getString("users." + this.userKey(p.getName()) + ".nick");
        if (nick == null || nick.isEmpty()) {
            p.setDisplayName(p.getName());
            p.setPlayerListName(null);
        } else {
            String shown = this.translate(nick) + "§r";
            p.setDisplayName(shown);
            p.setPlayerListName(shown);
        }
    }

    private void handleNick(CommandSender s, String[] args) {
        if (args.length < 1) {
            s.sendMessage("§cUsage: /nick <nickname|off>" + (s.hasPermission("unstableffa.nick.others") ? " §7or §c/nick <player> <nickname|off>" : ""));
            return;
        }
        Player target;
        String raw;
        if (args.length >= 2) {
            if (!s.hasPermission("unstableffa.nick.others")) {
                s.sendMessage(this.getMsg("no-permission"));
                return;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                s.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
                return;
            }
            raw = args[1];
        } else {
            if (!(s instanceof Player)) {
                s.sendMessage("§cConsole usage: /nick <player> <nickname|off>");
                return;
            }
            if (!s.hasPermission("unstableffa.nick")) {
                s.sendMessage(this.getMsg("no-permission"));
                return;
            }
            target = (Player) s;
            raw = args[0];
        }

        String base = "users." + this.userKey(target.getName());
        if (!this.data.contains(base + ".uuid")) {
            s.sendMessage("§cThat player has no record yet. Try again in a moment.");
            return;
        }

        if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("reset") || raw.equalsIgnoreCase("clear")) {
            this.data.set(base + ".nick", (Object) null);
            this.saveD();
            this.applyNick(target);
            s.sendMessage(s == target ? "§aNickname removed." : "§aNickname removed for §f" + target.getName() + "§a.");
            return;
        }

        if (!s.hasPermission("unstableffa.nick.color")) {
            raw = raw.replaceAll("(?i)&#[0-9a-f]{6}", "").replaceAll("(?i)&[0-9a-fk-or]", "");
        }
        String plain = ChatColor.stripColor(this.translate(raw));
        int max = this.getConfig().getInt("nick-max-length", 16);
        if (!plain.matches("[A-Za-z0-9_]{3," + max + "}")) {
            s.sendMessage("§cNicknames must be 3-" + max + " characters: letters, numbers and _ only.");
            return;
        }

        if (!s.hasPermission("unstableffa.nick.bypass")) {
            UUID holder = this.nameIndex.get(plain.toLowerCase());
            if (holder != null && !holder.equals(target.getUniqueId())) {
                s.sendMessage("§cThat name belongs to another player.");
                return;
            }
            ConfigurationSection us = this.data.getConfigurationSection("users");
            if (us != null) {
                for (String k : us.getKeys(false)) {
                    String other = us.getString(k + ".nick");
                    if (other == null || k.equals(this.userKey(target.getName()))) {
                        continue;
                    }
                    if (ChatColor.stripColor(this.translate(other)).equalsIgnoreCase(plain)) {
                        s.sendMessage("§cThat nickname is already taken.");
                        return;
                    }
                }
            }
        }

        this.data.set(base + ".nick", raw);
        this.saveD();
        this.applyNick(target);
        s.sendMessage("§aNickname set to §r" + target.getDisplayName());
    }

    // =====================================================================================
    // /seen (router), /nick, /playtime, /whois, /mutelist   (REPLACES the old handleExtra)
    // =====================================================================================

    private void handleExtra(CommandSender s, String n, String[] args) {
        switch (n) {
            case "seen":
                this.handleSeen(s, args);
                return;
            case "nick":
                this.handleNick(s, args);
                return;
            case "live":
                this.handleLive(s, args);
                return;
            case "playtime": {
                OfflinePlayer t = args.length > 0 ? this.resolveOffline(args[0]) : (s instanceof Player ? (Player) s : null);
                if (t == null) {
                    s.sendMessage("§cUsage: /playtime <player>");
                    return;
                }
                if (this.userSection(t.getUniqueId()) == null) {
                    s.sendMessage("§cNo playtime recorded for that player.");
                    return;
                }
                s.sendMessage("§f" + this.displayName(t, args.length > 0 ? args[0] : "You") + " §7has played §f"
                        + this.formatUptime(this.playtimeMs(t.getUniqueId())));
                return;
            }
            case "whois": {
                if (!s.hasPermission("unstableffa.whois")) {
                    s.sendMessage(this.getMsg("no-permission"));
                    return;
                }
                if (args.length < 1) {
                    s.sendMessage("§cUsage: /whois <player|uuid>");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[0]);
                UUID id = t.getUniqueId();
                ConfigurationSection u = this.userSection(id);
                if (u == null) {
                    s.sendMessage("§cNo record of " + args[0] + ".");
                    return;
                }
                String name = u.getString("name", args[0]);
                s.sendMessage("§b§lWHOIS §f" + name + this.altTags(id, name));

                String uuidLine = "§7UUID: §f" + id + " §8[click to copy]";
                if (s instanceof Player) {
                    TextComponent tc = new TextComponent(TextComponent.fromLegacyText(uuidLine));
                    tc.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString()));
                    ((Player) s).spigot().sendMessage(tc);
                } else {
                    s.sendMessage(uuidLine);
                }

                String nick = u.getString("nick");
                List<String> prev = u.getStringList("previous-names");
                if (nick != null && !nick.isEmpty()) {
                    s.sendMessage("§7Nickname: §r" + this.translate(nick));
                }
                s.sendMessage("§7First joined: §f" + u.getString("first-join", "unknown"));
                s.sendMessage("§7Last seen: §f" + u.getString("last-seen", "unknown"));
                s.sendMessage("§7Playtime: §f" + this.formatUptime(this.playtimeMs(id)));
                s.sendMessage("§7Previous names: §f" + (prev.isEmpty() ? "none" : String.join("§7, §f", prev)));
                if (s.hasPermission(PERM_IP_VIEW)) {
                    List<String> ips = this.data.getStringList("playerips." + id);
                    s.sendMessage("§7IPs: §f" + (ips.isEmpty() ? "none" : String.join("§7, §f", ips)));
                }
                if (s.hasPermission(PERM_ALTS)) {
                    Map<UUID, String> alts = this.findAlts(id);
                    s.sendMessage("§7Possible alts: §f" + (alts.isEmpty() ? "none" : String.join("§7, §f", alts.values())));
                }
                s.sendMessage("§7Punishments: §f" + this.historySummary(id));
                return;
            }
            case "mutelist": {
                if (!s.hasPermission("unstableffa.mutelist")) {
                    s.sendMessage(this.getMsg("no-permission"));
                    return;
                }
                ConfigurationSection ms = this.data.getConfigurationSection("mutes");
                List<String> lines = new ArrayList<>();
                if (ms != null) {
                    for (String key : new ArrayList<>(ms.getKeys(false))) {
                        try {
                            UUID id = UUID.fromString(key);
                            if (!this.isMutedUuid(id)) {
                                continue;
                            }
                            long exp = this.data.getLong("mutes." + key);
                            String left = exp == -1L ? "permanent" : this.formatUptime(exp - System.currentTimeMillis()) + " left";
                            lines.add("§7- §f" + this.nameForUuid(key) + " §8(§6" + left + "§8)");
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (lines.isEmpty()) {
                    s.sendMessage("§aNobody is muted right now.");
                    return;
                }
                s.sendMessage("§b§lMUTED PLAYERS §7(" + lines.size() + ")");
                lines.forEach(s::sendMessage);
                return;
            }
            default:
                return;
        }
    }

    // =====================================================================================
    // users index: touchUser  (REPLACES the old touchUser; now keeps the nickname on a rename)
    // =====================================================================================

    private void touchUser(Player p) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        String key = this.userKey(name);
        String first = this.now();
        long seconds = 0L;
        String nick = null;
        List<String> prev = new ArrayList<>();

        String oldName = this.uuidIndex.get(id);
        if (oldName != null) {
            String oldKey = this.userKey(oldName);
            ConfigurationSection old = this.data.getConfigurationSection("users." + oldKey);
            if (old != null) {
                first = old.getString("first-join", first);
                seconds = old.getLong("playtime-seconds", 0L);
                nick = old.getString("nick");
                prev = new ArrayList<>(old.getStringList("previous-names"));
            }
            if (!oldName.equals(name)) {
                if (!prev.contains(oldName)) {
                    prev.add(oldName);
                }
                this.data.set("users." + oldKey, (Object) null);
                this.nameIndex.remove(oldName.toLowerCase());
            }
        }

        String ip = (p.getAddress() != null && p.getAddress().getAddress() != null)
                ? p.getAddress().getAddress().getHostAddress() : "unknown";
        String base = "users." + key;
        this.data.set(base + ".name", name);
        this.data.set(base + ".uuid", id.toString());
        this.data.set(base + ".first-join", first);
        this.data.set(base + ".last-join", this.now());
        this.data.set(base + ".last-seen", this.now());
        this.data.set(base + ".last-ip", ip);
        this.data.set(base + ".playtime-seconds", seconds);
        this.data.set(base + ".previous-names", prev);
        if (nick != null && !nick.isEmpty()) {
            this.data.set(base + ".nick", nick);
        }

        this.nameIndex.put(name.toLowerCase(), id);
        this.uuidIndex.put(id, name);
        this.sessionStart.put(id, System.currentTimeMillis());
        this.saveD();
    }

    // =====================================================================================
    // Bridge for the helper classes (QuestManager, BanLog, ...). Package-private on purpose.
    // =====================================================================================

    FileConfiguration msgs() {
        return this.messages;
    }

    FileConfiguration data() {
        return this.data;
    }

    void saveData() {
        this.saveD();
    }

    void saveDataNow() {
        this.saveD();
    }

    void saveModules() {
        if (this.quests != null) {
            this.quests.saveAll();
            this.staffActivity.save();
        }
    }

    public void error(CommandSender s, String msg) {
        if (s == null) {
            this.getLogger().warning(msg);
            return;
        }
        s.sendMessage(this.translate("&8» &#D9381E&lError! &7" + msg));
    }

    public void info(CommandSender s, String msg) {
        if (s == null) {
            this.getLogger().info(msg);
            return;
        }
        s.sendMessage(this.translate(msg));
    }

    public void success(CommandSender s, String msg) {
        if (s == null) {
            this.getLogger().info(msg);
            return;
        }
        s.sendMessage(this.translate("&8» &#4CAF50&lSuccess! &7" + msg));
    }

    public void usage(CommandSender s, String msg) {
        if (s == null) {
            this.getLogger().info("Usage: " + msg);
            return;
        }
        s.sendMessage(this.translate("&8» &#FFB000&lUsage: &7" + msg));
    }

    String color(String s) {
        return this.translate(s);
    }

    // ------------------------------------------------------------------ freeze

    private final Map<UUID, float[]> frozenSpeeds = new HashMap<>();

    /** /freeze, the modmode blue ice and the /live button all come here, so the messages show once. */
    private void toggleFreeze(Player staff, Player t) {
        if (this.screenShare.inSession(t.getUniqueId())) {
            staff.sendMessage(this.translate("&8» &#D9381E&lError! &f" + t.getName() + " &7is being screenshared. End it with &f/ss end&7."));
            return;
        }
        boolean on = !this.frozen.contains(t.getUniqueId());
        this.setFrozen(t, on, false);
        if (!staff.equals(t)) {
            staff.sendMessage(this.translate(on ? "&8» &#B15CFF&lFrozen! &f" + t.getName() + " &7can't move now. Run it again to unfreeze."
                    : "&8» &#FF9147&lUnfrozen! &f" + t.getName() + " &7can move again."));
        }
    }

    boolean isFrozen(UUID id) {
        return this.frozen.contains(id);
    }

    /** Freeze or unfreeze. silent = no message to the player (the screenshare shows its own). */
    void setFrozen(Player t, boolean on, boolean silent) {
        UUID id = t.getUniqueId();
        if (on) {
            if (!this.frozen.add(id)) {
                return;
            }
            this.frozenSpeeds.put(id, new float[]{t.getWalkSpeed() > 0F ? t.getWalkSpeed() : 0.2F, t.getFlySpeed() > 0F ? t.getFlySpeed() : 0.1F});
            t.setWalkSpeed(0F);
            t.setFlySpeed(0F);
            t.setSprinting(false);
            if (!silent) {
                t.sendTitle(this.translate("<gradient:#FF7518:#FFFFFF>&lFRO</gradient><gradient:#FFFFFF:#FF7518>&lZEN</gradient>"), this.translate("&7Don't log out. Staff will talk to you."), 5, 60, 10);
                t.sendMessage(this.translate("&8» &#B15CFF&lFrozen! &7A staff member froze you. Don't log out, and wait for instructions."));
            }
        } else {
            if (!this.frozen.remove(id)) {
                return;
            }
            float[] sp = this.frozenSpeeds.remove(id);
            t.setWalkSpeed(sp == null ? 0.2F : sp[0]);
            t.setFlySpeed(sp == null ? 0.1F : sp[1]);
            if (!silent) {
                t.sendMessage(this.translate("&8» &#FF9147&lUnfrozen! &7You can move again."));
            }
        }
    }

    ConfigurationSection userData(UUID id) {
        return this.userSection(id);
    }

    String duration(long ms) {
        return this.formatUptime(Math.max(0L, ms));
    }

    long parseDuration(String s) {
        return this.parseTime(s);
    }

    long playtimeOf(UUID id) {
        return this.playtimeMs(id);
    }

    OfflinePlayer offline(String nameOrUuid) {
        return this.resolveOffline(nameOrUuid);
    }

    String nameFor(UUID id, String fallback) {
        String n = this.uuidIndex.get(id);
        if (n != null) {
            return n;
        }
        n = this.data.getString("punishnames." + id);
        if (n == null) {
            n = this.data.getString("playernames." + id);
        }
        if (n == null) {
            n = Bukkit.getOfflinePlayer(id).getName();
        }
        return n == null ? fallback : n;
    }

    Map<UUID, String> altsOf(UUID id) {
        return this.findAlts(id);
    }

    // Vanish on/off without the fake join/leave message (used by /modmode).
    void setVanishedQuiet(Player p, boolean on) {
        if (on == this.vanished.contains(p.getUniqueId())) {
            return;
        }
        if (on) {
            this.vanished.add(p.getUniqueId());
            Bukkit.getOnlinePlayers().forEach((o) -> o.hidePlayer(this, p));
        } else {
            this.vanished.remove(p.getUniqueId());
            Bukkit.getOnlinePlayers().forEach((o) -> o.showPlayer(this, p));
        }
        this.refreshStatusEmbedIfTracking();
    }

    /** Value for a PlaceholderAPI placeholder (%zenith_<key>%), or null if the key is unknown. p can be null (server-wide keys). */
    String placeholder(org.bukkit.OfflinePlayer p, String key) {
        String k = key.toLowerCase(Locale.ROOT);
        String v = this.serverPlaceholder(k);
        if (v != null) {
            return v;
        }
        if (p == null) {
            return "";
        }
        v = this.statsMenu == null ? null : this.statsMenu.papi(p, k);
        if (v == null) {
            v = this.playerPlaceholder(p, k);
        }
        if (v == null && this.leaderboards != null) {
            v = this.leaderboards.papi(p, k);
        }
        return v;
    }

    /** True while the player still has the rules / guide menus to do (the welcome waits for it). */
    boolean firstJoinPending(Player p) {
        return this.firstJoin != null && this.firstJoin.pending(p);
    }

    /** The player accepted the last rules / guide step: show the welcome. */
    void firstJoinDone(Player p) {
        if (this.welcome != null) {
            this.welcome.afterSteps(p);
        }
    }

    /** Accepts the teleport request waiting for this player (used by /tpaccept and the tpaccept menu). */
    void acceptTpa(Player p) {
        UUID sid = this.tpaReq.remove(p.getUniqueId());
        if (sid != null && Bukkit.getPlayer(sid) != null) {
            Player r = Bukkit.getPlayer(sid);
            boolean tpaInstantOp = this.getConfig().getBoolean("tpa-instant-op", true);
            p.sendMessage(this.getMsg("tpa-accepted"));
            if (Boolean.TRUE.equals(this.tpType.remove(p.getUniqueId()))) {
                this.startTp(p, r.getLocation(), "teleport-safe", r.getName(), tpaInstantOp);
            } else {
                this.startTp(r, p.getLocation(), "teleport-safe", p.getName(), tpaInstantOp);
            }
        } else {
            p.sendMessage(this.getMsg("tpa-no-request"));
        }
    }

    /** Denies the teleport request waiting for this player. */
    void denyTpa(Player p) {
        this.tpaReq.remove(p.getUniqueId());
        this.tpType.remove(p.getUniqueId());
        p.sendMessage(this.getMsg("tpa-denied"));
    }

    /** Who sent the request waiting for this player, or null. */
    UUID tpaPendingFrom(UUID target) {
        return this.tpaReq.get(target);
    }

    /** Called by StatsMenu after every death: killstreak popup, milestones and "streak ended". killer is null when no player did it. */
    void killstreakHook(Player killer, Player victim, int streak, int victimStreak) {
        this.killstreak.onKill(killer, victim, streak, victimStreak);
    }

    /** Hooked by the PlaceholderAPI expansion: starts the leaderboard snapshot. */
    void startPapiBoards() {
        if (this.leaderboards != null) {
            this.leaderboards.startPapi();
        }
    }

    private String serverPlaceholder(String k) {
        switch (k) {
            case "online": {
                int n = 0;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!this.vanished.contains(o.getUniqueId())) {
                        n++;
                    }
                }
                return String.valueOf(n);
            }
            case "staff_online": {
                int n = 0;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (this.isStaffMember(o) && !this.vanished.contains(o.getUniqueId())) {
                        n++;
                    }
                }
                return String.valueOf(n);
            }
            case "max_players":
                return String.valueOf(Bukkit.getMaxPlayers());
            case "tps": {
                double[] t = this.serverTps();
                return t == null ? "20.0" : String.format(Locale.US, "%.1f", Math.min(20.0, t[0]));
            }
            default:
                return null;
        }
    }

    private String playerPlaceholder(org.bukkit.OfflinePlayer p, String k) {
        UUID id = p.getUniqueId();
        switch (k) {
            case "nick": {
                ConfigurationSection u = this.userSection(id);
                String n = u == null ? null : u.getString("nick");
                if (n != null && !n.isEmpty()) {
                    return this.color(n);
                }
                return this.nameFor(id, p.getName() == null ? "?" : p.getName());
            }
            case "first_join": {
                ConfigurationSection u = this.userSection(id);
                return u == null ? "unknown" : u.getString("first-join", "unknown");
            }
            case "last_seen": {
                ConfigurationSection u = this.userSection(id);
                return u == null ? "unknown" : u.getString("last-seen", "unknown");
            }
            case "vanished":
                return String.valueOf(this.vanished.contains(id));
            case "modmode": {
                Player on = p.getPlayer();
                return String.valueOf(on != null && this.modMode != null && this.modMode.isActive(on));
            }
            case "staff": {
                Player on = p.getPlayer();
                return String.valueOf(on != null && this.isStaffMember(on));
            }
            case "frozen":
                return String.valueOf(this.isFrozen(id));
            case "muted":
                return String.valueOf(this.isMutedUuid(id));
            case "bans":
                return String.valueOf(this.getStats(id)[0]);
            case "mutes":
                return String.valueOf(this.getStats(id)[1]);
            case "kicks":
                return String.valueOf(this.getStats(id)[2]);
            case "warns":
                return String.valueOf(this.getStats(id)[3]);
            case "combat":
                return String.valueOf(this.combat != null && this.combat.inCombat(id));
            case "combat_time":
                return String.valueOf(this.combat == null ? 0 : (this.combat.combatLeftMs(id) + 999L) / 1000L);
            default:
                return null;
        }
    }

    /** Best killstreaks of everyone, for /leaderboard. */
    Map<UUID, Long> streaks() {
        return this.statsMenu == null ? new HashMap<>() : this.statsMenu.bestStreaks();
    }

    /** The fake "left the game" / "joined the game" line, used by /modmode (respects the staff member's Vanish Messages setting). */
    void vanishBroadcast(Player p, boolean joined) {
        if (this.getSetting(p.getUniqueId(), "vanish-messages", true)) {
            Bukkit.broadcastMessage(joined ? this.fakeJoinMessage(p) : this.fakeLeaveMessage(p));
        }
    }

    /** State of one /zenithsettings toggle for the menu (null = no such setting). */
    Boolean settingState(Player p, String key) {
        for (Setting st : this.allSettings(p)) {
            if (st.key.equalsIgnoreCase(key)) {
                return st.get.test(p);
            }
        }
        return null;
    }

    void flipSetting(Player p, String key) {
        this.toggleSettingByKey(p, key);
    }

    boolean vanished(UUID id) {
        return this.vanished.contains(id);
    }

    boolean staffAlerts(Player p) {
        return this.wantsAlert(p);
    }

    boolean isStaff(Player p) {
        return this.isStaffMember(p);
    }

    String historyLine(UUID id) {
        return this.historySummary(id);
    }

    void addNote(OfflinePlayer t, String text, String staff) {
        this.recordPunishment(t, this.nameFor(t.getUniqueId(), "?"), "NOTE", "-", staff, text, null);
    }

    void runPunishment(String cmd, String[] args, String staffLabel) {
        this.handlePunishment(Bukkit.getConsoleSender(), cmd, args, staffLabel);
    }

    void runStaffCommand(String cmd, String[] args) {
        this.handleStaffCommand(Bukkit.getConsoleSender(), cmd, args);
    }

    void onAppealHandled(Player staff) {
        this.staffActivity.onAppeal(staff);
    }

    // Sends a chat line to every online staff member who has staff alerts on.
    void alertStaff(String legacyText) {
        String msg = this.translate(legacyText);
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission(PERM_STAFF_ALERTS) && this.wantsAlert(st)) {
                st.sendMessage(msg);
            }
        }
    }

    // {uuid, time, action, duration, staff, reason} of the ban with this ID, or null.
    String[] banInfo(String banId) {
        String uuid = this.findUuidByBanId(banId);
        if (uuid == null) {
            return null;
        }
        for (String line : this.data.getStringList("punishlog." + uuid)) {
            String[] p = this.parseEntry(line);
            if (p[5].equalsIgnoreCase(banId)) {
                return new String[]{uuid, p[0], p[1], p[2], p[3], p[4]};
            }
        }
        return null;
    }

    boolean ipBannedWithId(String banId) {
        ConfigurationSection ib = this.data.getConfigurationSection("ipbans");
        if (ib == null) {
            return false;
        }
        for (String k : ib.getKeys(false)) {
            String[] p = String.valueOf(ib.getString(k)).split("\\|", 5);
            if (p.length > 4 && p[4].equalsIgnoreCase(banId)) {
                return true;
            }
        }
        return false;
    }

    // Posts a simple embed to the punishment webhook (discord-webhook.url) if that event is turned on.
    void discordEmbed(String eventKey, String title, int color, String[][] fields) {
        this.discord.embed(eventKey, title, color, fields);
    }

    // Routes the commands that live in their own classes.
    private void handleModule(CommandSender s, String n, String[] args) {
        switch (n) {
            case "livelink":
                this.liveLink.handle(s, args);
                return;
            case "quests":
                this.quests.handle(s, args);
                return;
            case "rewards":
                this.playtimeRewards.handle(s, args);
                return;
            case "chatlog":
                this.chatLogger.handle(s, args);
                return;
            case "banevasion":
                this.banEvasion.handle(s, args);
                return;
            case "staffactivity":
                this.staffActivity.handle(s, args);
                return;
            case "ss":
                this.screenShare.handle(s, args);
                return;
            case "punishlimits":
                this.punishLimits.handle(s, args);
                return;
            default:
                return;
        }
    }

    private List<String> moduleTab(CommandSender s, String n, String[] args) {
        List<String> out = new ArrayList<>();
        switch (n) {
            case "chatlog":
                if (args.length == 1) {
                    out.addAll(this.suggestableNames(s));
                }
                break;
            case "quests":
            case "rewards":
                if (args.length == 1 && s.hasPermission("unstableffa." + n + ".admin")) {
                    out.add("reset");
                } else if (args.length == 2) {
                    out.addAll(this.visibleOnlineNames(s));
                }
                break;
            case "banevasion":
                if (args.length == 1) {
                    out.addAll(Arrays.asList("allow", "unallow", "list"));
                } else if (args.length == 2) {
                    out.addAll(this.suggestableNames(s));
                }
                break;
            case "staffactivity":
                if (args.length == 1) {
                    out.addAll(Arrays.asList("7", "30"));
                    out.addAll(this.staffActivity.names());
                }
                break;
            case "punishlimits":
                if (s.hasPermission("unstableffa.punishlimits.admin")) {
                    out.addAll(this.punishLimits.tab(args));
                }
                break;
            case "ss":
                if (args.length == 1) {
                    out.addAll(Arrays.asList("next", "back", "repeat", "end", "list", "setlocation"));
                    out.addAll(this.visibleOnlineNames(s));
                } else if (args.length == 2 && args[0].matches("(?i)next|back|repeat|end")) {
                    out.addAll(this.screenShare.targets());
                } else if (args.length == 3 && args[0].equalsIgnoreCase("end")) {
                    out.addAll(Arrays.asList("clean", "admit", "ban"));
                }
                break;
            default:
                break;
        }
        return out;
    }

    // =====================================================================================
    // v4: /report GUI, /reports GUI, /mutechat, /staff, /kit, /clearlag, /block, /ignore, /near
    // =====================================================================================

    // ---------- shared: "type it in chat" input ----------

    private final Map<UUID, java.util.function.Consumer<String>> chatInput = new ConcurrentHashMap<>();

    // Closes the menu and uses the player's next chat message as input (60 second timeout).
    private void askChat(Player p, String prompt, java.util.function.Consumer<String> then) {
        final UUID id = p.getUniqueId();
        final java.util.function.Consumer<String> cb = then;
        this.chatInput.put(id, cb);
        this.later(() -> {
            p.closeInventory();
            p.sendMessage(prompt);
            p.sendMessage("§8Type §fcancel §8to stop.");
        });
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (this.chatInput.remove(id, cb)) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    pl.sendMessage("§7You took too long - cancelled.");
                }
            }
        }, 20L * 60L);
    }

    // Routes every v4 command.
    private void handleV4(CommandSender s, String n, String[] args) {
        switch (n) {
            case "mutechat":
                this.handleMuteChat(s, args);
                return;
            case "clearlag":
                this.handleClearLag(s, args);
                return;
            case "staff":
                this.handleStaffList(s);
                return;
            case "kit":
                this.handleKit(s, args);
                return;
            case "report":
                this.handleReport(s, args);
                return;
            case "reports":
                this.handleReports(s, args);
                return;
            case "block":
            case "unblock":
            case "ignore":
            case "unignore":
                this.handleRelation(s, n, args);
                return;
            case "near":
                this.handleNear(s, args);
                return;
            default:
                return;
        }
    }

    // =====================================================================================
    // /mutechat - lock public chat for everyone except staff
    // =====================================================================================

    private boolean chatLocked = false;

    private void handleMuteChat(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.mutechat")) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        this.chatLocked = !this.chatLocked;
        String reason = args.length > 0 ? String.join(" ", args) : "";
        Map<String, String> ph = new HashMap<>();
        ph.put("staff", s.getName());
        ph.put("reason", reason);
        if (this.chatLocked) {
            Bukkit.broadcastMessage(this.formatMsgMap("mutechat-on",
                    "&8» <gradient:#FF7518:#FFFFFF>&lChat has b</gradient><gradient:#FFFFFF:#FF7518>&leen muted</gradient> &7by &f{staff}&7.", ph)
                    + (reason.isEmpty() ? "" : this.translate(" &8(&7" + reason + "&8)")));
        } else {
            Bukkit.broadcastMessage(this.formatMsgMap("mutechat-off", "&8» <gradient:#FF7518:#FFFFFF>&lChat has be</gradient><gradient:#FFFFFF:#FF7518>&len unmuted</gradient> &7by &f{staff}&7.", ph));
        }
    }

    // =====================================================================================
    // /staff - who is online from the staff team
    // =====================================================================================

    private boolean isStaffMember(Player p) {
        return p.hasPermission(this.getConfig().getString("discord-webhook.status-embed.staff-permission", "unstableffa.staffcount"));
    }

    private void handleStaffList(CommandSender s) {
        boolean seeHidden = s.hasPermission("unstableffa.vanish");
        List<Player> staff = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (this.isStaffMember(pl) && (seeHidden || !this.vanished.contains(pl.getUniqueId()))) {
                staff.add(pl);
            }
        }
        staff.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        if (!(s instanceof Player)) {
            s.sendMessage("§b§lSTAFF ONLINE §7(" + staff.size() + ")");
            for (Player pl : staff) {
                s.sendMessage("§7- §f" + pl.getName() + (this.vanished.contains(pl.getUniqueId()) ? " §8[vanished]" : ""));
            }
            return;
        }
        Player viewer = (Player) s;
        int rows = Math.max(3, Math.min(6, 2 + (staff.size() + 6) / 7));
        Gui g = this.newGui("staff", rows * 9, "§8Online Staff (" + staff.size() + ")", null);
        this.renderStaffList(g, viewer, staff);
        viewer.openInventory(g.inv);
    }

    private void renderStaffListRaw(Gui g, Player viewer, List<Player> staff) {
        Inventory inv = g.inv;
        int size = inv.getSize();
        Arrays.fill(g.slotData, null);
        ItemStack frame = this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < size; i++) {
            inv.setItem(i, frame);
        }
        boolean viewerIsStaff = this.canPunishAny(viewer);
        if (staff.isEmpty()) {
            inv.setItem(13, this.createGuiItem(Material.BARRIER, "&#D9381ENo staff online", "&7Use &f/report &7and staff will see it", "&7as soon as they join."));
            return;
        }
        int slot = 10;
        for (Player st : staff) {
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= size - 9) {
                break;
            }
            List<String> lore = new ArrayList<>();
            Long start = this.sessionStart.get(st.getUniqueId());
            lore.add("&7Online for &f" + (start == null ? "a while" : this.formatUptime(System.currentTimeMillis() - start)));
            if (viewerIsStaff) {
                lore.add("&7World: &f" + st.getWorld().getName());
                if (this.vanished.contains(st.getUniqueId())) {
                    lore.add("&#B15CFFVanished");
                }
            }
            lore.add(" ");
            lore.add("&#FF8330Click &7» send a message");
            inv.setItem(slot, this.skull(st, "&#B15CFF" + st.getName(), lore));
            g.slotData[slot] = st.getUniqueId();
            slot++;
        }
    }

    private void clickStaffList(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= g.inv.getSize() || !(g.slotData[slot] instanceof UUID)) {
            return;
        }
        Player st = Bukkit.getPlayer((UUID) g.slotData[slot]);
        if (st == null) {
            return;
        }
        final TextComponent tc = new TextComponent(TextComponent.fromLegacyText("§7Click here to message §b" + st.getName() + " §8[/msg]"));
        tc.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + st.getName() + " "));
        this.later(() -> {
            p.closeInventory();
            p.spigot().sendMessage(tc);
        });
    }

    // =====================================================================================
    // /near - staff only: players around you
    // =====================================================================================

    private void handleNear(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can use /near.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.near")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        int max = Math.max(10, this.getConfig().getInt("near.max-radius", 1000));
        int radius = this.getConfig().getInt("near.default-radius", 200);
        if (args.length > 0) {
            try {
                radius = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                p.sendMessage("§cUsage: /near [radius]");
                return;
            }
        }
        radius = Math.max(1, Math.min(radius, max));
        boolean seeVanished = p.hasPermission("unstableffa.vanish");
        Location me = p.getLocation();
        List<Object[]> found = new ArrayList<>(); // {Player, distance}
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || (!seeVanished && this.vanished.contains(o.getUniqueId()))) {
                continue;
            }
            double d = o.getLocation().distance(me);
            if (d <= radius) {
                found.add(new Object[]{o, d});
            }
        }
        found.sort((a, b) -> Double.compare((Double) a[1], (Double) b[1]));
        if (found.isEmpty()) {
            p.sendMessage("§7Nobody within §f" + radius + " §7blocks.");
            return;
        }
        boolean canTp = p.hasPermission("unstableffa.forcetp");
        p.sendMessage("§b§lNEARBY §7(" + found.size() + " within " + radius + " blocks)");
        int shown = 0;
        for (Object[] row : found) {
            if (shown++ >= 15) {
                p.sendMessage("§8...and " + (found.size() - 15) + " more");
                break;
            }
            Player o = (Player) row[0];
            Location l = o.getLocation();
            double dx = l.getX() - me.getX();
            double dz = l.getZ() - me.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            String line = "§7- §f" + o.getName() + " §8» §f" + Math.round((Double) row[1]) + "m §7" + this.compass(yaw)
                    + (this.vanished.contains(o.getUniqueId()) ? " §b[V]" : "") + (canTp ? " §8[click to tp]" : "");
            TextComponent tc = new TextComponent(TextComponent.fromLegacyText(line));
            if (canTp) {
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/forcetp " + o.getName()));
            }
            p.spigot().sendMessage(tc);
        }
    }

    // =====================================================================================
    // /clearlag - removes dropped items, xp orbs, arrows and (optionally) hostile mobs
    // =====================================================================================

    private BukkitTask clearLagTask;
    private int clearLagCountdown = -1;

    // Starts (or restarts after /unstableffa reload) the automatic clear timer.
    private void startClearLag() {
        if (this.clearLagTask != null) {
            this.clearLagTask.cancel();
            this.clearLagTask = null;
        }
        this.clearLagCountdown = -1;
        if (!this.getConfig().getBoolean("clearlag.auto.enabled", true)) {
            return;
        }
        final int interval = Math.max(1, this.getConfig().getInt("clearlag.auto.interval-minutes", 15)) * 60;
        this.clearLagCountdown = interval;
        this.clearLagTask = (new BukkitRunnable() {
            public void run() {
                zenithEssentials.this.tickClearLag(interval);
            }
        }).runTaskTimer(this, 20L, 20L);
    }

    private void tickClearLag(int interval) {
        this.clearLagCountdown--;
        List<Integer> warns = this.getConfig().getIntegerList("clearlag.auto.warnings");
        if (warns.isEmpty()) {
            warns = Arrays.asList(60, 30, 10);
        }
        if (warns.contains(this.clearLagCountdown)) {
            Bukkit.broadcastMessage(this.formatMsgMap("clearlag-warning",
                    "&8» <gradient:#FF7518:#FFFFFF>&lClea</gradient><gradient:#FFFFFF:#FF7518>&lrLag</gradient> &7Ground items will be removed in &f{seconds}s&7.",
                    java.util.Collections.singletonMap("seconds", String.valueOf(this.clearLagCountdown))));
        }
        if (this.clearLagCountdown <= 0) {
            this.clearLagCountdown = interval;
            int[] c = this.clearEntities(
                    this.getConfig().getBoolean("clearlag.remove.items", true),
                    this.getConfig().getBoolean("clearlag.remove.xp-orbs", true),
                    this.getConfig().getBoolean("clearlag.remove.arrows", true),
                    this.getConfig().getBoolean("clearlag.remove.hostile-mobs", false));
            this.broadcastClearLag(c);
        }
    }

    // Returns {items, xp orbs, arrows, mobs} removed.
    private int[] clearEntities(boolean items, boolean xp, boolean arrows, boolean mobs) {
        int[] c = new int[4];
        List<String> skipWorlds = this.getConfig().getStringList("clearlag.ignored-worlds");
        for (World w : Bukkit.getWorlds()) {
            if (skipWorlds.contains(w.getName())) {
                continue;
            }
            for (Entity en : w.getEntities()) {
                if (en instanceof Player || en.getCustomName() != null || !en.getPassengers().isEmpty()) {
                    continue;
                }
                if (items && en instanceof org.bukkit.entity.Item) {
                    en.remove();
                    c[0]++;
                } else if (xp && en instanceof org.bukkit.entity.ExperienceOrb) {
                    en.remove();
                    c[1]++;
                } else if (arrows && en instanceof org.bukkit.entity.AbstractArrow && ((org.bukkit.entity.AbstractArrow) en).isInBlock()) {
                    en.remove();
                    c[2]++;
                } else if (mobs && en instanceof Monster) {
                    Monster m = (Monster) en;
                    if (m.getRemoveWhenFarAway() && !m.isLeashed() && !m.isInsideVehicle()) {
                        en.remove();
                        c[3]++;
                    }
                }
            }
        }
        return c;
    }

    private void broadcastClearLag(int[] c) {
        Map<String, String> ph = new HashMap<>();
        ph.put("items", String.valueOf(c[0]));
        ph.put("xp", String.valueOf(c[1]));
        ph.put("arrows", String.valueOf(c[2]));
        ph.put("mobs", String.valueOf(c[3]));
        ph.put("total", String.valueOf(c[0] + c[1] + c[2] + c[3]));
        Bukkit.broadcastMessage(this.formatMsgMap("clearlag-done",
                "&8» <gradient:#FF7518:#FFFFFF>&lClea</gradient><gradient:#FFFFFF:#FF7518>&lrLag</gradient> &7Removed &f{total} &7entities &8(&f{items} &7items, &f{mobs} &7mobs&8)", ph));
    }

    private void handleClearLag(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.clearlag")) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        String mode = args.length > 0 ? args[0].toLowerCase() : "default";
        switch (mode) {
            case "status": {
                s.sendMessage("§e§lCLEARLAG STATUS");
                for (World w : Bukkit.getWorlds()) {
                    int it = 0;
                    int mob = 0;
                    for (Entity en : w.getEntities()) {
                        if (en instanceof org.bukkit.entity.Item) {
                            it++;
                        } else if (en instanceof Monster) {
                            mob++;
                        }
                    }
                    s.sendMessage("§f" + w.getName() + " §8» §7entities §f" + w.getEntities().size() + "§7, items §f" + it
                            + "§7, hostile mobs §f" + mob + "§7, chunks §f" + w.getLoadedChunks().length);
                }
                s.sendMessage(this.clearLagCountdown > 0
                        ? "§7Next automatic clear in §f" + this.formatUptime(this.clearLagCountdown * 1000L)
                        + (this.clearLagCountdown < 60 ? " §8(" + this.clearLagCountdown + "s)" : "")
                        : "§7Automatic clearing is §coff§7.");
                return;
            }
            case "items":
                this.broadcastClearLag(this.clearEntities(true, true, true, false));
                return;
            case "mobs":
                this.broadcastClearLag(this.clearEntities(false, false, false, true));
                return;
            case "all":
                this.broadcastClearLag(this.clearEntities(true, true, true, true));
                return;
            case "default":
                this.broadcastClearLag(this.clearEntities(
                        this.getConfig().getBoolean("clearlag.remove.items", true),
                        this.getConfig().getBoolean("clearlag.remove.xp-orbs", true),
                        this.getConfig().getBoolean("clearlag.remove.arrows", true),
                        this.getConfig().getBoolean("clearlag.remove.hostile-mobs", false)));
                return;
            default:
                s.sendMessage("§cUsage: /clearlag [items|mobs|all|status]");
        }
    }

    // =====================================================================================
    // /block and /ignore
    //   /ignore  -> you stop seeing their chat and private messages.
    //   /block   -> everything about them is gone for BOTH of you: chat, private messages,
    //               teleport requests, join/leave/death messages and mention sounds.
    //               You still see each other in the world.
    //   Staff (unstableffa.ignore.exempt) can't be ignored or blocked.
    // =====================================================================================

    private final Map<UUID, Set<UUID>> blockMap = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoreMap = new ConcurrentHashMap<>();

    private void loadRelations() {
        this.blockMap.clear();
        this.ignoreMap.clear();
        this.loadRelationSection("blocks", this.blockMap);
        this.loadRelationSection("ignores", this.ignoreMap);
    }

    private void loadRelationSection(String sec, Map<UUID, Set<UUID>> into) {
        ConfigurationSection cs = this.data.getConfigurationSection(sec);
        if (cs == null) {
            return;
        }
        for (String k : cs.getKeys(false)) {
            try {
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (String v : cs.getStringList(k)) {
                    set.add(UUID.fromString(v));
                }
                if (!set.isEmpty()) {
                    into.put(UUID.fromString(k), set);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveRelation(String sec, Map<UUID, Set<UUID>> map, UUID owner) {
        Set<UUID> set = map.get(owner);
        if (set == null || set.isEmpty()) {
            map.remove(owner);
            this.data.set(sec + "." + owner, (Object) null);
        } else {
            List<String> out = new ArrayList<>();
            for (UUID u : set) {
                out.add(u.toString());
            }
            this.data.set(sec + "." + owner, out);
        }
        this.saveD();
    }

    private boolean hasBlocked(UUID owner, UUID other) {
        Set<UUID> s = this.blockMap.get(owner);
        return s != null && s.contains(other);
    }

    private boolean hasIgnored(UUID owner, UUID other) {
        Set<UUID> s = this.ignoreMap.get(owner);
        return s != null && s.contains(other);
    }

    private boolean blockedEitherWay(UUID a, UUID b) {
        return this.hasBlocked(a, b) || this.hasBlocked(b, a);
    }

    // Should "viewer" NOT see chat / messages from "sender"? (thread-safe, used in async chat)
    private boolean hidesChatFrom(UUID viewer, UUID sender) {
        return this.hasIgnored(viewer, sender) || this.blockedEitherWay(viewer, sender);
    }

    private boolean anyoneBlocks(UUID subject) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (this.blockedEitherWay(o.getUniqueId(), subject)) {
                return true;
            }
        }
        return false;
    }

    // Sends a join/leave/death message to everyone except people with a block between them.
    private void announceFiltered(String msg, Player subject) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(subject) || !this.blockedEitherWay(o.getUniqueId(), subject.getUniqueId())) {
                o.sendMessage(msg);
            }
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathBlockFilter(PlayerDeathEvent e) {
        String msg = e.getDeathMessage();
        if (msg != null && this.anyoneBlocks(e.getEntity().getUniqueId())) {
            e.setDeathMessage(null);
            this.announceFiltered(msg, e.getEntity());
        }
    }

    private List<String> relationNames(Map<UUID, Set<UUID>> map, UUID owner) {
        List<String> out = new ArrayList<>();
        Set<UUID> s = map.get(owner);
        if (s != null) {
            for (UUID u : s) {
                out.add(this.nameForUuid(u.toString()));
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void handleRelation(CommandSender s, String n, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can use /" + n + ".");
            return;
        }
        Player p = (Player) s;
        boolean isBlock = n.contains("block");
        if (!p.hasPermission(isBlock ? "unstableffa.block" : "unstableffa.ignore")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        Map<UUID, Set<UUID>> map = isBlock ? this.blockMap : this.ignoreMap;
        String sec = isBlock ? "blocks" : "ignores";
        String word = isBlock ? "blocked" : "ignored";

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> names = this.relationNames(map, p.getUniqueId());
            p.sendMessage(names.isEmpty()
                    ? "§7You haven't " + word + " anyone. §8(/" + (isBlock ? "block" : "ignore") + " <player>)"
                    : "§b§l" + word.toUpperCase() + " §7(" + names.size() + "): §f" + String.join("§7, §f", names));
            return;
        }

        OfflinePlayer t = this.resolveOffline(args[0]);
        String tn = this.displayName(t, args[0]);
        if (!this.uuidIndex.containsKey(t.getUniqueId()) && !t.hasPlayedBefore() && !t.isOnline()) {
            p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
            return;
        }
        if (t.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage("§cYou can't " + (isBlock ? "block" : "ignore") + " yourself.");
            return;
        }
        Set<UUID> set = map.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean removing = n.startsWith("un") || (!isBlock && set.contains(t.getUniqueId()));

        if (removing) {
            if (!set.remove(t.getUniqueId())) {
                p.sendMessage("§cYou haven't " + word + " §f" + tn + "§c.");
                return;
            }
            this.saveRelation(sec, map, p.getUniqueId());
            p.sendMessage("§aYou no longer " + (isBlock ? "block" : "ignore") + " §f" + tn + "§a.");
            return;
        }

        if (set.contains(t.getUniqueId())) {
            p.sendMessage("§7You already blocked §f" + tn + "§7. Use §f/unblock " + tn + " §7to undo it.");
            return;
        }
        Player online = t.getPlayer();
        if (online != null && online.hasPermission("unstableffa.ignore.exempt")) {
            p.sendMessage("§cYou can't " + (isBlock ? "block" : "ignore") + " staff members.");
            return;
        }
        set.add(t.getUniqueId());
        this.saveRelation(sec, map, p.getUniqueId());
        if (isBlock) {
            // Drop any open teleport requests between the two of them.
            UUID pending = this.tpaReq.get(p.getUniqueId());
            if (t.getUniqueId().equals(pending)) {
                this.tpaReq.remove(p.getUniqueId());
            }
            pending = this.tpaReq.get(t.getUniqueId());
            if (p.getUniqueId().equals(pending)) {
                this.tpaReq.remove(t.getUniqueId());
            }
            p.sendMessage("§aYou blocked §f" + tn + "§a. §7You won't see their chat, messages, teleport requests or join/leave messages anymore (and they won't see yours). §8/unblock " + tn);
        } else {
            p.sendMessage("§aYou are now ignoring §f" + tn + "§a. §7You won't see their chat or private messages. §8/ignore " + tn + " again to undo");
        }
    }

    // =====================================================================================
    // /report (GUI) and /reports (staff GUI)
    // data.yml -> reportdata.<id>: reporter, reporter-uuid, target, target-uuid, reason,
    //                              time, status (OPEN/CLAIMED/RESOLVED), handler, world/x/y/z
    // =====================================================================================

    private final Map<UUID, Long> reportCooldown = new HashMap<>();
    private static final String[] REPORT_FILTERS = {"Open + claimed", "Open", "Claimed", "Resolved", "Everything"};

    // config.yml reports.reasons: ["Hacking:DIAMOND_SWORD", "Spam:PAPER", ...]
    private List<String[]> reportReasons() {
        List<String> raw = this.getConfig().getStringList("reports.reasons");
        if (raw.isEmpty()) {
            raw = Arrays.asList("Hacking:DIAMOND_SWORD", "Spamming:PAPER", "Toxicity / Harassment:SKELETON_SKULL",
                    "Griefing:TNT", "Bad name or skin:NAME_TAG", "Bug abuse:SPIDER_EYE", "Scamming:GOLD_INGOT");
        }
        List<String[]> out = new ArrayList<>();
        for (String r : raw) {
            int i = r.lastIndexOf(':');
            out.add(i > 0 ? new String[]{r.substring(0, i), r.substring(i + 1)} : new String[]{r, "PAPER"});
        }
        return out;
    }

    private Material matOr(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name.trim());
        return m == null || m.isAir() ? def : m;
    }

    private void handleReport(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage("§cOnly players can report.");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.report")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length == 0) {
            this.openReportPick(p);
            return;
        }
        OfflinePlayer t = this.resolveOffline(args[0]);
        Player online = t.getPlayer();
        boolean known = this.uuidIndex.containsKey(t.getUniqueId()) || t.hasPlayedBefore();
        if ((online == null && !known) || (online != null && this.vanished.contains(online.getUniqueId()) && !p.hasPermission("unstableffa.vanish"))) {
            p.sendMessage(this.getMsg("player-not-found").replace("{player}", args[0]));
            return;
        }
        String tn = this.displayName(t, args[0]);
        if (args.length == 1) {
            this.openReportReason(p, t.getUniqueId(), tn);
            return;
        }
        this.submitReport(p, t.getUniqueId(), tn, String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("_", " "));
    }

    private void openReportPick(Player p) {
        Gui g = this.newGui("report-pick", 54, "§8Report » Choose a player", null);
        this.renderReportPick(g, p);
        p.openInventory(g.inv);
    }

    private void renderReportPickRaw(Gui g, Player p) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        List<Player> list = new ArrayList<>();
        boolean seeVanished = p.hasPermission("unstableffa.vanish");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p) && (seeVanished || !this.vanished.contains(o.getUniqueId()))) {
                list.add(o);
            }
        }
        list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int total = list.size();
        int pages = Math.max(1, (total + 35) / 36);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        ItemStack frame = this.pane();
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(4, this.createGuiItem(Material.WRITABLE_BOOK, "<gradient:#FF7518:#FFFFFF>&lReport a</gradient><gradient:#FFFFFF:#FF7518>&l player</gradient>",
                "&7Click the player you want to report.", "&7Abusing reports can get you punished."));
        int start = g.page * 36;
        for (int i = 0; i < 36 && start + i < total; i++) {
            Player o = list.get(start + i);
            inv.setItem(9 + i, this.skull(o, "&f" + o.getName(), Arrays.asList("&#FF8330Click &7» report " + o.getName())));
            g.slotData[9 + i] = o.getUniqueId();
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.BARRIER, "&#D9381ENobody else is online",
                    "&7You can still use &f/report <name> <reason>"));
        }
        this.renderPager(g, pages, total);
    }

    private void clickReportPick(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (this.handlePager(g, slot)) {
            this.renderReportPick(g, p);
            return;
        }
        if (slot >= 9 && slot < 45 && g.slotData[slot] instanceof UUID) {
            final UUID id = (UUID) g.slotData[slot];
            final String name = this.nameForUuid(id.toString());
            this.later(() -> this.openReportReason(p, id, name));
        }
    }

    private void openReportReason(Player p, UUID target, String targetName) {
        if (target.equals(p.getUniqueId())) {
            p.sendMessage("§cYou can't report yourself.");
            return;
        }
        List<String[]> reasons = this.reportReasons();
        int rows = Math.min(6, 3 + (reasons.size() + 1) / 7);
        Gui g = this.newGui("report-reason", rows * 9, "§8Report » " + targetName, null);
        g.target = target;
        g.targetName = targetName;
        Inventory inv = g.inv;
        ItemStack frame = this.pane();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(4, this.skull(Bukkit.getOfflinePlayer(target), "<gradient:#FF7518:#FFFFFF>&lRepor</gradient><gradient:#FFFFFF:#FF7518>&lting</gradient> &f" + targetName,
                Arrays.asList("&7Pick the reason that fits best.")));
        int slot = 10;
        for (String[] r : reasons) {
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= inv.getSize() - 9) {
                break;
            }
            inv.setItem(slot, this.createGuiItem(this.matOr(r[1], Material.PAPER), "&#FF8330" + r[0], "&#FF8330Click &7» send report"));
            g.slotData[slot] = "reason:" + r[0];
            slot++;
        }
        int bottom = inv.getSize() - 9;
        inv.setItem(bottom + 3, this.createGuiItem(Material.OAK_SIGN, "&fOther reason...", "&7Type your own reason in chat."));
        g.slotData[bottom + 3] = "other";
        inv.setItem(bottom + 5, this.createGuiItem(Material.BARRIER, "&#D9381ECancel"));
        g.slotData[bottom + 5] = "cancel";
        this.tidy(inv, false);
        p.openInventory(inv);
    }

    private void clickReportReason(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= g.inv.getSize() || !(g.slotData[slot] instanceof String)) {
            return;
        }
        String key = (String) g.slotData[slot];
        final UUID target = g.target;
        final String tn = g.targetName;
        if (key.equals("cancel")) {
            this.later(p::closeInventory);
        } else if (key.equals("other")) {
            this.askChat(p, "§eType the reason for reporting §f" + tn + "§e:", text -> this.submitReport(p, target, tn, text));
        } else if (key.startsWith("reason:")) {
            final String reason = key.substring(7);
            this.later(() -> {
                p.closeInventory();
                this.submitReport(p, target, tn, reason);
            });
        }
    }

    private void submitReport(Player p, UUID target, String targetName, String reasonRaw) {
        if (target.equals(p.getUniqueId())) {
            p.sendMessage("§cYou can't report yourself.");
            return;
        }
        String reason = this.clean(reasonRaw, "No reason");
        if (reason.length() > 120) {
            reason = reason.substring(0, 120);
        }
        long now = System.currentTimeMillis();
        int cd = this.getConfig().getInt("reports.cooldown-seconds", 60);
        Long last = this.reportCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd * 1000L && !p.hasPermission("unstableffa.report.bypasscooldown")) {
            p.sendMessage("§cPlease wait §f" + ((cd * 1000L - (now - last)) / 1000L + 1) + "s §cbefore sending another report.");
            return;
        }
        this.reportCooldown.put(p.getUniqueId(), now);

        String id = this.newBanId();
        String base = "reportdata." + id;
        this.data.set(base + ".reporter", p.getName());
        this.data.set(base + ".reporter-uuid", p.getUniqueId().toString());
        this.data.set(base + ".target", targetName);
        this.data.set(base + ".target-uuid", target.toString());
        this.data.set(base + ".reason", reason);
        this.data.set(base + ".time", now);
        this.data.set(base + ".status", "OPEN");
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            Location l = online.getLocation();
            this.data.set(base + ".world", l.getWorld() == null ? null : l.getWorld().getName());
            this.data.set(base + ".x", l.getX());
            this.data.set(base + ".y", l.getY());
            this.data.set(base + ".z", l.getZ());
        }
        this.saveD();

        p.sendMessage(this.formatMsgMap("report-sent", "&#FF9147Thanks! Your report on &f{target} &#FF9147has been sent to the staff team.",
                java.util.Collections.singletonMap("target", targetName)));

        TextComponent alert = new TextComponent(TextComponent.fromLegacyText("§8[§c§lREPORT§8] §f" + p.getName() + " §7reported §c"
                + targetName + "§7: §f" + reason + " §8[click to open /reports]"));
        alert.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/reports"));
        for (Player st : Bukkit.getOnlinePlayers()) {
            if (st.hasPermission("unstableffa.reports") && this.wantsAlert(st)) {
                st.spigot().sendMessage(alert);
                st.playSound(st.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.2F);
            }
        }
        this.sendReportWebhook(id, p.getName(), targetName, reason);
    }

    private void sendReportWebhook(String id, String reporter, String target, String reason) {
        int color = DiscordWebhooks.color(this.getConfig().getString("discord-webhook.colors.report", "E91E63"), 0xE91E63);
        this.discord.embed("report", "New Report #" + id, color, new String[][]{
                {"Reported", target}, {"By", reporter}, {"Reason", reason}});
    }

    private void handleReports(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.reports")) {
            s.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            if (!s.hasPermission("unstableffa.reports.clear")) {
                s.sendMessage(this.getMsg("no-permission"));
                return;
            }
            boolean all = args.length > 1 && args[1].equalsIgnoreCase("all");
            int removed = this.clearReports(all);
            s.sendMessage("§aRemoved §f" + removed + " §a" + (all ? "" : "resolved ") + "report(s).");
            return;
        }
        if (!(s instanceof Player)) {
            ConfigurationSection rs = this.data.getConfigurationSection("reportdata");
            if (rs == null || rs.getKeys(false).isEmpty()) {
                s.sendMessage("§cNo reports.");
                return;
            }
            s.sendMessage("§b§lREPORTS:");
            for (String k : rs.getKeys(false)) {
                s.sendMessage("§7#" + k + " §8[" + rs.getString(k + ".status") + "] §f" + rs.getString(k + ".reporter")
                        + " -> §c" + rs.getString(k + ".target") + "§7: " + rs.getString(k + ".reason"));
            }
            return;
        }
        Player p = (Player) s;
        Gui g = this.newGui("reports", 54, "§8Unstable » Reports", null);
        this.renderReports(g, p);
        p.openInventory(g.inv);
    }

    private int clearReports(boolean all) {
        ConfigurationSection rs = this.data.getConfigurationSection("reportdata");
        int removed = 0;
        if (rs != null) {
            for (String k : new ArrayList<>(rs.getKeys(false))) {
                if (all || "RESOLVED".equals(rs.getString(k + ".status"))) {
                    this.data.set("reportdata." + k, (Object) null);
                    removed++;
                }
            }
        }
        if (all && this.data.contains("reports")) {
            this.data.set("reports", (Object) null); // old text-only reports from before the GUI
        }
        this.saveD();
        return removed;
    }

    private boolean reportMatches(String status, int filter) {
        switch (filter) {
            case 0:
                return !"RESOLVED".equals(status);
            case 1:
                return "OPEN".equals(status);
            case 2:
                return "CLAIMED".equals(status);
            case 3:
                return "RESOLVED".equals(status);
            default:
                return true;
        }
    }

    private void renderReportsRaw(Gui g, Player p) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        ConfigurationSection rs = this.data.getConfigurationSection("reportdata");
        List<String> ids = new ArrayList<>();
        int open = 0;
        int claimed = 0;
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                String st = rs.getString(k + ".status", "OPEN");
                if ("OPEN".equals(st)) {
                    open++;
                } else if ("CLAIMED".equals(st)) {
                    claimed++;
                }
                if (this.reportMatches(st, g.filter)) {
                    ids.add(k);
                }
            }
        }
        final ConfigurationSection fr = rs;
        ids.sort((a, b) -> Long.compare(fr.getLong(b + ".time"), fr.getLong(a + ".time")));
        int total = ids.size();
        int pages = Math.max(1, (total + 35) / 36);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        ItemStack frame = this.pane();
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(1, this.optionItem(Material.HOPPER, "&#B15CFF&lShow: &f", REPORT_FILTERS, g.filter,
                "&#FF8330Click &7» next &8| &#FF8330Right-click &7» previous"));
        inv.setItem(4, this.createGuiItem(Material.BOOK, "<gradient:#FF7518:#FFFFFF>&lRepo</gradient><gradient:#FFFFFF:#FF7518>&lrts</gradient>", "&7Open: &#D9381E" + open, "&7Claimed: &#FF8330" + claimed, "&7Showing: &f" + total));
        if (p.hasPermission("unstableffa.reports.clear")) {
            inv.setItem(8, this.createGuiItem(Material.LAVA_BUCKET, "<gradient:#FF7518:#FFFFFF>&lClear r</gradient><gradient:#FFFFFF:#FF7518>&lesolved</gradient>", "&7Deletes every resolved report.", " ", "&#FF8330Shift-click to confirm"));
            g.slotData[8] = "clear";
        }

        boolean canTp = p.hasPermission("unstableffa.forcetp");
        boolean canHistory = p.hasPermission("unstableffa.history");
        int start = g.page * 36;
        for (int i = 0; i < 36 && start + i < total; i++) {
            String id = ids.get(start + i);
            String b = id + ".";
            String st = rs.getString(b + "status", "OPEN");
            String handler = rs.getString(b + "handler", "");
            String tn = rs.getString(b + "target", "?");
            boolean targetOnline = false;
            try {
                Player tp = Bukkit.getPlayer(UUID.fromString(rs.getString(b + "target-uuid", "")));
                targetOnline = tp != null && (!this.vanished.contains(tp.getUniqueId()) || p.hasPermission("unstableffa.vanish"));
            } catch (IllegalArgumentException ignored) {
            }
            List<String> lore = new ArrayList<>();
            lore.add("&7Reported: &f" + tn + (targetOnline ? " &#FF9147(online)" : " &8(offline)"));
            lore.add("&7By: &f" + rs.getString(b + "reporter", "?"));
            lore.add("&7Reason:");
            lore.addAll(this.wrap(rs.getString(b + "reason", ""), 32, "&f  "));
            String ts = String.valueOf(rs.getLong(b + "time"));
            lore.add("&7When: &f" + this.formatDate(ts) + " &8(" + this.agoMs(ts) + ")");
            switch (st) {
                case "CLAIMED":
                    lore.add("&7Status: &#FF8330Claimed by " + handler);
                    break;
                case "RESOLVED":
                    lore.add("&7Status: &#FF9147Resolved by " + handler);
                    break;
                default:
                    lore.add("&7Status: &#D9381EOpen");
                    break;
            }
            lore.add(" ");
            if (!"RESOLVED".equals(st)) {
                lore.add("CLAIMED".equals(st) && handler.equalsIgnoreCase(p.getName()) ? "&#FF8330Left-click &7» unclaim" : "&#FF8330Left-click &7» claim");
                lore.add("&#FF8330Shift-left-click &7» mark as resolved");
            } else {
                lore.add("&#FF8330Shift-left-click &7» reopen");
            }
            if (canTp) {
                lore.add("&#FF8330Right-click &7» teleport " + (targetOnline ? "to " + tn : "to where they were"));
            }
            if (canHistory) {
                lore.add("&#FF8330Shift-right-click &7» " + tn + "'s history");
            }
            Material mat = "OPEN".equals(st) ? Material.PAPER : ("CLAIMED".equals(st) ? Material.WRITABLE_BOOK : Material.LIME_DYE);
            ItemStack it = this.createGuiItem(mat, ("OPEN".equals(st) ? "&#D9381E" : ("CLAIMED".equals(st) ? "&#FF8330" : "&#FF9147")) + "#" + id + " &8» &f" + tn, lore.toArray(new String[0]));
            if ("OPEN".equals(st)) {
                this.glow(it);
            }
            inv.setItem(9 + i, it);
            g.slotData[9 + i] = id;
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.LIME_STAINED_GLASS_PANE, "&#FF9147Nothing here", "&7No reports match this filter."));
        }
        this.renderPager(g, pages, total);
    }

    private void clickReports(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot == 1) {
            g.filter = this.cycle(g.filter, REPORT_FILTERS.length, e.isRightClick());
            g.page = 0;
            this.renderReports(g, p);
            return;
        }
        if (slot == 8 && "clear".equals(g.slotData[8])) {
            if (e.isShiftClick()) {
                p.sendMessage("§aRemoved §f" + this.clearReports(false) + " §aresolved report(s).");
                this.renderReports(g, p);
            } else {
                p.sendMessage("§7Shift-click to confirm.");
            }
            return;
        }
        if (this.handlePager(g, slot)) {
            this.renderReports(g, p);
            return;
        }
        if (slot < 9 || slot >= 45 || !(g.slotData[slot] instanceof String)) {
            return;
        }
        String id = (String) g.slotData[slot];
        String b = "reportdata." + id;
        if (!this.data.contains(b)) {
            this.renderReports(g, p);
            return;
        }
        String st = this.data.getString(b + ".status", "OPEN");
        String handler = this.data.getString(b + ".handler", "");
        String tn = this.data.getString(b + ".target", "?");

        if (e.isRightClick() && e.isShiftClick()) {
            if (p.hasPermission("unstableffa.history")) {
                try {
                    final UUID tid = UUID.fromString(this.data.getString(b + ".target-uuid", ""));
                    this.later(() -> this.openHistory(p, Bukkit.getOfflinePlayer(tid), tn, g));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return;
        }
        if (e.isRightClick()) {
            if (!p.hasPermission("unstableffa.forcetp")) {
                return;
            }
            Player tp = null;
            try {
                tp = Bukkit.getPlayer(UUID.fromString(this.data.getString(b + ".target-uuid", "")));
            } catch (IllegalArgumentException ignored) {
            }
            Location dest = null;
            if (tp != null && (!this.vanished.contains(tp.getUniqueId()) || p.hasPermission("unstableffa.vanish"))) {
                dest = tp.getLocation();
            } else if (this.data.contains(b + ".world")) {
                World w = Bukkit.getWorld(this.data.getString(b + ".world"));
                if (w != null) {
                    dest = new Location(w, this.data.getDouble(b + ".x"), this.data.getDouble(b + ".y"), this.data.getDouble(b + ".z"));
                }
            }
            if (dest == null) {
                p.sendMessage("§c" + tn + " is offline and no location was saved with this report.");
                return;
            }
            final Location fd = dest;
            if ("OPEN".equals(st)) {
                this.data.set(b + ".status", "CLAIMED");
                this.data.set(b + ".handler", p.getName());
                this.saveD();
                this.staffActivity.onReport(p);
            }
            this.later(() -> {
                p.closeInventory();
                p.teleport(fd);
                p.sendMessage("§aTeleported for report §f#" + id + "§a.");
            });
            return;
        }
        if (e.isShiftClick()) {
            if ("RESOLVED".equals(st)) {
                this.data.set(b + ".status", "OPEN");
                this.data.set(b + ".handler", (Object) null);
            } else {
                this.data.set(b + ".status", "RESOLVED");
                this.data.set(b + ".handler", p.getName());
                this.staffActivity.onReport(p);
                try {
                    Player rep = Bukkit.getPlayer(UUID.fromString(this.data.getString(b + ".reporter-uuid", "")));
                    if (rep != null) {
                        rep.sendMessage(this.formatMsgMap("report-resolved",
                                "&#FF9147Your report on &f{target} &#FF9147has been handled by staff. Thank you!",
                                java.util.Collections.singletonMap("target", tn)));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            this.saveD();
            this.renderReports(g, p);
            return;
        }
        // plain left click: claim / unclaim
        if ("RESOLVED".equals(st)) {
            return;
        }
        if ("CLAIMED".equals(st) && handler.equalsIgnoreCase(p.getName())) {
            this.data.set(b + ".status", "OPEN");
            this.data.set(b + ".handler", (Object) null);
        } else if ("CLAIMED".equals(st)) {
            p.sendMessage("§e" + handler + " §7is already handling this report.");
            return;
        } else {
            this.data.set(b + ".status", "CLAIMED");
            this.data.set(b + ".handler", p.getName());
            this.staffActivity.onReport(p);
        }
        this.saveD();
        this.renderReports(g, p);
    }

    // =====================================================================================
    // /kit  (kits.yml) - PlayerKits2 style: GUI, preview, cooldowns, one-time kits,
    //                    permission-locked kits, auto-equip armor, and an in-game editor.
    //
    //   /kit                         open the kit menu
    //   /kit <name>                  claim a kit
    //   /kit preview <name>          look inside a kit
    //   /kit create <name>           admin: new kit from your current inventory
    //   /kit edit <name>             admin: settings menu (items, cooldown, permission, ...)
    //   /kit delete <name> confirm   admin
    //   /kit give <player> <name>    admin: give a kit (no cooldown / permission checks)
    //   /kit reset <player> <name>   admin: reset someone's cooldown
    //
    //   unstableffa.kit.<name>  needed for kits that have "Permission required" ON
    // =====================================================================================

    private File kitsFile;
    private FileConfiguration kits;
    private static final List<String> KIT_SUBCOMMANDS = Arrays.asList("create", "edit", "delete", "give", "preview", "reset", "list");
    private static final String[] KIT_COOLDOWN_PRESETS = {"none", "1h", "12h", "1d", "3d", "7d", "30d"};

    private void loadKits() {
        this.kitsFile = Configs.file(this, "kits.yml");
        if (!this.kitsFile.exists()) {
            try {
                this.kitsFile.getParentFile().mkdirs();
                this.kitsFile.createNewFile();
            } catch (IOException ignored) {
            }
        }
        this.kits = YamlConfiguration.loadConfiguration(this.kitsFile);
    }

    private void saveKits() {
        try {
            this.kits.save(this.kitsFile);
        } catch (IOException ex) {
            this.getLogger().warning("Could not save kits.yml: " + ex.getMessage());
        }
    }

    private List<String> kitNames() {
        ConfigurationSection cs = this.kits.getConfigurationSection("kits");
        return cs == null ? new ArrayList<>() : new ArrayList<>(cs.getKeys(false));
    }

    private String kitKey(String in) {
        for (String k : this.kitNames()) {
            if (k.equalsIgnoreCase(in)) {
                return k;
            }
        }
        return null;
    }

    private String kp(String kit) {
        return "kits." + kit;
    }

    private String kitDisplay(String kit) {
        return this.translate(this.kits.getString(this.kp(kit) + ".display-name", "&#B15CFF&l" + kit));
    }

    private String kitCooldownText(String kit) {
        String c = this.kits.getString(this.kp(kit) + ".cooldown", "none");
        return c == null || c.equalsIgnoreCase("none") || c.equals("0") ? "none" : c;
    }

    private long kitCooldownMs(String kit) {
        String c = this.kitCooldownText(kit);
        return c.equals("none") ? 0L : this.parseTime(c);
    }

    private Map<Integer, ItemStack> kitItems(String kit) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        ConfigurationSection cs = this.kits.getConfigurationSection(this.kp(kit) + ".items");
        if (cs == null) {
            return out;
        }
        for (String k : cs.getKeys(false)) {
            try {
                ItemStack it = cs.getItemStack(k);
                if (it != null && !it.getType().isAir()) {
                    out.put(Integer.parseInt(k), it);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private int kitItemCount(String kit) {
        int n = 0;
        for (ItemStack it : this.kitItems(kit).values()) {
            n += it.getAmount();
        }
        return n;
    }

    private boolean hasKitPermission(Player p, String kit) {
        if (!this.kits.getBoolean(this.kp(kit) + ".permission-required", true)) {
            return true;
        }
        return p.hasPermission("unstableffa.kit." + kit.toLowerCase()) || p.hasPermission("unstableffa.kit.admin");
    }

    // 0 = can claim, >0 = ms left, -1 = one-time kit already claimed
    private long kitWait(Player p, String kit) {
        if (p.hasPermission("unstableffa.kit.bypasscooldown")) {
            return 0L;
        }
        long last = this.data.getLong("kits-used." + p.getUniqueId() + "." + kit.toLowerCase(), 0L);
        if (last <= 0L) {
            return 0L;
        }
        if (this.kits.getBoolean(this.kp(kit) + ".one-time", false)) {
            return -1L;
        }
        long left = last + this.kitCooldownMs(kit) - System.currentTimeMillis();
        return Math.max(0L, left);
    }

    private int armorSlotFor(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET") || n.endsWith("_SKULL") || n.endsWith("_HEAD")) {
            return n.endsWith("_HELMET") ? 3 : -1;
        }
        if (n.endsWith("_CHESTPLATE") || m == Material.ELYTRA) {
            return 2;
        }
        if (n.endsWith("_LEGGINGS")) {
            return 1;
        }
        if (n.endsWith("_BOOTS")) {
            return 0;
        }
        return -1;
    }

    // Gives every item of the kit; armor goes straight on (if enabled and that slot is free),
    // and anything that doesn't fit is dropped at the player's feet.
    private void giveKitItems(Player p, String kit) {
        boolean autoArmor = this.kits.getBoolean(this.kp(kit) + ".auto-armor", true);
        PlayerInventory pi = p.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> en : this.kitItems(kit).entrySet()) {
            ItemStack it = en.getValue().clone();
            if (autoArmor) {
                int a = this.armorSlotFor(it.getType());
                ItemStack[] armor = pi.getArmorContents();
                if (a >= 0 && (armor[a] == null || armor[a].getType().isAir())) {
                    armor[a] = it;
                    pi.setArmorContents(armor);
                    continue;
                }
            }
            rest.add(it);
        }
        Map<Integer, ItemStack> left = pi.addItem(rest.toArray(new ItemStack[0]));
        for (ItemStack it : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
        if (!left.isEmpty()) {
            p.sendMessage("§eYour inventory was full - some items were dropped at your feet.");
        }
    }

    private void claimKit(Player p, String kit) {
        if (!this.hasKitPermission(p, kit)) {
            p.sendMessage("§cYou don't have access to the §f" + ChatColor.stripColor(this.kitDisplay(kit)) + " §ckit.");
            return;
        }
        long wait = this.kitWait(p, kit);
        if (wait == -1L) {
            p.sendMessage("§cYou already claimed this kit. It can only be claimed once.");
            return;
        }
        if (wait > 0L) {
            p.sendMessage("§cYou can claim this kit again in §f" + this.formatUptime(Math.max(60000L, wait)) + "§c.");
            return;
        }
        if (this.kitItems(kit).isEmpty()) {
            p.sendMessage("§cThis kit is empty.");
            return;
        }
        this.giveKitItems(p, kit);
        this.data.set("kits-used." + p.getUniqueId() + "." + kit.toLowerCase(), System.currentTimeMillis());
        this.saveD();
        p.sendMessage(this.formatMsgMap("kit-claimed", "&#FF9147You received the {kit} &#FF9147kit!",
                java.util.Collections.singletonMap("kit", this.kitDisplay(kit))));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.0F);
    }

    // Called on a player's very first join.
    private void giveFirstJoinKit(Player p) {
        String k = this.kitKey(this.getConfig().getString("kits.first-join-kit", ""));
        if (k == null) {
            return;
        }
        this.giveKitItems(p, k);
        this.data.set("kits-used." + p.getUniqueId() + "." + k.toLowerCase(), System.currentTimeMillis());
        this.saveD();
    }

    private void handleKit(CommandSender s, String[] args) {
        boolean admin = s.hasPermission("unstableffa.kit.admin");
        if (args.length > 0 && KIT_SUBCOMMANDS.contains(args[0].toLowerCase())) {
            String sub = args[0].toLowerCase();
            if (sub.equals("preview")) {
                if (!(s instanceof Player) || args.length < 2) {
                    s.sendMessage("§cUsage: /kit preview <kit>");
                    return;
                }
                String k = this.kitKey(args[1]);
                if (k == null) {
                    s.sendMessage("§cThat kit doesn't exist.");
                    return;
                }
                this.openKitPreview((Player) s, k, null);
                return;
            }
            if (sub.equals("list")) {
                List<String> names = this.kitNames();
                s.sendMessage(names.isEmpty() ? "§cNo kits yet." : "§b§lKITS: §f" + String.join("§7, §f", names));
                return;
            }
            if (!admin) {
                s.sendMessage(this.getMsg("no-permission"));
                return;
            }
            this.handleKitAdmin(s, sub, args);
            return;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("§cUsage: /kit <list|give|reset|delete>");
            return;
        }
        Player p = (Player) s;
        if (!p.hasPermission("unstableffa.kit")) {
            p.sendMessage(this.getMsg("no-permission"));
            return;
        }
        if (args.length == 0) {
            this.openKits(p);
            return;
        }
        String k = this.kitKey(args[0]);
        if (k == null) {
            p.sendMessage("§cThat kit doesn't exist. §7Use §f/kit §7to see all kits.");
            return;
        }
        this.claimKit(p, k);
    }

    private void handleKitAdmin(CommandSender s, String sub, String[] args) {
        switch (sub) {
            case "create": {
                if (!(s instanceof Player) || args.length < 2) {
                    s.sendMessage("§cUsage: /kit create <name>  §7(uses your current inventory)");
                    return;
                }
                Player p = (Player) s;
                String name = args[1];
                if (!name.matches("[A-Za-z0-9_-]{1,24}") || KIT_SUBCOMMANDS.contains(name.toLowerCase())) {
                    p.sendMessage("§cKit names: 1-24 letters, numbers, _ or -  (and not a sub-command name).");
                    return;
                }
                if (this.kitKey(name) != null) {
                    p.sendMessage("§cThat kit already exists. Use §f/kit edit " + name);
                    return;
                }
                String base = this.kp(name);
                PlayerInventory pi = p.getInventory();
                ItemStack[] storage = pi.getStorageContents();
                int slot = 0;
                // inventory 9-35 first, then hotbar, then armor + offhand (keeps a sensible order in the editor)
                for (int i = 9; i < storage.length; i++) {
                    if (storage[i] != null && !storage[i].getType().isAir()) {
                        this.kits.set(base + ".items." + slot, storage[i].clone());
                    }
                    slot++;
                }
                for (int i = 0; i < 9 && i < storage.length; i++) {
                    if (storage[i] != null && !storage[i].getType().isAir()) {
                        this.kits.set(base + ".items." + slot, storage[i].clone());
                    }
                    slot++;
                }
                ItemStack[] armor = pi.getArmorContents();
                for (int i = armor.length - 1; i >= 0; i--) {
                    if (armor[i] != null && !armor[i].getType().isAir()) {
                        this.kits.set(base + ".items." + slot, armor[i].clone());
                    }
                    slot++;
                }
                ItemStack off = pi.getItemInOffHand();
                if (off != null && !off.getType().isAir()) {
                    this.kits.set(base + ".items." + slot, off.clone());
                }
                ItemStack hand = pi.getItemInMainHand();
                ItemStack icon = (hand == null || hand.getType().isAir()) ? new ItemStack(Material.CHEST) : new ItemStack(hand.getType());
                this.kits.set(base + ".display-name", "&#B15CFF&l" + name);
                this.kits.set(base + ".icon", icon);
                this.kits.set(base + ".cooldown", "1d");
                this.kits.set(base + ".permission-required", true);
                this.kits.set(base + ".one-time", false);
                this.kits.set(base + ".auto-armor", true);
                this.saveKits();
                p.sendMessage("§aKit §f" + name + " §acreated from your inventory. Permission: §funstableffa.kit." + name.toLowerCase());
                this.openKitSettings(p, name);
                return;
            }
            case "edit": {
                if (!(s instanceof Player) || args.length < 2) {
                    s.sendMessage("§cUsage: /kit edit <kit>");
                    return;
                }
                String k = this.kitKey(args[1]);
                if (k == null) {
                    s.sendMessage("§cThat kit doesn't exist.");
                    return;
                }
                this.openKitSettings((Player) s, k);
                return;
            }
            case "delete": {
                if (args.length < 2) {
                    s.sendMessage("§cUsage: /kit delete <kit> confirm");
                    return;
                }
                String k = this.kitKey(args[1]);
                if (k == null) {
                    s.sendMessage("§cThat kit doesn't exist.");
                    return;
                }
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    s.sendMessage("§eType §f/kit delete " + k + " confirm §eto delete it for good.");
                    return;
                }
                this.kits.set(this.kp(k), (Object) null);
                this.saveKits();
                s.sendMessage("§aKit §f" + k + " §adeleted.");
                return;
            }
            case "give": {
                if (args.length < 3) {
                    s.sendMessage("§cUsage: /kit give <player> <kit>");
                    return;
                }
                Player t = Bukkit.getPlayer(args[1]);
                String k = this.kitKey(args[2]);
                if (t == null) {
                    s.sendMessage(this.getMsg("player-not-found").replace("{player}", args[1]));
                    return;
                }
                if (k == null) {
                    s.sendMessage("§cThat kit doesn't exist.");
                    return;
                }
                this.giveKitItems(t, k);
                t.sendMessage(this.formatMsgMap("kit-claimed", "&#FF9147You received the {kit} &#FF9147kit!",
                        java.util.Collections.singletonMap("kit", this.kitDisplay(k))));
                s.sendMessage("§aGave §f" + k + " §ato §f" + t.getName() + "§a.");
                return;
            }
            case "reset": {
                if (args.length < 3) {
                    s.sendMessage("§cUsage: /kit reset <player> <kit|all>");
                    return;
                }
                OfflinePlayer t = this.resolveOffline(args[1]);
                if (args[2].equalsIgnoreCase("all")) {
                    this.data.set("kits-used." + t.getUniqueId(), (Object) null);
                } else {
                    String k = this.kitKey(args[2]);
                    if (k == null) {
                        s.sendMessage("§cThat kit doesn't exist.");
                        return;
                    }
                    this.data.set("kits-used." + t.getUniqueId() + "." + k.toLowerCase(), (Object) null);
                }
                this.saveD();
                s.sendMessage("§aReset kit cooldown(s) for §f" + this.displayName(t, args[1]) + "§a.");
                return;
            }
            default:
                return;
        }
    }

    // ---------- kit menu ----------

    private static final int[] KIT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private void openKits(Player p) {
        Gui g = this.newGui("kits", 54, "§8Kits", null);
        this.renderKits(g, p);
        p.openInventory(g.inv);
    }

    private void renderKitsRaw(Gui g, Player p) {
        Inventory inv = g.inv;
        inv.clear();
        Arrays.fill(g.slotData, null);
        boolean admin = p.hasPermission("unstableffa.kit.admin");
        boolean showLocked = this.getConfig().getBoolean("kits.show-locked", true);
        List<String> list = new ArrayList<>();
        for (String k : this.kitNames()) {
            if (admin || showLocked || this.hasKitPermission(p, k)) {
                list.add(k);
            }
        }
        int per = KIT_SLOTS.length;
        int total = list.size();
        int pages = Math.max(1, (total + per - 1) / per);
        g.page = Math.max(0, Math.min(g.page, pages - 1));

        ItemStack frame = this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(4, this.createGuiItem(Material.CHEST, "<gradient:#FF7518:#FFFFFF>&lKi</gradient><gradient:#FFFFFF:#FF7518>&lts</gradient>", "&#FF8330Left-click &7a kit » claim it", "&#FF8330Right-click &7a kit » see what's inside"));

        int start = g.page * per;
        for (int i = 0; i < per && start + i < total; i++) {
            String k = list.get(start + i);
            inv.setItem(KIT_SLOTS[i], this.kitIcon(p, k, admin));
            g.slotData[KIT_SLOTS[i]] = k;
        }
        if (total == 0) {
            inv.setItem(22, this.createGuiItem(Material.BARRIER, "&#D9381ENo kits yet",
                    admin ? "&7Make one with &f/kit create <name>" : "&7Check back later!"));
        }
        if (g.page > 0) {
            inv.setItem(45, this.createGuiItem(Material.ARROW, "&#FF8330« Previous page"));
        }
        if (g.page < pages - 1) {
            inv.setItem(53, this.createGuiItem(Material.ARROW, "&#FF8330Next page »"));
        }
        inv.setItem(49, this.createGuiItem(Material.BARRIER, "&#D9381EClose"));
        g.slotData[49] = "close";
    }

    private ItemStack kitIcon(Player p, String k, boolean admin) {
        ItemStack base = this.kits.getItemStack(this.kp(k) + ".icon");
        ItemStack it = base == null || base.getType().isAir() ? new ItemStack(Material.CHEST) : base.clone();
        it.setAmount(1);
        boolean perm = this.hasKitPermission(p, k);
        long wait = this.kitWait(p, k);
        List<String> lore = new ArrayList<>();
        String desc = this.kits.getString(this.kp(k) + ".description", "");
        if (desc != null && !desc.isEmpty()) {
            lore.addAll(this.wrap(desc, 32, "&7"));
            lore.add(" ");
        }
        String cd = this.kitCooldownText(k);
        lore.add("&7Cooldown: &f" + (this.kits.getBoolean(this.kp(k) + ".one-time", false) ? "one time only" : (cd.equals("none") ? "none" : cd)));
        lore.add("&7Items: &f" + this.kitItemCount(k));
        lore.add(" ");
        if (!perm) {
            lore.add("&#D9381E✖ Locked");
        } else if (wait == -1L) {
            lore.add("&8✔ Already claimed");
        } else if (wait > 0L) {
            lore.add("&#FF7518⌚ Ready in " + this.formatUptime(Math.max(60000L, wait)));
        } else {
            lore.add("&#FF9147✔ Ready to claim");
        }
        lore.add("&#FF8330Right-click &7» preview");
        if (admin) {
            lore.add("&8Shift-right-click » edit kit");
        }
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(this.kitDisplay(k));
            List<String> t = new ArrayList<>();
            for (String l : lore) {
                t.add(this.translate(l));
            }
            m.setLore(t);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            m.setEnchantmentGlintOverride(perm && wait == 0L);
            it.setItemMeta(m);
        }
        return it;
    }

    private void clickKits(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot == 45 && g.page > 0) {
            g.page--;
            this.renderKits(g, p);
            return;
        }
        if (slot == 53) {
            g.page++;
            this.renderKits(g, p);
            return;
        }
        if (slot < 0 || slot >= 54 || !(g.slotData[slot] instanceof String)) {
            return;
        }
        String k = (String) g.slotData[slot];
        if (k.equals("close")) {
            this.later(p::closeInventory);
            return;
        }
        if (this.kitKey(k) == null) {
            this.renderKits(g, p);
            return;
        }
        if (e.isRightClick() && e.isShiftClick() && p.hasPermission("unstableffa.kit.admin")) {
            this.later(() -> this.openKitSettings(p, k));
        } else if (e.isRightClick()) {
            this.later(() -> this.openKitPreview(p, k, g));
        } else {
            this.claimKit(p, k);
            this.renderKits(g, p);
        }
    }

    // ---------- preview (read only) ----------

    private void openKitPreview(Player p, String kit, Gui parent) {
        Gui g = this.newGui("kit-preview", 54, "§8Kit » " + ChatColor.stripColor(this.kitDisplay(kit)), parent);
        g.key = kit;
        Inventory inv = g.inv;
        for (Map.Entry<Integer, ItemStack> en : this.kitItems(kit).entrySet()) {
            if (en.getKey() >= 0 && en.getKey() < 45) {
                inv.setItem(en.getKey(), en.getValue().clone());
            }
        }
        ItemStack frame = this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, frame);
        }
        inv.setItem(45, this.createGuiItem(Material.ARROW, parent != null ? "&#FF8330« Back" : "&#D9381EClose"));
        g.slotData[45] = "back";
        long wait = this.kitWait(p, kit);
        boolean perm = this.hasKitPermission(p, kit);
        inv.setItem(49, this.createGuiItem(perm && wait == 0L ? Material.LIME_DYE : Material.GRAY_DYE,
                perm && wait == 0L ? "<gradient:#FF7518:#FFFFFF>&lClaim</gradient><gradient:#FFFFFF:#FF7518>&l kit</gradient>" : "&7&lCan't claim right now",
                !perm ? "&#D9381ELocked" : (wait == -1L ? "&8Already claimed" : (wait > 0L ? "&#FF7518Ready in " + this.formatUptime(Math.max(60000L, wait)) : "&#FF8330Click to claim"))));
        g.slotData[49] = "claim";
        p.openInventory(inv);
    }

    private void clickKitPreview(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54 || !(g.slotData[slot] instanceof String)) {
            return;
        }
        String a = (String) g.slotData[slot];
        if (a.equals("back")) {
            this.later(() -> {
                if (g.parent != null) {
                    this.renderKits(g.parent, p);
                    p.openInventory(g.parent.inv);
                } else {
                    p.closeInventory();
                }
            });
        } else if (a.equals("claim")) {
            final String k = g.key;
            this.later(() -> {
                p.closeInventory();
                if (this.kitKey(k) != null) {
                    this.claimKit(p, k);
                }
            });
        }
    }

    // ---------- admin: kit settings ----------

    // The kit editor is one menu now (like PlayerKits2): the kit's items on top, the settings in the bottom row.
    private void openKitSettings(Player p, String kit) {
        this.openKitItems(p, kit);
    }

    private void renderKitSettings(Gui g) {
        String k = g.key;
        String b = this.kp(k);
        Inventory inv = g.inv;
        Arrays.fill(g.slotData, null);
        ItemStack frame = this.createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, frame);
        }
        boolean perm = this.kits.getBoolean(b + ".permission-required", true);
        boolean once = this.kits.getBoolean(b + ".one-time", false);
        boolean armor = this.kits.getBoolean(b + ".auto-armor", true);

        inv.setItem(4, this.createGuiItem(Material.BOOK, this.kitDisplay(k), "&7Kit id: &f" + k, "&7Items: &f" + this.kitItemCount(k)));
        inv.setItem(10, this.createGuiItem(Material.CHEST, "&#FF8330Edit items", "&7Put items in / take them out.", "&7Saved when you close it."));
        g.slotData[10] = "items";
        inv.setItem(11, this.createGuiItem(Material.CLOCK, "&#FF8330Cooldown: &f" + this.kitCooldownText(k),
                "&7How long before it can be claimed again.", " ", "&#FF8330Left-click &7» next preset", "&#FF8330Right-click &7» type your own (e.g. 2d12h)"));
        g.slotData[11] = "cooldown";
        inv.setItem(12, this.createGuiItem(Material.TRIPWIRE_HOOK, (perm ? "&#FF9147" : "&#D9381E") + "Permission required: " + (perm ? "On" : "Off"),
                "&7When on, players need", "&funstableffa.kit." + k.toLowerCase(), " ", "&#FF8330Click to toggle"));
        g.slotData[12] = "perm";
        inv.setItem(13, this.createGuiItem(Material.NETHER_STAR, (once ? "&#FF9147" : "&#D9381E") + "One time only: " + (once ? "On" : "Off"),
                "&7Can only ever be claimed once.", " ", "&#FF8330Click to toggle"));
        g.slotData[13] = "once";
        inv.setItem(14, this.createGuiItem(Material.IRON_CHESTPLATE, (armor ? "&#FF9147" : "&#D9381E") + "Auto-equip armor: " + (armor ? "On" : "Off"),
                "&7Armor goes straight onto the player", "&7if that armor slot is empty.", " ", "&#FF8330Click to toggle"));
        g.slotData[14] = "armor";
        inv.setItem(15, this.createGuiItem(Material.ITEM_FRAME, "&#FF8330Set icon", "&7Uses the item in your main hand.", " ", "&#FF8330Click to set"));
        g.slotData[15] = "icon";
        inv.setItem(16, this.createGuiItem(Material.NAME_TAG, "&#FF8330Name & description", "&7Name: &r" + this.kitDisplay(k),
                " ", "&#FF8330Left-click &7» change name", "&#FF8330Right-click &7» change description"));
        g.slotData[16] = "name";
        inv.setItem(22, this.createGuiItem(Material.OAK_DOOR, "&7Done"));
        g.slotData[22] = "close";
        inv.setItem(26, this.createGuiItem(Material.LAVA_BUCKET, "&#D9381EDelete kit", "&#FF8330Shift-click to delete"));
        g.slotData[26] = "delete";
    }

    private void clickKitSettings(InventoryClickEvent e, Player p, Gui g) {
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 27 || !(g.slotData[slot] instanceof String) || !p.hasPermission("unstableffa.kit.admin")) {
            return;
        }
        final String k = g.key;
        if (this.kitKey(k) == null) {
            this.later(p::closeInventory);
            return;
        }
        String b = this.kp(k);
        switch ((String) g.slotData[slot]) {
            case "items":
                this.later(() -> this.openKitItems(p, k));
                return;
            case "cooldown":
                if (e.isRightClick()) {
                    this.askChat(p, "§eType the cooldown for §f" + k + " §e(e.g. §f30m§e, §f1d§e, §f2d12h§e or §fnone§e):", text -> {
                        String v = text.trim().toLowerCase();
                        if (!v.equals("none") && !v.matches("(\\d+[smhdwy])+")) {
                            p.sendMessage("§cThat's not a valid time.");
                        } else {
                            this.kits.set(b + ".cooldown", v);
                            this.saveKits();
                            p.sendMessage("§aCooldown set to §f" + v + "§a.");
                        }
                        this.openKitSettings(p, k);
                    });
                    return;
                }
                String cur = this.kitCooldownText(k);
                int idx = Arrays.asList(KIT_COOLDOWN_PRESETS).indexOf(cur);
                this.kits.set(b + ".cooldown", KIT_COOLDOWN_PRESETS[(idx + 1) % KIT_COOLDOWN_PRESETS.length]);
                break;
            case "perm":
                this.kits.set(b + ".permission-required", !this.kits.getBoolean(b + ".permission-required", true));
                break;
            case "once":
                this.kits.set(b + ".one-time", !this.kits.getBoolean(b + ".one-time", false));
                break;
            case "armor":
                this.kits.set(b + ".auto-armor", !this.kits.getBoolean(b + ".auto-armor", true));
                break;
            case "icon": {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) {
                    p.sendMessage("§cHold the item you want as the icon.");
                    return;
                }
                ItemStack icon = hand.clone();
                icon.setAmount(1);
                this.kits.set(b + ".icon", icon);
                break;
            }
            case "name":
                if (e.isRightClick()) {
                    this.askChat(p, "§eType a short description for §f" + k + "§e (or §fnone§e):", text -> {
                        this.kits.set(b + ".description", text.equalsIgnoreCase("none") ? null : text);
                        this.saveKits();
                        this.openKitSettings(p, k);
                    });
                } else {
                    this.askChat(p, "§eType the display name for §f" + k + "§e (color codes work, e.g. &#FF7518&lGold):", text -> {
                        this.kits.set(b + ".display-name", text);
                        this.saveKits();
                        this.openKitSettings(p, k);
                    });
                }
                return;
            case "close":
                this.later(p::closeInventory);
                return;
            case "delete":
                if (!e.isShiftClick()) {
                    p.sendMessage("§7Shift-click to delete the kit.");
                    return;
                }
                this.kits.set(b, (Object) null);
                this.saveKits();
                p.sendMessage("§aKit §f" + k + " §adeleted.");
                this.later(p::closeInventory);
                return;
            default:
                return;
        }
        this.saveKits();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.2F);
        this.renderKitSettings(g);
    }

    // ---------- admin: kit item editor (slots 0-44 are free to edit) ----------

    private void openKitItems(Player p, String kit) {
        Gui g = this.newGui("kit-items", 54, "§8Editing kit » " + kit, null);
        g.key = kit;
        Inventory inv = g.inv;
        for (Map.Entry<Integer, ItemStack> en : this.kitItems(kit).entrySet()) {
            if (en.getKey() >= 0 && en.getKey() < 45) {
                inv.setItem(en.getKey(), en.getValue().clone());
            }
        }
        this.renderKitBar(g);
        p.openInventory(inv);
    }

    private static final int KB_ICON = 45, KB_COOLDOWN = 46, KB_PERM = 47, KB_ONCE = 48, KB_ARMOR = 49, KB_NAME = 50,
            KB_DELETE = 52, KB_SAVE = 53;

    private String kitStatus(boolean on) {
        return on ? "&7Status: &#FF9147Enabled" : "&7Status: &#D9381EDisabled";
    }

    /** Bottom row of the kit editor: every setting, PlayerKits2 style. Slots 0-44 are the kit's items. */
    private void renderKitBar(Gui g) {
        String k = g.key;
        String b = this.kp(k);
        Inventory inv = g.inv;
        boolean perm = this.kits.getBoolean(b + ".permission-required", true);
        boolean once = this.kits.getBoolean(b + ".one-time", false);
        boolean armor = this.kits.getBoolean(b + ".auto-armor", true);

        ItemStack icon = this.kits.getItemStack(b + ".icon");
        ItemStack display = icon == null ? new ItemStack(Material.CHEST) : icon.clone();
        display.setAmount(1);
        ItemMeta dm = display.getItemMeta();
        if (dm != null) {
            dm.setDisplayName(this.translate("<gradient:#FF7518:#FFFFFF>&lDispla</gradient><gradient:#FFFFFF:#FF7518>&ly Item</gradient>"));
            List<String> dl = new ArrayList<>();
            dl.add(this.translate("&7The icon players see in /kit."));
            dl.add("");
            dl.add(this.translate("&#FF8330Click with an item &7on your cursor to set it"));
            dl.add(this.translate("&7(or click to use the item in your hand)"));
            dm.setLore(dl);
            dm.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            display.setItemMeta(dm);
        }
        inv.setItem(KB_ICON, display);
        inv.setItem(KB_COOLDOWN, this.createGuiItem(Material.CLOCK, "<gradient:#FF7518:#FFFFFF>&lCool</gradient><gradient:#FFFFFF:#FF7518>&ldown</gradient>",
                "&7Time before it can be claimed again.", "&7Current: &f" + this.kitCooldownText(k), "",
                "&#FF8330Left-click &7to pick the next preset", "&#FF8330Right-click &7to type your own (2d12h)"));
        inv.setItem(KB_PERM, this.createGuiItem(Material.TRIPWIRE_HOOK, "<gradient:#FF7518:#FFFFFF>&lPermission</gradient><gradient:#FFFFFF:#FF7518>&l Required</gradient>",
                "&7Players need &funstableffa.kit." + k.toLowerCase(), this.kitStatus(perm), "", "&#FF8330Click to toggle"));
        inv.setItem(KB_ONCE, this.createGuiItem(Material.NETHER_STAR, "<gradient:#FF7518:#FFFFFF>&lOne </gradient><gradient:#FFFFFF:#FF7518>&lTime</gradient>",
                "&7Can only be claimed once, ever.", this.kitStatus(once), "", "&#FF8330Click to toggle"));
        inv.setItem(KB_ARMOR, this.createGuiItem(Material.IRON_CHESTPLATE, "<gradient:#FF7518:#FFFFFF>&lAuto </gradient><gradient:#FFFFFF:#FF7518>&lArmor</gradient>",
                "&7Armor goes straight on when the slot is empty.", this.kitStatus(armor), "", "&#FF8330Click to toggle"));
        inv.setItem(KB_NAME, this.createGuiItem(Material.NAME_TAG, "<gradient:#FF7518:#FFFFFF>&lNa</gradient><gradient:#FFFFFF:#FF7518>&lme</gradient> & Description",
                "&7Name: &r" + this.kitDisplay(k), "", "&#FF8330Left-click &7to change the name", "&#FF8330Right-click &7to change the description"));
        inv.setItem(51, this.createGuiItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(KB_DELETE, this.createGuiItem(Material.BARRIER, "<gradient:#FF7518:#FFFFFF>&lDelet</gradient><gradient:#FFFFFF:#FF7518>&le Kit</gradient>", "&7This can't be undone.", "", "&#D9381EShift-click to delete"));
        inv.setItem(KB_SAVE, this.createGuiItem(Material.LIME_DYE, "<gradient:#FF7518:#FFFFFF>&lSa</gradient><gradient:#FFFFFF:#FF7518>&lve</gradient> & Close", "&7Saves the items and closes the editor.",
                "&7Closing the menu also saves."));
        for (int i = 45; i < 54; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null) {
                ItemMeta m = it.getItemMeta();
                if (m != null) {
                    m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                    it.setItemMeta(m);
                }
            }
        }
    }

    private void clickKitItems(InventoryClickEvent e, Player p, Gui g) {
        int raw = e.getRawSlot();
        if (raw < 45 || raw >= 54) {
            if (e.getClick() == ClickType.DOUBLE_CLICK) {
                e.setCancelled(true); // double-click would pull items out of the settings row
            }
            return;
        }
        e.setCancelled(true);
        if (!p.hasPermission("unstableffa.kit.admin")) {
            return;
        }
        final String k = g.key;
        if (this.kitKey(k) == null) {
            this.later(p::closeInventory);
            return;
        }
        String b = this.kp(k);
        switch (raw) {
            case KB_ICON: {
                ItemStack src = e.getCursor() != null && !e.getCursor().getType().isAir() ? e.getCursor() : p.getInventory().getItemInMainHand();
                if (src == null || src.getType().isAir()) {
                    p.sendMessage(this.translate("&8» &#D9381E&lError! &7Hold an item or put one on your cursor first."));
                    return;
                }
                ItemStack icon = src.clone();
                icon.setAmount(1);
                this.kits.set(b + ".icon", icon);
                break;
            }
            case KB_COOLDOWN:
                if (e.isRightClick()) {
                    this.later(p::closeInventory); // saves the items
                    this.askChat(p, "§eType the cooldown for §f" + k + " §e(e.g. §f30m§e, §f1d§e, §f2d12h§e or §fnone§e):", text -> {
                        String v = text.trim().toLowerCase();
                        if (!v.equals("none") && !v.matches("(\\d+[smhdwy])+")) {
                            p.sendMessage("§cThat's not a valid time.");
                        } else {
                            this.kits.set(b + ".cooldown", v);
                            this.saveKits();
                            p.sendMessage("§aCooldown set to §f" + v + "§a.");
                        }
                        this.openKitItems(p, k);
                    });
                    return;
                }
                String cur = this.kitCooldownText(k);
                int idx = Arrays.asList(KIT_COOLDOWN_PRESETS).indexOf(cur);
                this.kits.set(b + ".cooldown", KIT_COOLDOWN_PRESETS[(idx + 1) % KIT_COOLDOWN_PRESETS.length]);
                break;
            case KB_PERM:
                this.kits.set(b + ".permission-required", !this.kits.getBoolean(b + ".permission-required", true));
                break;
            case KB_ONCE:
                this.kits.set(b + ".one-time", !this.kits.getBoolean(b + ".one-time", false));
                break;
            case KB_ARMOR:
                this.kits.set(b + ".auto-armor", !this.kits.getBoolean(b + ".auto-armor", true));
                break;
            case KB_NAME:
                this.later(p::closeInventory); // saves the items
                if (e.isRightClick()) {
                    this.askChat(p, "§eType a short description for §f" + k + "§e (or §fnone§e):", text -> {
                        this.kits.set(b + ".description", text.equalsIgnoreCase("none") ? null : text);
                        this.saveKits();
                        this.openKitItems(p, k);
                    });
                } else {
                    this.askChat(p, "§eType the display name for §f" + k + "§e (color codes work, e.g. &#FF7518&lGold):", text -> {
                        this.kits.set(b + ".display-name", text);
                        this.saveKits();
                        this.openKitItems(p, k);
                    });
                }
                return;
            case KB_DELETE:
                if (!e.isShiftClick()) {
                    p.sendMessage(this.translate("&7Shift-click to delete the kit."));
                    return;
                }
                this.kits.set(b, (Object) null);
                this.saveKits();
                p.sendMessage(this.translate("&8» &#FF9147&lDeleted! &7Kit &f" + k + " &7is gone."));
                this.later(p::closeInventory);
                return;
            case KB_SAVE:
                this.later(p::closeInventory); // closing saves
                return;
            default:
                return;
        }
        this.saveKits();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.2F);
        this.renderKitBar(g);
    }

    private void saveKitItems(Gui g, Player p) {
        if (this.kitKey(g.key) == null) {
            return;
        }
        String b = this.kp(g.key) + ".items";
        this.kits.set(b, (Object) null);
        int count = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = g.inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                this.kits.set(b + "." + i, it.clone());
                count++;
            }
        }
        this.saveKits();
        p.sendMessage("§aSaved §f" + count + " §aitem stack(s) in kit §f" + g.key + "§a.");
    }

}