package com.xazymc.elementalsmp;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ElementalSMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private enum Element {
        FIRE(ChatColor.RED, "Fire", Material.BLAZE_POWDER),
        EARTH(ChatColor.GREEN, "Earth", Material.MOSSY_COBBLESTONE),
        WATER(ChatColor.AQUA, "Water", Material.HEART_OF_THE_SEA),
        AIR(ChatColor.WHITE, "Air", Material.FEATHER);
        final ChatColor color; final String label; final Material icon;
        Element(ChatColor color, String label, Material icon) { this.color = color; this.label = label; this.icon = icon; }
        static Element parse(String s) {
            for (Element e : values()) if (e.name().equalsIgnoreCase(s)) return e;
            return null;
        }
    }

    private final Map<UUID, Element> elements = new HashMap<>();
    private final Map<UUID, Integer> mastery = new HashMap<>();
    private final Map<UUID, Set<String>> learned = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> combos = new HashMap<>();
    private final Map<UUID, Long> lastComboHit = new HashMap<>();
    private final Map<UUID, Long> counterUntil = new HashMap<>();
    private final Map<UUID, Long> mistUntil = new HashMap<>();
    private Connection database;
    private NamespacedKey scrollKey;
    private NamespacedKey scrollElementKey;
    private NamespacedKey scrollTechniqueKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        scrollKey = new NamespacedKey(this, "elemental_scroll");
        scrollElementKey = new NamespacedKey(this, "scroll_element");
        scrollTechniqueKey = new NamespacedKey(this, "scroll_technique");
        if (!openDatabase()) {
            getLogger().severe("Could not open player database; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("element")).setExecutor(this);
        Objects.requireNonNull(getCommand("element")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("elementadmin")).setExecutor(this);
        Objects.requireNonNull(getCommand("elementadmin")).setTabCompleter(this);
        for (Player p : Bukkit.getOnlinePlayers()) loadPlayer(p);
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) tickPassive(p);
            }
        }.runTaskTimer(this, 20L, 20L);
        getLogger().info("Elemental SMP enabled.");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) savePlayer(p);
        try { if (database != null && !database.isClosed()) database.close(); }
        catch (SQLException e) { getLogger().warning("Database close failed: " + e.getMessage()); }
    }

    private boolean openDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new SQLException("Could not create plugin folder");
            Class.forName("org.sqlite.JDBC");
            database = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "players.db").getAbsolutePath());
            try (Statement s = database.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, element TEXT NOT NULL, mastery INTEGER NOT NULL DEFAULT 0, learned TEXT NOT NULL DEFAULT '')");
            }
            return true;
        } catch (Exception e) {
            getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    private synchronized void loadPlayer(Player p) {
        UUID id = p.getUniqueId();
        try (PreparedStatement ps = database.prepareStatement("SELECT element, mastery, learned FROM players WHERE uuid=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Element e = Element.parse(rs.getString("element"));
                    if (e != null) elements.put(id, e);
                    mastery.put(id, Math.max(0, rs.getInt("mastery")));
                    Set<String> set = new HashSet<>();
                    String raw = rs.getString("learned");
                    if (raw != null && !raw.isBlank()) set.addAll(Arrays.asList(raw.split(",")));
                    learned.put(id, set);
                } else if (getConfig().getBoolean("assignment.enabled", true)) {
                    Element[] all = Element.values();
                    Element chosen = all[ThreadLocalRandom.current().nextInt(all.length)];
                    elements.put(id, chosen); mastery.put(id, 0); learned.put(id, new HashSet<>());
                    savePlayer(p);
                    msg(p, "Your element has been assigned: " + chosen.color + chosen.label + ChatColor.GRAY + ". Use /element to learn more.");
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.3f);
                }
            }
        } catch (SQLException e) { getLogger().warning("Could not load " + p.getName() + ": " + e.getMessage()); }
        if (elements.get(id) == Element.AIR) p.setAllowFlight(true);
    }

    private synchronized void savePlayer(Player p) {
        UUID id = p.getUniqueId();
        Element element = elements.get(id);
        if (element == null || database == null) return;
        String scrolls = String.join(",", learned.getOrDefault(id, Set.of()));
        try (PreparedStatement ps = database.prepareStatement(
                "INSERT INTO players(uuid,element,mastery,learned) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET element=excluded.element, mastery=excluded.mastery, learned=excluded.learned")) {
            ps.setString(1, id.toString()); ps.setString(2, element.name());
            ps.setInt(3, mastery.getOrDefault(id, 0)); ps.setString(4, scrolls); ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning("Could not save " + p.getName() + ": " + e.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        loadPlayer(e.getPlayer());
        if (elements.get(e.getPlayer().getUniqueId()) == Element.AIR) e.getPlayer().setAllowFlight(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        savePlayer(e.getPlayer());
        UUID id = e.getPlayer().getUniqueId();
        cooldowns.remove(id); combos.remove(id); lastComboHit.remove(id); counterUntil.remove(id); mistUntil.remove(id);
    }

    private void tickPassive(Player p) {
        Element e = elements.get(p.getUniqueId());
        if (e == null) return;
        if (e == Element.EARTH && getConfig().getBoolean("passives.earth-mining-speed", true) && p.isOnGround()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 45,
                    Math.max(0, getConfig().getInt("passives.earth-mining-levels", 1) - 1), true, false, false));
        } else if (e == Element.WATER && getConfig().getBoolean("passives.water-swim-speed", true) && p.isSwimming()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 45, 0, true, false, false));
        } else if (e == Element.AIR && getConfig().getBoolean("passives.air-double-jump", true)) {
            if (p.isOnGround() && !p.isFlying()) p.setAllowFlight(true);
        }
    }

    @EventHandler public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (elements.get(p.getUniqueId()) != Element.AIR || !getConfig().getBoolean("passives.air-double-jump", true)) return;
        event.setCancelled(true);
        p.setFlying(false);
        String key = "air-double-jump";
        if (!ready(p, key, getConfig().getInt("passives.air-double-jump-cooldown-seconds", 2))) return;
        Vector v = p.getLocation().getDirection().multiply(0.75);
        v.setY(0.78);
        p.setVelocity(v);
        p.setAllowFlight(false);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 18, 0.35, 0.15, 0.35, 0.04);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f);
        putCooldown(p, key, getConfig().getInt("passives.air-double-jump-cooldown-seconds", 2));
    }

    @EventHandler public void onFall(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.FALL
                && elements.get(p.getUniqueId()) == Element.AIR) event.setDamage(event.getDamage() * 0.5);
    }

    @EventHandler public void onDrops(BlockDropItemEvent event) {
        Player p = event.getPlayer();
        if (elements.get(p.getUniqueId()) != Element.FIRE || !getConfig().getBoolean("passives.fire-auto-smelt", true)) return;
        for (org.bukkit.entity.Item entity : event.getItems()) {
            ItemStack stack = entity.getItemStack();
            Material result = switch (stack.getType()) {
                case RAW_IRON -> Material.IRON_INGOT;
                case RAW_GOLD -> Material.GOLD_INGOT;
                case RAW_COPPER -> Material.COPPER_INGOT;
                case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
                case COBBLESTONE -> Material.STONE;
                case SAND -> Material.GLASS;
                case CLAY_BALL -> Material.BRICK;
                default -> null;
            };
            if (result != null) {
                stack.setType(result);
                entity.setItemStack(stack);
                p.spawnParticle(Particle.FLAME, event.getBlock().getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (!event.getPlayer().isSneaking()) {
            if (event.getAction().isRightClick() && isScroll(event.getItem())) {
                event.setCancelled(true); learnScroll(event.getPlayer(), event.getItem()); return;
            }
            return;
        }
        if (!event.getAction().isRightClick()) return;
        Player p = event.getPlayer();
        Element element = elements.get(p.getUniqueId());
        if (element == null) return;
        event.setCancelled(true);
        if (!getConfig().getBoolean("combat.abilities-enabled", true)) return;
        int slot = p.getInventory().getHeldItemSlot();
        String ability = abilityFor(element, slot);
        if (ability == null) { msg(p, "No ability mapped to this hotbar slot."); return; }
        cast(p, element, ability);
    }

    private String abilityFor(Element e, int slot) {
        return switch (slot) {
            case 0 -> switch (e) { case FIRE -> "ember-shot"; case EARTH -> "stone-shot"; case WATER -> "water-whip"; case AIR -> "gust-shot"; };
            case 1 -> switch (e) { case FIRE -> "flame-dash"; case EARTH -> "fault-line"; case WATER -> "undertow"; case AIR -> "air-dash"; };
            case 2 -> switch (e) { case FIRE -> "fire-sweep"; case EARTH -> "raise-wall"; case WATER -> "ice-step"; case AIR -> "cyclone-sweep"; };
            case 3 -> switch (e) { case FIRE -> "blazing-counter"; case EARTH -> "grounded-stance"; case WATER -> "mist-veil"; case AIR -> "deflect"; };
            case 4 -> "ultimate";
            default -> null;
        };
    }

    private void cast(Player p, Element e, String ability) {
        if (ability.equals("ultimate") && !learned.getOrDefault(p.getUniqueId(), Set.of()).contains(e.name().toLowerCase() + "_ultimate")) {
            msg(p, "You need to discover your element's Ultimate Scroll first."); return;
        }
        int seconds = getConfig().getInt("cooldowns." + ability, ability.equals("ultimate") ? 60 : 8);
        if (!ready(p, ability, seconds)) {
            long left = remaining(p, ability);
            msg(p, "That technique is recharging for " + left + "s."); return;
        }
        Location loc = p.getLocation();
        World w = p.getWorld();
        Vector dir = loc.getDirection().normalize();
        switch (ability) {
            case "ember-shot" -> projectile(p, e, dir, 3.0, 1.65, Particle.FLAME, Color.ORANGE);
            case "stone-shot" -> projectile(p, e, dir, 3.0, 1.45, Particle.BLOCK, Color.GRAY);
            case "water-whip" -> projectile(p, e, dir, 3.0, 1.6, Particle.SPLASH, Color.AQUA);
            case "gust-shot" -> projectile(p, e, dir, 2.0, 1.5, Particle.CLOUD, Color.WHITE);
            case "flame-dash", "air-dash" -> {
                Vector dash = dir.multiply(1.25); dash.setY(Math.max(0.18, dash.getY() + 0.12));
                p.setVelocity(dash);
                w.spawnParticle(e == Element.FIRE ? Particle.FLAME : Particle.CLOUD, loc, 24, .35, .3, .35, .03);
                damageNearby(p, loc, 2.4, ability.equals("flame-dash") ? 2.0 : 0.0, ability.equals("flame-dash") ? 0.35 : 0.0);
            }
            case "fire-sweep" -> { arc(p, 4.0, 3.0); w.spawnParticle(Particle.SWEEP_ATTACK, loc.add(dir.clone().multiply(1.1)), 5, .4, .3, .4, 0); }
            case "fault-line" -> {
                w.spawnParticle(Particle.BLOCK, loc, 30, 1.3, .15, 1.3, 0.15, Bukkit.createBlockData(Material.STONE));
                damageNearby(p, loc, 4.0, 3.0, 0.75);
            }
            case "raise-wall" -> {
                for (int y = 0; y < 3; y++) for (int x = -1; x <= 1; x++) {
                    Location particleLoc = loc.clone().add(dir.getX() * 2 + x, y, dir.getZ() * 2);
                    w.spawnParticle(Particle.BLOCK, particleLoc, 5, .1, .1, .1, .02, Bukkit.createBlockData(Material.COBBLESTONE));
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 0, true, true));
            }
            case "undertow" -> {
                LivingEntity target = target(p, 7, 1.5);
                if (target == null) { msg(p, "No target in range."); return; }
                Vector pull = p.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(.75);
                pull.setY(.18); target.setVelocity(pull); target.getWorld().spawnParticle(Particle.SPLASH, target.getLocation(), 20, .4, .4, .4, .1);
                target.damage(1.5, p);
            }
            case "ice-step" -> {
                p.setVelocity(dir.multiply(1.0).setY(Math.max(0.1, dir.getY() * .4)));
                w.spawnParticle(Particle.SNOWFLAKE, loc, 25, .45, .15, .45, .02);
                for (Entity entity : p.getNearbyEntities(2.2, 1.0, 2.2)) if (entity instanceof LivingEntity target && !entity.equals(p)) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, true, true));
            }
            case "blazing-counter" -> {
                counterUntil.put(p.getUniqueId(), System.currentTimeMillis() + 1200);
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation(), 18, .4, .5, .4, .03);
                msg(p, "Counter stance active for 1.2 seconds.");
            }
            case "grounded-stance" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 0, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, true));
                p.getWorld().spawnParticle(Particle.BLOCK, loc, 20, .5, .1, .5, .03, Bukkit.createBlockData(Material.DEEPSLATE));
            }
            case "mist-veil" -> {
                mistUntil.put(p.getUniqueId(), System.currentTimeMillis() + 2500);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 35, 0, true, false, false));
                p.getWorld().spawnParticle(Particle.CLOUD, loc, 35, .8, .5, .8, .04);
            }
            case "deflect" -> {
                counterUntil.put(p.getUniqueId(), System.currentTimeMillis() + 1000);
                p.getWorld().spawnParticle(Particle.CLOUD, loc, 24, .5, .5, .5, .06);
                msg(p, "Deflection stance active for 1 second.");
            }
            case "cyclone-sweep" -> {
                w.spawnParticle(Particle.SWEEP_ATTACK, loc, 10, .5, .4, .5, 0);
                damageNearby(p, loc, 3.0, 2.0, .55);
            }
            case "ultimate" -> ultimate(p, e, dir);
            default -> { return; }
        }
        if (!ability.equals("undertow") || target(p, 7, 1.5) != null) {
            putCooldown(p, ability, seconds);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, .75f, e == Element.FIRE ? 1.3f : 1.0f);
        }
    }

    private void projectile(Player p, Element element, Vector direction, double damage, double speed, Particle particle, Color ignored) {
        Snowball ball = p.launchProjectile(Snowball.class);
        ball.setVelocity(direction.multiply(speed));
        ball.setShooter(p);
        ball.setCustomName("elemental:" + element.name() + ":" + damage);
        ball.setCustomNameVisible(false);
        ball.getPersistentDataContainer().set(new NamespacedKey(this, "elemental_projectile"), PersistentDataType.STRING, element.name() + ":" + damage);
        ball.setGravity(false);
        ball.getWorld().spawnParticle(particle, ball.getLocation(), 8, .12, .12, .12, .02);
        new BukkitRunnable() {
            @Override public void run() {
                if (!ball.isValid() || ball.isDead()) { cancel(); return; }
                ball.getWorld().spawnParticle(particle, ball.getLocation(), 3, .06, .06, .06, .01);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) return;
        String data = ball.getPersistentDataContainer().get(new NamespacedKey(this, "elemental_projectile"), PersistentDataType.STRING);
        if (data == null) return;
        String[] parts = data.split(":");
        if (!(ball.getShooter() instanceof Player shooter)) return;
        double damage;
        try { damage = Double.parseDouble(parts[1]); } catch (Exception ex) { damage = 2.0; }
        if (event.getHitEntity() instanceof LivingEntity target && !target.equals(shooter)) {
            target.damage(damage, shooter);
            if (parts[0].equals("FIRE")) target.setFireTicks(30);
            if (parts[0].equals("WATER")) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 0, true, true));
            if (parts[0].equals("AIR")) target.setVelocity(target.getVelocity().add(shooter.getLocation().getDirection().multiply(.25)));
            if (parts[0].equals("EARTH")) target.setVelocity(target.getVelocity().add(new Vector(0, .25, 0)));
        }
        ball.remove();
    }

    private void ultimate(Player p, Element e, Vector dir) {
        Location loc = p.getLocation();
        World w = p.getWorld();
        switch (e) {
            case FIRE -> {
                w.spawnParticle(Particle.EXPLOSION, loc, 4, .4, .4, .4, 0);
                damageNearby(p, loc, 5.0, 6.0, .9);
                for (Entity en : p.getNearbyEntities(5, 3, 5)) if (en instanceof LivingEntity le && !en.equals(p)) le.setFireTicks(50);
            }
            case EARTH -> {
                LivingEntity target = target(p, 8, 1.8);
                if (target == null) { msg(p, "No target in range."); return; }
                Location centre = target.getLocation();
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 4, true, true));
                target.damage(4.0, p);
                for (int y = 0; y < 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                    Location at = centre.clone().add(x, y, z);
                    w.spawnParticle(Particle.BLOCK, at, 4, .1, .1, .1, .01, Bukkit.createBlockData(Material.STONE));
                }
            }
            case WATER -> {
                w.spawnParticle(Particle.SPLASH, loc, 90, 3, 1.5, 3, .15);
                for (Entity en : p.getNearbyEntities(6, 3, 6)) if (en instanceof LivingEntity le && !en.equals(p)) {
                    Vector pull = loc.toVector().subtract(le.getLocation().toVector()).normalize().multiply(.45);
                    le.setVelocity(le.getVelocity().add(pull)); le.damage(2.0, p);
                }
            }
            case AIR -> {
                w.spawnParticle(Particle.CLOUD, loc, 100, 3, 1.5, 3, .12);
                for (Entity en : p.getNearbyEntities(5, 3, 5)) if (en instanceof LivingEntity le && !en.equals(p)) {
                    Vector push = le.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.0);
                    push.setY(.65); le.setVelocity(push);
                }
                Vector leap = dir.multiply(1.0); leap.setY(.9); p.setVelocity(leap);
            }
        }
        msg(p, e.color + e.label + " ultimate unleashed!");
    }

    private void arc(Player p, double range, double damage) {
        Vector dir = p.getLocation().getDirection().normalize();
        for (Entity entity : p.getNearbyEntities(range, 2, range)) if (entity instanceof LivingEntity target && !entity.equals(p)) {
            Vector to = target.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
            if (to.dot(dir) > .25) target.damage(damage, p);
        }
    }

    private void damageNearby(Player p, Location centre, double radius, double damage, double knockback) {
        for (Entity entity : p.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity target && !entity.equals(p) && target.getLocation().distanceSquared(centre) <= radius * radius) {
                if (damage > 0) target.damage(damage, p);
                if (knockback > 0) {
                    Vector v = target.getLocation().toVector().subtract(p.getLocation().toVector()).normalize().multiply(knockback);
                    v.setY(Math.max(.15, v.getY())); target.setVelocity(target.getVelocity().add(v));
                }
            }
        }
    }

    private LivingEntity target(Player p, double range, double dotThreshold) {
        LivingEntity best = null; double bestDistance = range * range;
        Vector direction = p.getLocation().getDirection().normalize();
        for (Entity entity : p.getNearbyEntities(range, range, range)) if (entity instanceof LivingEntity le && !le.equals(p)) {
            Vector to = le.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
            double distance = to.length();
            if (distance > 0 && distance * distance < bestDistance && direction.dot(to.normalize()) > 0.82 && p.hasLineOfSight(le)) {
                best = le; bestDistance = distance * distance;
            }
        }
        return best;
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) attacker = p;
        if (attacker != null) {
            UUID id = attacker.getUniqueId();
            long now = System.currentTimeMillis();
            if (now - lastComboHit.getOrDefault(id, 0L) <= getConfig().getInt("combat.combo-window-seconds", 3) * 1000L) combos.put(id, combos.getOrDefault(id, 0) + 1);
            else combos.put(id, 1);
            lastComboHit.put(id, now);
            int count = combos.getOrDefault(id, 1);
            attacker.sendActionBar(ChatColor.AQUA + "Combo " + count);
            int threshold = Math.max(1, getConfig().getInt("combat.combo-mastery-every", 5));
            if (count > 0 && count % threshold == 0) {
                mastery.put(id, mastery.getOrDefault(id, 0) + 1);
                attacker.sendMessage(prefix() + ChatColor.GREEN + "Combo milestone! +1 mastery.");
                savePlayer(attacker);
            }
        }
        if (event.getEntity() instanceof Player victim) {
            long now = System.currentTimeMillis();
            if (counterUntil.getOrDefault(victim.getUniqueId(), 0L) > now && attacker != null) {
                event.setDamage(event.getDamage() * .5);
                attacker.damage(1.5, victim);
                victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation(), 14, .4, .4, .4, .02);
                counterUntil.remove(victim.getUniqueId());
            }
            if (mistUntil.getOrDefault(victim.getUniqueId(), 0L) > now && attacker != null) {
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, true, true));
            }
        }
    }

    private boolean ready(Player p, String key, int seconds) { return remaining(p, key) <= 0; }
    private long remaining(Player p, String key) {
        long until = cooldowns.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(key, 0L);
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }
    private void putCooldown(Player p, String key, int seconds) {
        cooldowns.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>()).put(key, System.currentTimeMillis() + seconds * 1000L);
    }
    private String prefix() { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&8[&bElemental&8] &r")); }
    private void msg(CommandSender sender, String text) { sender.sendMessage(prefix() + ChatColor.translateAlternateColorCodes('&', text)); }
    private boolean isScroll(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(scrollKey, PersistentDataType.BYTE);
    }
    private ItemStack makeScroll(Element element, boolean ultimate) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String tech = ultimate ? "ultimate" : "technique";
        meta.setDisplayName(element.color + element.label + (ultimate ? " Ultimate Scroll" : " Technique Scroll"));
        meta.setLore(List.of(ChatColor.GRAY + "A discovered elemental technique.", ChatColor.YELLOW + "Right-click to learn"));
        meta.getPersistentDataContainer().set(scrollKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(scrollElementKey, PersistentDataType.STRING, element.name());
        meta.getPersistentDataContainer().set(scrollTechniqueKey, PersistentDataType.STRING, tech);
        item.setItemMeta(meta);
        return item;
    }
    private void learnScroll(Player p, ItemStack item) {
        if (!getConfig().getBoolean("scrolls.enabled", true)) { msg(p, "Scrolls are disabled."); return; }
        ItemMeta meta = item.getItemMeta();
        String elementName = meta.getPersistentDataContainer().get(scrollElementKey, PersistentDataType.STRING);
        String technique = meta.getPersistentDataContainer().get(scrollTechniqueKey, PersistentDataType.STRING);
        Element element = elementName == null ? null : Element.parse(elementName);
        if (element == null || technique == null) { msg(p, "This scroll is damaged."); return; }
        if (elements.get(p.getUniqueId()) != element) { msg(p, "This scroll belongs to " + element.color + element.label + ChatColor.GRAY + ". It cannot teach your element."); return; }
        String id = element.name().toLowerCase() + (technique.equals("ultimate") ? "_ultimate" : "_technique");
        Set<String> set = learned.computeIfAbsent(p.getUniqueId(), ignored -> new HashSet<>());
        if (!set.add(id)) {
            mastery.put(p.getUniqueId(), mastery.getOrDefault(p.getUniqueId(), 0) + getConfig().getInt("scrolls.duplicate-mastery", 10));
            msg(p, "Duplicate scroll converted into mastery.");
        } else {
            msg(p, "You learned a " + element.color + element.label + ChatColor.GREEN + " " + technique + " scroll!");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
        if (item.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else item.setAmount(item.getAmount() - 1);
        savePlayer(p);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("element")) {
            if (!(sender instanceof Player p)) { msg(sender, "This command is for players."); return true; }
            Element e = elements.get(p.getUniqueId());
            if (e == null) { msg(p, "Your element has not been assigned yet."); return true; }
            String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "skills" -> {
                    msg(p, e.color + e.label + ChatColor.GRAY + " abilities (sneak + right-click with hotbar slots 1-5):");
                    msg(p, "1: " + abilityFor(e, 0) + " | 2: " + abilityFor(e, 1) + " | 3: " + abilityFor(e, 2));
                    msg(p, "4: " + abilityFor(e, 3) + " | 5: Ultimate (requires Ultimate Scroll)");
                }
                case "mastery" -> msg(p, "Mastery: " + mastery.getOrDefault(p.getUniqueId(), 0));
                case "scrolls" -> msg(p, "Learn techniques by right-clicking a matching scroll. Admins can issue one with /elementadmin scroll.");
                default -> {
                    msg(p, "Your element: " + e.color + e.label);
                    msg(p, "Passive: " + switch (e) {
                        case FIRE -> "auto-smelt eligible drops";
                        case EARTH -> "faster mining while grounded";
                        case WATER -> "faster swimming";
                        case AIR -> "double jump and reduced fall damage";
                    });
                    msg(p, "Use /element skills, /element mastery, /element scrolls.");
                }
            }
            return true;
        }
        if (!sender.hasPermission("elementalsmp.admin")) { msg(sender, "You don't have permission."); return true; }
        if (args.length == 0) { msg(sender, "Usage: /elementadmin <assign|reset|scroll|reload>"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> { reloadConfig(); msg(sender, "Configuration reloaded."); }
            case "assign" -> {
                if (args.length < 3) { msg(sender, "/elementadmin assign <player> <fire|earth|water|air>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]); Element e = Element.parse(args[2]);
                if (target == null || e == null) { msg(sender, "Player must be online and element must be fire, earth, water or air."); return true; }
                elements.put(target.getUniqueId(), e); mastery.putIfAbsent(target.getUniqueId(), 0); learned.putIfAbsent(target.getUniqueId(), new HashSet<>());
                target.setAllowFlight(e == Element.AIR); savePlayer(target); msg(sender, "Assigned " + e.label + " to " + target.getName()); msg(target, "Your element is now " + e.color + e.label);
            }
            case "reset" -> {
                if (args.length < 2) { msg(sender, "/elementadmin reset <player>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { msg(sender, "Player must be online."); return true; }
                elements.remove(target.getUniqueId()); mastery.remove(target.getUniqueId()); learned.remove(target.getUniqueId());
                try (PreparedStatement ps = database.prepareStatement("DELETE FROM players WHERE uuid=?")) { ps.setString(1, target.getUniqueId().toString()); ps.executeUpdate(); }
                catch (SQLException ex) { msg(sender, "Database error: " + ex.getMessage()); return true; }
                loadPlayer(target); msg(sender, "Reset progression for " + target.getName());
            }
            case "scroll" -> {
                if (args.length < 3) { msg(sender, "/elementadmin scroll <player> <element|ultimate>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { msg(sender, "Player must be online."); return true; }
                Element e = elements.get(target.getUniqueId());
                if (e == null) { msg(sender, "Target has no element."); return true; }
                boolean ultimate = args[2].equalsIgnoreCase("ultimate");
                if (!ultimate && !args[2].equalsIgnoreCase("element") && !args[2].equalsIgnoreCase("technique")) { msg(sender, "Use element, technique or ultimate."); return true; }
                target.getInventory().addItem(makeScroll(e, ultimate));
                msg(sender, "Gave " + (ultimate ? "ultimate" : "technique") + " scroll to " + target.getName());
            }
            default -> msg(sender, "Usage: /elementadmin <assign|reset|scroll|reload>");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("element")) return args.length == 1 ? List.of("skills", "mastery", "scrolls").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        if (!sender.hasPermission("elementalsmp.admin")) return List.of();
        if (args.length == 1) return List.of("assign", "reset", "scroll", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("assign")) return List.of("fire", "earth", "water", "air").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("scroll")) return List.of("element", "technique", "ultimate").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}