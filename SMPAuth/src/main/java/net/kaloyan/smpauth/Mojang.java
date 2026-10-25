package net.kaloyan.smpauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Talks to Mojang's web services (both addresses can be changed in the config): is there a bought account with this name, and did this player really join with it. */
public final class Mojang {

    /** A skin and the like that belongs to a profile. */
    public record Property(String name, String value, String signature) {
    }

    /**
     * A profile as Mojang knows it.
     *
     * @param id         the UUID Mojang made for the account
     * @param name       the name with the right capitals
     * @param properties its properties (the skin)
     */
    public record Profile(UUID id, String name, List<Property> properties) {
    }

    /** The answer to "is there a bought account with this name". */
    public enum Lookup {
        /** there is */
        EXISTS,
        /** there is none */
        MISSING,
        /** Mojang could not be asked or gave an answer that cannot be used */
        ERROR
    }

    private final HttpClient http;
    private final String lookupUrl;
    private final String sessionUrl;
    private final Duration timeout;

    /**
     * @param lookupUrl  address that has the name appended (answers 200 or 404)
     * @param sessionUrl the "has joined" address (answers 200 with a profile or 204)
     * @param timeout    how long to wait for an answer
     */
    public Mojang(String lookupUrl, String sessionUrl, Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.lookupUrl = lookupUrl;
        this.sessionUrl = sessionUrl;
        this.timeout = timeout;
    }

    /**
     * @param name a player name
     * @return whether a bought account has this name
     */
    public CompletableFuture<Lookup> lookup(String name) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(lookupUrl + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(timeout).header("Accept", "application/json").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((response, error) -> {
            if (error != null) {
                return Lookup.ERROR;
            }
            return switch (response.statusCode()) {
                case 200 -> Lookup.EXISTS;
                case 204, 404 -> Lookup.MISSING;
                default -> Lookup.ERROR;
            };
        });
    }

    /**
     * Asks Mojang whether the player who sent this server hash really joined as this name.
     *
     * @param name       the name the player gave
     * @param serverHash the hash of the encryption exchange (see {@link #serverHash})
     * @return the profile, or a future that completes with {@code null} if Mojang does not know the player; fails if Mojang cannot be reached
     */
    public CompletableFuture<Profile> hasJoined(String name, String serverHash) {
        String url = sessionUrl + "?username=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&serverId=" + URLEncoder.encode(serverHash, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() == 204 || response.statusCode() == 404 || response.statusCode() == 403) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mojang answered " + response.statusCode());
            }
            return parseProfile(response.body());
        });
    }

    /**
     * @param json the body of a "has joined" answer
     * @return the profile in it
     * @throws IllegalArgumentException if it is not one
     */
    public static Profile parseProfile(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String id = o.get("id").getAsString();
            if (!id.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("bad id");
            }
            UUID uuid = UUID.fromString(id.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})", "$1-$2-$3-$4-$5"));
            String name = o.get("name").getAsString();
            List<Property> properties = new ArrayList<>();
            JsonElement list = o.get("properties");
            if (list != null && list.isJsonArray()) {
                JsonArray array = list.getAsJsonArray();
                for (JsonElement element : array) {
                    JsonObject p = element.getAsJsonObject();
                    properties.add(new Property(p.get("name").getAsString(), p.get("value").getAsString(),
                            p.has("signature") && !p.get("signature").isJsonNull() ? p.get("signature").getAsString() : null));
                }
            }
            return new Profile(uuid, name, List.copyOf(properties));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Mojang's answer is not a profile: " + e.getMessage(), e);
        }
    }

    /**
     * The hash both sides compute from the encryption exchange (Minecraft's odd "signed hex" SHA-1).
     *
     * @param serverId  the server id (empty)
     * @param secret    the shared secret the client chose
     * @param publicKey the server's public key as it was sent
     * @return the hash as a string
     */
    public static String serverHash(String serverId, byte[] secret, byte[] publicKey) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            sha.update(secret);
            sha.update(publicKey);
            return new BigInteger(sha.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
