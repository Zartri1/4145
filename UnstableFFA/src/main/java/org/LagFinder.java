package org;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * /lagfinder  -  find what is lagging the server.
 *
 *   /lagfinder              overview: TPS, memory, chunks, entities and the 10 laggiest chunks (click [TP])
 *   /lagfinder chunks [n]   the n laggiest chunks (default 10)
 *   /lagfinder entities     which entity types there are the most of
 *   /lagfinder world        entities / chunks per world
 *   /lagfinder tp <world> <x> <z>   teleport to a chunk (used by the [TP] buttons)
 *
 * Also watches TPS by itself: when it stays low, staff with unstableffa.lagfinder.alerts get a
 * clickable alert (and Discord, if a webhook is set).
 * Permission: unstableffa.lagfinder.   Settings: config.yml -> lag-finder
 * Works on its own: it only needs the plugin's config.yml.
 */
final class LagFinder {

    private final zenithEssentials plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    // TPS measured by the plugin itself (works on Spigot and Paper)
    private final long[] tickTimes = new long[600]; // last 30 seconds of ticks
    private int tickIndex = 0;
    private long ticksSeen = 0;
    private BukkitTask tickTask;
    private BukkitTask watchTask;
    private int lowChecks = 0;
    private long lastAlert = 0;

