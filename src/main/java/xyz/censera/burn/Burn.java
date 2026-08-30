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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class Burn extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "burn_hidden";
    private static final String DISPLAY_TAG = "burn_nametag";

    private Scoreboard scoreboard;
    private Team hiddenNameTeam;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        hiddenNameTeam = scoreboard.getTeam(TEAM_NAME);
        if (hiddenNameTeam == null) {
            hiddenNameTeam = scoreboard.registerNewTeam(TEAM_NAME);
        }
        hiddenNameTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        removeDisplayEntities();
        getServer().getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            applyStoredName(player);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeNameTag(player);
            if (hiddenNameTeam != null) {
                hiddenNameTeam.removeEntry(player.getName());
            }
        }

        if (hiddenNameTeam != null && hiddenNameTeam.getEntries().isEmpty()) {
            hiddenNameTeam.unregister();
        }
    }

    private void applyStoredName(Player player) {
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored == null) {
            return;
        }

        player.setDisplayName(stored);
        player.setPlayerListName(stored);
        setNameTag(player, stored);
    }

    private void setNameTag(Player player, String display) {
        hiddenNameTeam.addEntry(player.getName());
        removeNameTag(player);

        Location location = player.getLocation().clone().add(0, player.getHeight() + 0.35, 0);
        TextDisplay text = player.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.addScoreboardTag(DISPLAY_TAG);
            entity.text(toComponent(display));
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setDefaultBackground(false);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setLineWidth(256);
            entity.setViewRange(64.0f);
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(0);
            entity.setPersistent(false);
        });

        player.addPassenger(text);
    }

    private void removeNameTag(Player player) {
        for (Entity passenger : player.getPassengers()) {
            if (passenger.getScoreboardTags().contains(DISPLAY_TAG)) {
                player.removePassenger(passenger);
                passenger.remove();
            }
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
            applyStoredName(player);
            event.setJoinMessage(stored + " §ejoined the game.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored != null) {
            event.setQuitMessage(stored + " §eleft the game.");
        }
        removeNameTag(player);
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
