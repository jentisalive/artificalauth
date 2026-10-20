package fun.vespera.dialogAuthVelocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LicenseCommand implements SimpleCommand {
    private final DialogAuthVelocity plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<UUID> confirmations = new HashSet<>();
    private final HttpClient httpClient;

    public LicenseCommand(DialogAuthVelocity plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private String getMessage(String path, String def) {
        return plugin.getConfig().node("messages", path).getString(def);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) return;

        String[] args = invocation.arguments();
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();

        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            if (confirmations.remove(uuid)) {
                player.sendMessage(mm.deserialize(getMessage("checking_license", "<yellow>Checking license...")));

                checkMojangAPI(username).thenAccept(hasLicense -> {
                    if (hasLicense) {
                        setPremiumViaAPI(username, true).thenAccept(success -> {
                            if (success) {
                                player.sendMessage(mm.deserialize(getMessage("license_confirmed", "<green>License successfully verified! <yellow>Please reconnect to the server for changes to take effect.")));
                            } else {
                                player.sendMessage(mm.deserialize(getMessage("license_api_error", "<red>Internal error: Failed to contact the game server to save status. Please contact administration.")));
                            }
                        });
                    } else {
                        player.sendMessage(mm.deserialize(getMessage("no_license_found", "<red>Сouldn't find a Minecraft license account with this nickname!")));
                    }
                });
            }
        } else {
            player.sendMessage(mm.deserialize(getMessage("license_warning", "<yellow>Are you sure you want to link your license? Enter <gold>/license confirm</gold> to verify.")));
            confirmations.add(uuid);
        }
    }

    // asking mojang: is there premium player with this username? (foolproofing)
    // there is a nuance: if player not a premium player, but there is premium account with his name, this is will work anyway
    private CompletableFuture<Boolean> checkMojangAPI(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                plugin.getLogger().error("an error occurred while checking license with Mojang API (is mojang/microsoft down?)", e);
                return false;
            }
        });
    }

    // asking paper plugin to change isPremium in his db
    // why we dont connect directly to db? because it will broke h2(local db type) support
    private CompletableFuture<Boolean> setPremiumViaAPI(String username, boolean status) {
        String host = plugin.getConfig().node("api", "host").getString("0.0.0.0");
        int port = plugin.getConfig().node("api", "port").getInt(8080);
        String token = plugin.getConfig().node("api", "token").getString("");

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Building our POST request body
                String requestBody = "username=" + username + "&status=" + status;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + host + ":" + port + "/api/setPremium"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    plugin.getLogger().error("Unauthorized API request, check if API-tokens matches in Paper and Velocity configs");
                    return false;
                }

                return response.statusCode() == 200 && "success".equals(response.body());
            } catch (Exception e) {
                plugin.getLogger().error("Cant connect to API, check plugin settings", e);
                return false;
            }
        });
    }
}