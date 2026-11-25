package com.mgames.minecraft.rpg.lobby;

import com.google.gson.Gson;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class LobbyApi {

    private static final Gson GSON = new Gson();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String serverId;
    private final String token;

    LobbyApi(){
        URI uri = URI.create(System.getenv().getOrDefault("LOBBY_CLOUD_URL", ""));
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null){
            throw new IllegalArgumentException("LOBBY_CLOUD_URL должен содержать HTTPS-адрес Cloud");
        }

        baseUrl = uri.toString().replaceFirst("/+$", "");
        serverId = System.getenv().getOrDefault("LOBBY_SERVER_ID", "lobby-main");
        token = System.getenv().getOrDefault("LOBBY_CLOUD_TOKEN", "");
        if (!serverId.matches("[A-Za-z0-9._-]{1,64}") || token.length() < 32){
            throw new IllegalArgumentException("Проверьте LOBBY_SERVER_ID и LOBBY_CLOUD_TOKEN");
        }
    }

    CompletableFuture<Response> password(Player player, boolean register, String password, String newPassword){
        Map<String, String> body = playerInput(player);
        body.put("password", password);
        if (newPassword != null){
            body.put("new_password", newPassword);
        }
        return post(register ? "register" : "login", body);
    }

    CompletableFuture<Response> session(Player player, String token){
        Map<String, String> body = playerInput(player);
        body.put("session", token);
        return post("session", body);
    }

    CompletableFuture<Response> modes(Player player, String session){
        Map<String, String> body = playerInput(player);
        body.put("session", session);
        return post("modes", body);
    }

    CompletableFuture<Response> ticket(Player player, String session, String destination){
        Map<String, String> body = playerInput(player);
        body.put("session", session);
        body.put("destination", destination);
        return post("tickets", body);
    }

    private Map<String, String> playerInput(Player player){
        Map<String, String> body = new HashMap<>();
        body.put("name", player.getName());
        body.put("uuid", player.getUniqueId().toString());
        return body;
    }

    CompletableFuture<Void> snapshot(Map<String, Object> snapshot){
        return send("analytics", snapshot).thenApply(response -> {
            if (response.statusCode() != 200){
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return null;
        });
    }

    private CompletableFuture<Response> post(String endpoint, Map<String, String> body){
        return send(endpoint, body).thenApply(http -> GSON.fromJson(http.body(), Response.class));
    }

    private CompletableFuture<HttpResponse<String>> send(String endpoint, Map<String, ?> body){
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/game/lobby/" + endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Game-Server-Id", serverId)
                .header("X-Game-Server-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    record Response(String session, String ticket, String error, boolean authenticated, List<Mode> modes,
                    String host, int port) {
    }

    record Mode(String serverId, String name, String serverName, String environment, boolean online, int onlinePlayers,
                int maxPlayers, boolean onlinePlayersKnown, boolean canJoin) {
    }
}
