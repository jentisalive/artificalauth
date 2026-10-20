package fun.vespera.dialogAuthVelocity;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class LoginListener {
    private final DialogAuthVelocity plugin;
    private final HttpClient httpClient;

    public LoginListener(DialogAuthVelocity plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> {
            String username = event.getUsername();
            String host = plugin.getConfig().node("api", "host").getString("0.0.0.0");
            int port = plugin.getConfig().node("api", "port").getInt(8080);
            String token = plugin.getConfig().node("api", "token").getString("");

            String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();

            // asking paper plugin: is this player set /license?
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + host + ":" + port + "/api/isPremium?username=" + username + "&ip=" + ip))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // forcing player to join with online-mode with session check
                if (response.statusCode() == 200 && "true".equalsIgnoreCase(response.body())) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                    return;
                } else if (response.statusCode() == 401) {
                    plugin.getLogger().error("Unauthorized API request, check if API-tokens matches in Paper and Velocity configs");
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode()); // setting online-mode anyway, to prevent unauthorized login on server
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().error("Cant connect to API to check: " + username, e);
            }

            // leave as is
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        });
    }

    // spoofing UUID
    // short explanation: Premium UUIDs and offline UUIDs differ, without spoofing
    // when player activate /license, all his progress, inventory etc. will be reseted
    // because his uuid changed. So, to get rid of this effect, after plugin validates session
    // he "recover" his offline UUID and says to all other servers: "OH, HEY THERE, NOW THIS IS CORRECT UUID OF THIS PLAYER, I SWEAR"
    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        // generating offline uuid
        String offlineIdString = "OfflinePlayer:" + event.getUsername();
        UUID offlineUUID = UUID.nameUUIDFromBytes(offlineIdString.getBytes(StandardCharsets.UTF_8));

        // get original player profile (with premiumUUID)
        GameProfile originalProfile = event.getGameProfile();

        // leavin everything as is, except UUID
        GameProfile modifiedProfile = originalProfile.withId(offlineUUID);

        // SPOOF!
        event.setGameProfile(modifiedProfile);
    }
}