    LagFinder(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (this.tickTask == null) {
            this.tickTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                this.tickTimes[this.tickIndex] = System.nanoTime();
                this.tickIndex = (this.tickIndex + 1) % this.tickTimes.length;
                this.ticksSeen++;
            }, 1L, 1L);
        }
        if (this.watchTask == null) {
            this.watchTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::watch, 20L * 60L, 20L * 15L);
        }
    }

    private FileConfiguration cfg() {
        return this.plugin.getConfig();
    }

    private static String c(String s) {
        return Announcer.color(s);
    }

    // =====================================================================================
    // measuring
    // =====================================================================================

    /** Average TPS over the last "seconds" seconds (max 30). */
    double tps(int seconds) {
        int ticks = Math.min(Math.min(seconds * 20, this.tickTimes.length - 1), (int) Math.max(0, this.ticksSeen - 1));
        if (ticks < 20) {
            return 20.0;
        }
        int newest = (this.tickIndex - 1 + this.tickTimes.length) % this.tickTimes.length;
        int oldest = (newest - ticks + this.tickTimes.length) % this.tickTimes.length;
        double secs = (this.tickTimes[newest] - this.tickTimes[oldest]) / 1_000_000_000.0;
        return secs <= 0 ? 20.0 : Math.min(20.0, ticks / secs);
    }

    // Paper's own 1m TPS, if it exists (more accurate); otherwise ours.
    private double tps1m() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            if (r instanceof double[]) {
                return Math.min(20.0, ((double[]) r)[0]);
            }
        } catch (Exception ignored) {
        }
        return this.tps(30);
    }

    private double mspt() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getAverageTickTime").invoke(Bukkit.getServer());
            if (r instanceof Number) {
                return ((Number) r).doubleValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String tpsColor(double tps) {
        return tps >= 18 ? "&#FF9147" : (tps >= 15 ? "&#FF8330" : "&#D9381E");
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    /** One loaded chunk and what's in it. */
    private static final class ChunkInfo {
        World world;
        int x;
        int z;
        int entities;
        int items;
        int mobs;
        int tiles;
        int hoppers;
        int redstone;
        double score;
        String topEntity = "";
    }

    private static boolean isRedstone(Material m) {
        String n = m.name();
        return n.contains("PISTON") || n.contains("OBSERVER") || n.contains("REPEATER") || n.contains("COMPARATOR")
                || n.equals("DISPENSER") || n.equals("DROPPER") || n.contains("REDSTONE");
    }

    private List<ChunkInfo> scan() {
        FileConfiguration c = this.cfg();
        double wEntity = c.getDouble("lag-finder.weights.entity", 1.0);
        double wItem = c.getDouble("lag-finder.weights.dropped-item", 0.5);
        double wTile = c.getDouble("lag-finder.weights.tile-entity", 0.5);
        double wHopper = c.getDouble("lag-finder.weights.hopper", 3.0);
        double wRedstone = c.getDouble("lag-finder.weights.redstone", 1.5);
        List<ChunkInfo> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (Chunk ch : w.getLoadedChunks()) {
                ChunkInfo ci = new ChunkInfo();
                ci.world = w;
                ci.x = ch.getX();
                ci.z = ch.getZ();
                Map<EntityType, Integer> types = new EnumMap<>(EntityType.class);
                for (Entity e : ch.getEntities()) {
                    if (e instanceof Player) {
                        continue;
                    }
                    ci.entities++;
                    if (e instanceof Item) {
                        ci.items++;
                    } else if (e instanceof org.bukkit.entity.LivingEntity) {
                        ci.mobs++;
                    }
                    types.merge(e.getType(), 1, Integer::sum);
                }
                for (BlockState bs : ch.getTileEntities()) {
                    ci.tiles++;
                    Material m = bs.getType();
                    if (m == Material.HOPPER) {
                        ci.hoppers++;
                    } else if (isRedstone(m)) {
                        ci.redstone++;
                    }
                }
                int best = 0;
                for (Map.Entry<EntityType, Integer> en : types.entrySet()) {
                    if (en.getValue() > best) {
                        best = en.getValue();
                        ci.topEntity = best + "x " + pretty(en.getKey().name());
                    }
                }
                ci.score = (ci.entities - ci.items) * wEntity + ci.items * wItem + (ci.tiles - ci.hoppers - ci.redstone) * wTile
                        + ci.hoppers * wHopper + ci.redstone * wRedstone;
                if (ci.score > 0) {
                    out.add(ci);
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    private static String pretty(String enumName) {
        String s = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // =====================================================================================
    // /lagfinder
    // =====================================================================================

    void handle(CommandSender s, String[] args) {
        if (!s.hasPermission("unstableffa.lagfinder")) {
            s.sendMessage(c("&8» &#D9381E&lDenied! &7You lack the required permissions."));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "tp":
                this.teleport(s, args);
                return;
            case "chunks": {
                int n = 10;
                if (args.length > 1) {
                    try {
                        n = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                this.showChunks(s, this.scan(), n);
                return;
            }
            case "entities":
                this.showEntities(s);
                return;
            case "world":
            case "worlds":
                this.showWorlds(s);
                return;
            default:
                this.overview(s);
        }
    }

    private void overview(CommandSender s) {
        double t1 = this.tps1m();
        double t5s = this.tps(5);
        double ms = this.mspt();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        int chunks = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getLoadedChunks().length;
            entities += w.getEntities().size();
        }
        s.sendMessage(c("&8&m          &r <gradient:#FF7518:#FFFFFF>&lLAG F</gradient><gradient:#FFFFFF:#FF7518>&lINDER</gradient> &8&m          "));
        s.sendMessage(c("&7TPS: " + tpsColor(t1) + fmt(t1) + " &8(1m) &7· " + tpsColor(t5s) + fmt(t5s) + " &8(last 5s)"
                + (ms >= 0 ? " &7· &f" + fmt(ms) + " ms/tick" : "")));
        s.sendMessage(c("&7Memory: &f" + used + "&7/&f" + max + " MB &7· Chunks: &f" + chunks + " &7· Entities: &f" + entities
                + " &7· Players: &f" + Bukkit.getOnlinePlayers().size()));
        this.showChunks(s, this.scan(), 10);
        s.sendMessage(c("&8/lagfinder entities &7· &8/lagfinder world &7· &8/lagfinder chunks 25"));
    }

    private void showChunks(CommandSender s, List<ChunkInfo> list, int n) {
        if (list.isEmpty()) {
            s.sendMessage(c("&8» &7No busy chunks found."));
            return;
        }
        s.sendMessage(c("<gradient:#FF7518:#FFFFFF>&lLaggiest</gradient><gradient:#FFFFFF:#FF7518>&l chunks</gradient> &8(score = entities, items, hoppers, redstone...)"));
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            ChunkInfo ci = list.get(i);
            int bx = ci.x * 16 + 8;
            int bz = ci.z * 16 + 8;
            String color = ci.score >= 400 ? "&#D9381E" : (ci.score >= 150 ? "&#FF8330" : "&#FF9147");
            StringBuilder what = new StringBuilder();
            what.append("&f").append(ci.entities).append(" &7entities");
            if (ci.items > 0) {
                what.append(" &8(&f").append(ci.items).append(" &7items&8)");
            }
            if (ci.hoppers > 0) {
                what.append(" &7· &f").append(ci.hoppers).append(" &7hoppers");
            }
            if (ci.redstone > 0) {
                what.append(" &7· &f").append(ci.redstone).append(" &7redstone");
            }
            if (ci.tiles - ci.hoppers - ci.redstone > 0) {
                what.append(" &7· &f").append(ci.tiles - ci.hoppers - ci.redstone).append(" &7other blocks");
            }
            String line = c("&8" + (i + 1) + ". " + color + (int) ci.score + " &8» &7" + ci.world.getName() + " &f" + bx + ", " + bz + " &8| " + what);
            if (s instanceof Player) {
                TextComponent tc = new TextComponent(TextComponent.fromLegacyText(line + " "));
                TextComponent tp = new TextComponent(TextComponent.fromLegacyText(c("&#B15CFF&l[TP]")));
                tp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lagfinder tp " + ci.world.getName() + " " + bx + " " + bz));
                tp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(c(
                        "&7Teleport to this chunk" + (ci.topEntity.isEmpty() ? "" : "\n&7Most common: &f" + ci.topEntity))))));
                tc.addExtra(tp);
                ((Player) s).spigot().sendMessage(tc);
            } else {
                s.sendMessage(line + (ci.topEntity.isEmpty() ? "" : " (" + ci.topEntity + ")"));
            }
        }
    }

    private void showEntities(CommandSender s) {
        Map<EntityType, Integer> count = new HashMap<>();
        int total = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!(e instanceof Player)) {
                    count.merge(e.getType(), 1, Integer::sum);
                    total++;
                }
            }
        }
        List<Map.Entry<EntityType, Integer>> list = new ArrayList<>(count.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        s.sendMessage(c("<gradient:#FF7518:#FFFFFF>&lEnti</gradient><gradient:#FFFFFF:#FF7518>&lties</gradient> &8(&f" + total + " &7total&8)"));
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            Map.Entry<EntityType, Integer> en = list.get(i);
            s.sendMessage(c("&8" + (i + 1) + ". &f" + pretty(en.getKey().name()) + " &8» &#FF8330" + en.getValue()));
        }
        s.sendMessage(c("&8Tip: &7/clearlag removes dropped items and (if enabled) hostile mobs."));
    }

    private void showWorlds(CommandSender s) {
        s.sendMessage(c("<gradient:#FF7518:#FFFFFF>&lWor</gradient><gradient:#FFFFFF:#FF7518>&llds</gradient>"));
        for (World w : Bukkit.getWorlds()) {
            int items = 0;
            int mobs = 0;
            for (Entity e : w.getEntities()) {
                if (e instanceof Item) {
                    items++;
                } else if (e instanceof org.bukkit.entity.LivingEntity && !(e instanceof Player)) {
                    mobs++;
                }
            }
            s.sendMessage(c("&f" + w.getName() + " &8» &7chunks &f" + w.getLoadedChunks().length + " &7· entities &f" + w.getEntities().size()
                    + " &7· mobs &f" + mobs + " &7· items &f" + items + " &7· players &f" + w.getPlayers().size()));
        }
    }

    private void teleport(CommandSender s, String[] args) {
        if (!(s instanceof Player)) {
            return;
        }
        if (args.length < 4) {
            s.sendMessage(c("<gradient:#FF7518:#FFFFFF>&lUsa</gradient><gradient:#FFFFFF:#FF7518>&lge</gradient> &8» &f/lagfinder tp <world> <x> <z>"));
            return;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) {
            s.sendMessage(c("&8» &#D9381E&lError! &7That world doesn't exist."));
            return;
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            s.sendMessage(c("&8» &#D9381E&lError! &7Bad coordinates."));
            return;
        }
        int y = w.getHighestBlockYAt(x, z) + 1;
        if (w.getEnvironment() == World.Environment.NETHER) {
            y = Math.min(y, 120); // don't land on the nether roof
        }
        Player p = (Player) s;
        p.teleport(new Location(w, x + 0.5, y, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch()));
        p.sendMessage(c("&8» <gradient:#FF7518:#FFFFFF>&lLag F</gradient><gradient:#FFFFFF:#FF7518>&linder</gradient> &8• &7Teleported to &f" + w.getName() + " " + x + ", " + y + ", " + z
                + "&7. Tip: &f/vanish &7first."));
    }

    // =====================================================================================
    // automatic TPS alerts
    // =====================================================================================

    private void watch() {
        FileConfiguration c = this.cfg();
        if (!c.getBoolean("lag-finder.alerts.enabled", true)) {
            return;
        }
        double threshold = c.getDouble("lag-finder.alerts.tps-below", 16.0);
        double tps = this.tps(15);
        if (tps >= threshold) {
            this.lowChecks = 0;
            return;
        }
        this.lowChecks++;
        int needed = Math.max(1, c.getInt("lag-finder.alerts.checks-in-a-row", 3)); // 3 x 15s = 45s of lag
        long cooldown = c.getLong("lag-finder.alerts.cooldown-minutes", 10L) * 60000L;
        if (this.lowChecks < needed || System.currentTimeMillis() - this.lastAlert < cooldown) {
            return;
        }
        this.lastAlert = System.currentTimeMillis();
        List<ChunkInfo> top = this.scan();
        String worst = top.isEmpty() ? "nothing obvious" : top.get(0).world.getName() + " " + (top.get(0).x * 16 + 8) + ", "
                + (top.get(0).z * 16 + 8) + " (" + (int) top.get(0).score + ")";

        TextComponent alert = new TextComponent(TextComponent.fromLegacyText(c("&8[<gradient:#FF7518:#FFFFFF>&lLA</gradient><gradient:#FFFFFF:#FF7518>&lG</gradient>&8] &7TPS is &#D9381E" + fmt(tps)
                + " &7· worst chunk: &f" + worst + " ")));
        TextComponent btn = new TextComponent(TextComponent.fromLegacyText(c("&#B15CFF&l[LAG FINDER]")));
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lagfinder"));
        alert.addExtra(btn);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("unstableffa.lagfinder.alerts")) {
                p.spigot().sendMessage(alert);
            }
        }
        this.plugin.getLogger().warning("[LagFinder] TPS dropped to " + fmt(tps) + " - worst chunk: " + worst);

        String url = c.getString("lag-finder.alerts.webhook-url", "");
        if (url != null && url.startsWith("http")) {
            String json = "{\"username\":\"Lag Finder\",\"embeds\":[{\"title\":\"⚠️ Low TPS: " + fmt(tps) + "\",\"color\":15158332,"
                    + "\"description\":\"Worst chunk: **" + worst.replace("\"", "'") + "**\\nPlayers online: " + Bukkit.getOnlinePlayers().size()
                    + "\\nUse `/lagfinder` in game.\"}]}";
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                try {
                    this.http.send(HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception ex) {
                    this.plugin.getLogger().warning("[LagFinder] Discord alert failed: " + ex.getMessage());
                }
            });
        }
    }

    List<String> tab(CommandSender s, String[] args) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("unstableffa.lagfinder")) {
            return out;
        }
        if (args.length == 1) {
            out.addAll(Arrays.asList("chunks", "entities", "world"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("chunks")) {
            out.addAll(Arrays.asList("10", "25", "50"));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
