package xyz.censera.burn;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class Burn extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void applyName(Player player, String display) {
        player.setDisplayName(display);
        player.setPlayerListName(display);

        PlayerProfile profile = Bukkit.createProfileExact(player.getUniqueId(), display.replace("§", ""));
        player.setPlayerProfile(profile);
    }

    private void resetName(Player player) {
        player.setDisplayName(player.getName());
        player.setPlayerListName(player.getName());

        PlayerProfile profile = Bukkit.createProfileExact(player.getUniqueId(), player.getName());
        player.setPlayerProfile(profile);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored != null) {
            applyName(player, stored);
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
        String profileName = display.replaceAll("§[0-9a-fk-or]", "");
        if (profileName.length() > 16 || !profileName.matches("[A-Za-z0-9_]*")) {
            sender.sendMessage("§7Display name must be 1-16 characters using letters, numbers, or underscores.");
            return true;
        }

        getConfig().set("names." + uuid, display);
        saveConfig();

        if (target != null) {
            applyName(target, display);
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
