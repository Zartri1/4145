package org;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * A slightly bigger hitbox for ender pearls and wind charges that players throw, so they hit a bit easier.
 * Vanilla already counts a projectile as 0.3 blocks wide around a player; this adds a little on top.
 * When a flying pearl or charge passes within that extra reach of a player, it is moved into the player so
 * the normal hit happens (damage, teleport, burst) exactly like a real hit.
 *
 * Settings: combat.yml -> projectile-hitbox   (enabled: false = vanilla, /ze reload applies it)
 * Works on its own: it only needs the plugin's config.
 */
final class ProjectileHitbox implements Listener {

    private static final double VANILLA_MARGIN = 0.3;

    private final zenithEssentials plugin;
    private final Set<Projectile> tracked = new HashSet<>();
    private BukkitTask task;

    ProjectileHitbox(zenithEssentials plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration c() {
        return this.plugin.getConfig();
    }

    private double extraFor(Projectile pr, FileConfiguration cfg) {
        if (pr instanceof EnderPearl) {
            return cfg.getDouble("projectile-hitbox.ender-pearl", 0.15);
        }
        if (pr instanceof WindCharge) {
            return cfg.getDouble("projectile-hitbox.wind-charge", 0.15);
        }
        return 0.0;
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr instanceof EnderPearl) && !(pr instanceof WindCharge)) {
            return;
        }
        if (!(pr.getShooter() instanceof Player)) {
            return;
        }
        FileConfiguration cfg = this.c();
        if (!cfg.getBoolean("projectile-hitbox.enabled", true) || this.extraFor(pr, cfg) <= 0.0) {
            return;
        }
        if (cfg.getStringList("projectile-hitbox.disabled-worlds").contains(pr.getWorld().getName())) {
            return;
        }
        this.tracked.add(pr);
        if (this.task == null) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
        }
    }

    private void tick() {
        if (this.tracked.isEmpty()) {
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }
            return;
        }
        FileConfiguration cfg = this.c();
        if (!cfg.getBoolean("projectile-hitbox.enabled", true)) {
            this.tracked.clear();
            return;
        }
        final boolean playersOnly = cfg.getBoolean("projectile-hitbox.players-only", true);
        Iterator<Projectile> it = this.tracked.iterator();
        while (it.hasNext()) {
            Projectile pr = it.next();
            if (!pr.isValid() || pr.isDead() || !(pr.getShooter() instanceof Player)) {
                it.remove();
                continue;
            }
            double extra = this.extraFor(pr, cfg);
            Vector vel = pr.getVelocity();
            double len = vel.length();
            if (extra <= 0.0 || len < 0.01) {
                continue;
            }
            final Player shooter = (Player) pr.getShooter();
            World w = pr.getWorld();
            Location now = pr.getLocation();
            Vector dir = vel.clone().multiply(1.0 / len);

            Predicate<Entity> filter = en -> en instanceof LivingEntity
                    && !(en instanceof ArmorStand)
                    && !en.equals(shooter)
                    && (!playersOnly || en instanceof Player)
                    && !(en instanceof Player && ((Player) en).getGameMode() == GameMode.SPECTATOR);
            RayTraceResult hit = w.rayTraceEntities(now, dir, len, VANILLA_MARGIN + extra, filter);
            if (hit == null || hit.getHitEntity() == null || hit.getHitPosition() == null) {
                continue;
            }
            // a block in front of the player blocks the hit
            RayTraceResult block = w.rayTraceBlocks(now, dir, len, FluidCollisionMode.NEVER, true);
            if (block != null && block.getHitPosition() != null
                    && block.getHitPosition().distanceSquared(now.toVector()) < hit.getHitPosition().distanceSquared(now.toVector())) {
                continue;
            }
            // move it into the player: the normal hit then happens in the same tick
            Entity target = hit.getHitEntity();
            Location to = target.getBoundingBox().getCenter().toLocation(w);
            to.setDirection(dir);
            pr.teleport(to);
            pr.setVelocity(vel);
        }
    }
}
