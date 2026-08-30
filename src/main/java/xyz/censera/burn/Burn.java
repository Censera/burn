package xyz.censera.burn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Burn extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "burn_hidden";
    private static final String DISPLAY_TAG = "burn_nametag";

    // Tag is shown when the viewer is within this distance (blocks).
    private static final double SHOW_DISTANCE = 16.0;
    // Tag is shown when the viewer's look direction is within this angle (degrees).
    private static final double SHOW_ANGLE = 35.0;

    private Scoreboard scoreboard;
    private Team hiddenNameTeam;
    private boolean floodgatePresent;

    // Tracks the TextDisplay entity for each online Java player.
    private final Map<UUID, TextDisplay> nameTags = new HashMap<>();

    // Per viewer: which tag owners are currently visible to them.
    // Used to diff state each tick and call show/hide only on changes.
    private final Map<UUID, Set<UUID>> visibleTo = new HashMap<>();

    // Repeating task that teleports displays and manages per-viewer visibility.
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        floodgatePresent = Bukkit.getPluginManager().getPlugin("floodgate") != null;

        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        hiddenNameTeam = scoreboard.getTeam(TEAM_NAME);
        if (hiddenNameTeam == null) {
            hiddenNameTeam = scoreboard.registerNewTeam(TEAM_NAME);
        }
        hiddenNameTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        removeDisplayEntities();
        getServer().getPluginManager().registerEvents(this, this);

        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 0L, 1L);

        for (Player player : Bukkit.getOnlinePlayers()) {
            applyStoredName(player);
        }
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            removeNameTag(player);
            if (hiddenNameTeam != null) {
                hiddenNameTeam.removeEntry(player.getName());
            }
        }

        if (hiddenNameTeam != null && hiddenNameTeam.getEntries().isEmpty()) {
            hiddenNameTeam.unregister();
        }

        nameTags.clear();
        visibleTo.clear();
    }

    // Called every tick. Teleports displays and updates per-viewer show/hide state.
    private void tick() {
        // Teleport each display to its owner's head.
        for (Map.Entry<UUID, TextDisplay> entry : nameTags.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            TextDisplay display = entry.getValue();
            if (owner == null || !owner.isOnline() || display == null || display.isDead()) {
                continue;
            }
            display.teleport(headLocation(owner));
        }

        // For each online Java viewer, decide which tags they should see.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (isBedrock(viewer)) {
                continue;
            }

            Set<UUID> currentlyVisible = visibleTo.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());

            for (Map.Entry<UUID, TextDisplay> entry : nameTags.entrySet()) {
                UUID ownerUuid = entry.getKey();
                TextDisplay display = entry.getValue();

                // Never show a player their own tag.
                if (ownerUuid.equals(viewer.getUniqueId())) {
                    continue;
                }

                Player owner = Bukkit.getPlayer(ownerUuid);
                if (owner == null || !owner.isOnline() || display == null || display.isDead()) {
                    continue;
                }

                boolean shouldShow = canSeeTag(viewer, owner);
                boolean isShown = currentlyVisible.contains(ownerUuid);

                if (shouldShow && !isShown) {
                    viewer.showEntity(this, display);
                    currentlyVisible.add(ownerUuid);
                } else if (!shouldShow && isShown) {
                    viewer.hideEntity(this, display);
                    currentlyVisible.remove(ownerUuid);
                }
            }
        }
    }

    // Returns true if viewer is close enough and roughly looking at owner.
    private boolean canSeeTag(Player viewer, Player owner) {
        Location vLoc = viewer.getEyeLocation();
        Location oLoc = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);

        if (!vLoc.getWorld().equals(oLoc.getWorld())) {
            return false;
        }

        double distance = vLoc.distance(oLoc);
        if (distance > SHOW_DISTANCE) {
            return false;
        }

        // Direction from viewer eye to the owner's center.
        Vector toOwner = oLoc.toVector().subtract(vLoc.toVector()).normalize();
        Vector lookDir = vLoc.getDirection().normalize();
        double dot = lookDir.dot(toOwner);

        // dot = cos(angle); convert SHOW_ANGLE threshold to cosine for comparison.
        double threshold = Math.cos(Math.toRadians(SHOW_ANGLE));
        return dot >= threshold;
    }

    private Location headLocation(Player player) {
        return player.getLocation().clone().add(0, player.getHeight() + 0.35, 0);
    }

    private void applyStoredName(Player player) {
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored == null) {
            return;
        }

        player.setDisplayName(stored);
        player.setPlayerListName(stored);

        // Bedrock players can't see TextDisplay entities — display name is enough.
        if (!isBedrock(player)) {
            setNameTag(player, stored);
        }
    }

    private void setNameTag(Player player, String display) {
        hiddenNameTeam.addEntry(player.getName());
        removeNameTag(player);

        TextDisplay text = player.getWorld().spawn(headLocation(player), TextDisplay.class, entity -> {
            entity.addScoreboardTag(DISPLAY_TAG);
            entity.text(toComponent(display));
            entity.setBillboard(TextDisplay.Billboard.VERTICAL);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setDefaultBackground(false);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setLineWidth(256);
            entity.setViewRange(64.0f);
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(0);
            entity.setPersistent(false);
            // Hidden from everyone by default; tick() handles per-viewer show/hide.
            entity.setVisibleByDefault(false);
        });

        nameTags.put(player.getUniqueId(), text);
    }

    private void removeNameTag(Player player) {
        // Clean up any leftover passenger-based entities from older versions.
        for (Entity passenger : player.getPassengers()) {
            if (passenger.getScoreboardTags().contains(DISPLAY_TAG)) {
                player.removePassenger(passenger);
                passenger.remove();
            }
        }

        TextDisplay existing = nameTags.remove(player.getUniqueId());
        if (existing != null && !existing.isDead()) {
            existing.remove();
        }

        // Clear this owner from every viewer's visible set.
        UUID ownerUuid = player.getUniqueId();
        for (Set<UUID> visible : visibleTo.values()) {
            visible.remove(ownerUuid);
        }
    }

    private void resetName(Player player) {
        player.setDisplayName(player.getName());
        player.setPlayerListName(player.getName());
        removeNameTag(player);

        if (hiddenNameTeam != null) {
            hiddenNameTeam.removeEntry(player.getName());
        }
    }

    private boolean isBedrock(Player player) {
        if (!floodgatePresent) {
            return false;
        }
        return org.geysermc.floodgate.api.FloodgateApi.getInstance()
                .isFloodgatePlayer(player.getUniqueId());
    }

    private Component toComponent(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private void removeDisplayEntities() {
        for (var world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getScoreboardTags().contains(DISPLAY_TAG)) {
                    display.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> applyStoredName(player), 1L);
            event.setJoinMessage(stored + " §ejoined the game.");
        }
        visibleTo.put(player.getUniqueId(), new HashSet<>());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored != null) {
            event.setQuitMessage(stored + " §eleft the game.");
        }
        removeNameTag(player);
        visibleTo.remove(player.getUniqueId());
        if (hiddenNameTeam != null) {
            hiddenNameTeam.removeEntry(player.getName());
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (!sender.isOp()) {
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§7/burn <player> <display|reset>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);
        UUID uuid;
        if (target != null) {
            uuid = target.getUniqueId();
        } else {
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage("§7Unknown player.");
                return true;
            }
            uuid = offline.getUniqueId();
        }

        String rest = String.join(" ", List.of(args).subList(1, args.length))
                .replaceAll("^\"|\"$", "");

        if (rest.equalsIgnoreCase("reset")) {
            getConfig().set("names." + uuid, null);
            saveConfig();
            if (target != null) {
                resetName(target);
            }
            sender.sendMessage("§7Reset.");
            return true;
        }

        String display = rest.replace("&", "§");
        getConfig().set("names." + uuid, display);
        saveConfig();

        if (target != null) {
            applyStoredName(target);
        }

        sender.sendMessage("§7Done.");
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (!sender.isOp()) {
            return List.of();
        }
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            return List.of("reset");
        }
        return List.of();
    }
}
