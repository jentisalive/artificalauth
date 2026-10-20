package fun.vespera.dialogAuth.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fun.vespera.dialogAuth.DatabaseManager;
import fun.vespera.dialogAuth.DialogAuth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class PremiumAuth {
    private final DialogAuth plugin;
    private HttpServer server;
    private final String token;

    public PremiumAuth(DialogAuth plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    // method for API auth
    private boolean isAuthorized(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        return authHeader != null && authHeader.equals("Bearer " + token);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.sendResponseHeaders(statusCode, response.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    public void start(String hostname,int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(hostname, port), 0);

            // 1-st endpoint, getting premium status
            server.createContext("/api/isPremium", exchange -> {
                // API-auth check
                if (!isAuthorized(exchange)) {
                    sendResponse(exchange, 401, "unauthorized");
                    return;
                }

                // false response by default(for security)
                String query = exchange.getRequestURI().getQuery();
                String response = "false";
                String username = null;
                String ip = null;

                if (query != null && query.startsWith("username=")) {
                    // get API params
                    String[] params = query.split("&");

                    // spliting params to ip & nickname
                    for (String param : params) {
                        if (param.startsWith("username=")) username = param.substring(9);
                        if (param.startsWith("ip=")) ip = param.substring(3);
                    }

                    if (username != null) {
                        // adding players ip to cache
                        if (ip != null) {
                            plugin.addPendingIp(username, ip);
                        }
                        // checkin isPremium in db
                        DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(username).join();
                        if (data != null && data.isPremium()) {
                            // changing response to true
                            response = "true";
                        }
                    }
                }
                // sending http code: 200 (success)
                sendResponse(exchange, 200, response);
            });


            server.createContext("/api/setPremium", exchange -> {
                // API-auth check
                if (!isAuthorized(exchange)) {
                    sendResponse(exchange, 401, "unauthorized");
                    return;
                }

                // now using POST method for this endpoint
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "method_not_allowed");
                    return;
                }

                // reading POST body
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response = "error";
                int statusCode = 400;

                // error response by default(for security)
                String[] params = body.split("&");
                String username = null;
                boolean status = false;

                // parsing query parameters
                for (String param : params) {
                    if (param.startsWith("username=")) username = param.substring(9);
                    if (param.startsWith("status=")) status = Boolean.parseBoolean(param.substring(7));
                }

                if (username != null) {
                    DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(username).join();
                    if (data != null) {
                        // updating table with isPremium=true status
                        DatabaseManager.PlayerData newData = new DatabaseManager.PlayerData(
                                data.uuid(), data.username(), data.passwordHash(),
                                data.lastIp(), data.lastLogin(), status
                        );
                        plugin.getDb().savePlayer(newData).join();
                        // changing response to success
                        response = "success";
                        statusCode = 200;
                    } else {
                        // if player not found, changing to player not found
                        response = "player_not_found";
                        statusCode = 404;
                    }
                }
                sendResponse(exchange, statusCode, response);
            });

            server.setExecutor(plugin.getAsyncExecutor());
            server.start();
            plugin.getLogger().info("API enabled on port: " + port);

        } catch (IOException e) {
            plugin.getLogger().severe("An error occurred while attempting to enable API on port: " + port);
            e.printStackTrace();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}