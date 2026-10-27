package me.punisher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.*;
import java.util.*;

public class PunisherPlugin extends JavaPlugin implements Listener {
    static final double DASH = 1.6, RADIUS = 8, PUSH = 2.0, LIFT = 0.5, RANGE = 20;
    static final int BIND_TICKS = 100;
    static final long DASH_CD = 3000, CRUMBLE_CD = 10000, BIND_CD = 12000;

    NamespacedKey key;
    final Map<String, Long> cds = new HashMap<>();
    final Set<UUID> bound = new HashSet<>();

    @Override public void onEnable() {
        key = new NamespacedKey(this, "the_punisher");
        getServer().getPluginManager().registerEvents(this, this);
    }

    boolean isPunisher(ItemStack i) {
        return i != null && i.getType() == Material.MACE && i.hasItemMeta()
                && i.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) return true;
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("The Punisher", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Right Click: Dash", NamedTextColor.WHITE),
                Component.text("G: Crumble", NamedTextColor.RED),
                Component.text("Shift + Right Click: Bind", NamedTextColor.DARK_PURPLE)));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(m);
        p.getInventory().addItem(item);
        return true;
    }

    boolean onCd(Player p, String name, long ms) {
        String k = p.getUniqueId() + name;
        long now = System.currentTimeMillis();
        Long until = cds.get(k);
        if (until != null && until > now) {
            p.sendActionBar(Component.text(name + ": " + String.format("%.1f", (until - now) / 1000.0) + "s", NamedTextColor.GRAY));
            return true;
        }
        cds.put(k, now + ms);
        return false;
    }

    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (!isPunisher(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (p.isSneaking()) bind(p); else dash(p);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (!isPunisher(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        crumble(e.getPlayer());
    }

    void puff(Location l) {
        l.getWorld().spawnParticle(Particle.POOF, l.clone().add(0, .2, 0), 25, .35, .1, .35, .05);
        l.getWorld().spawnParticle(Particle.CLOUD, l.clone().add(0, .2, 0), 8, .3, .1, .3, .02);
    }

    void dash(Player p) {
        if (onCd(p, "Dash", DASH_CD)) return;
        puff(p.getLocation());
        Vector d = p.getLocation().getDirection().normalize();
        d.setY(Math.max(d.getY(), .15));
        p.setVelocity(d.normalize().multiply(DASH));
        p.setFallDistance(0);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, .7f);
        new BukkitRunnable() { int t = 0; public void run() {
            if (!p.isOnline() || t++ >= 6) { cancel(); return; }
            puff(p.getLocation());
        }}.runTaskTimer(this, 1, 1);
    }

    void crumble(Player p) {
        if (onCd(p, "Crumble", CRUMBLE_CD)) return;
        World w = p.getWorld();
        Location c = p.getLocation();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 1);
        w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 30, RADIUS / 3, .6, RADIUS / 3, .1);
        w.spawnParticle(Particle.LARGE_SMOKE, c.clone().add(0, .5, 0), 40, 2, .5, 2, .05);
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            w.spawnParticle(Particle.EXPLOSION, c.clone().add(Math.cos(a) * 4, .3, Math.sin(a) * 4), 1);
        }
        w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 2f, .8f);
        for (Entity e : p.getNearbyEntities(RADIUS, RADIUS, RADIUS)) {
            if (!(e instanceof LivingEntity t)) continue;
            Vector v = t.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (v.lengthSquared() < 1e-4) v = p.getLocation().getDirection().setY(0);
            t.setVelocity(v.normalize().multiply(PUSH).setY(LIFT));
        }
    }

    void bind(Player p) {
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), RANGE, .6,
                e -> e instanceof Player && !e.equals(p));
        if (r == null || !(r.getHitEntity() instanceof Player t)) { p.sendActionBar(Component.text("No player in sight.", NamedTextColor.GRAY)); return; }
        if (bound.contains(t.getUniqueId()) || onCd(p, "Bind", BIND_CD)) return;
        bound.add(t.getUniqueId());
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1f, .6f);
        Particle.DustOptions black = new Particle.DustOptions(Color.BLACK, 1.6f);
        new BukkitRunnable() { int t0 = 0; public void run() {
            if (!t.isOnline() || t0 >= BIND_TICKS) { bound.remove(t.getUniqueId()); cancel(); return; }
            t.setVelocity(new Vector());
            Location f = t.getLocation().add(0, .1, 0);
            World w = t.getWorld();
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12 + t0 * .3;
                w.spawnParticle(Particle.DUST, f.clone().add(Math.cos(a) * .8, 0, Math.sin(a) * .8), 1, 0, 0, 0, 0, black);
            }
            w.spawnParticle(Particle.DUST, f, 6, .4, .05, .4, 0, black);
            w.spawnParticle(Particle.SQUID_INK, f, 2, .4, .05, .4, .01);
            t0 += 2;
        }}.runTaskTimer(this, 0, 2);
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!bound.contains(e.getPlayer().getUniqueId()) || e.getTo() == null) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            Location l = f.clone();
            l.setYaw(t.getYaw()); l.setPitch(t.getPitch());
            e.setTo(l);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { bound.remove(e.getPlayer().getUniqueId()); }
}
