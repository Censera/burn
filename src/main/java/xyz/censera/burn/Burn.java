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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class Burn extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    // Apply stored display name and fix join message.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String stored = getConfig().getString("names." + player.getUniqueId());
        if (stored != null) {
            player.setDisplayName(stored);
            player.setPlayerListName(stored);
            event.setJoinMessage(stored + " §ejoined the game.");
        }
    }

    // Fix quit message to use display name.
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

        // Silently ignore non-OPs — no feedback, no error.
        if (!sender.isOp()) {
            return true;
        }

        // /burn <username> "<display>" | reset
        if (args.length < 2) {
            sender.sendMessage("§7/burn <player> <display|reset>");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);

        // Resolve UUID: online players directly, offline via cache.
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

        // Collect remaining args as the display name (handles quoted and unquoted).
        String rest = String.join(" ", List.of(args).subList(1, args.length))
                           .replaceAll("^\"|\"$", "");

        if (rest.equalsIgnoreCase("reset")) {
            getConfig().set("names." + uuid, null);
            saveConfig();
            if (target != null) {
                target.setDisplayName(target.getName());
                target.setPlayerListName(target.getName());
            }
            sender.sendMessage("§7Reset.");
            return true;
        }

        // Translate §-codes that come in as literal § characters.
        String display = rest.replace("&", "§");

        getConfig().set("names." + uuid, display);
        saveConfig();

        if (target != null) {
            target.setDisplayName(display);
            target.setPlayerListName(display);
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
