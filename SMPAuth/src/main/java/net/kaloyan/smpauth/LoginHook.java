package net.kaloyan.smpauth;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.crypto.SecretKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

/**
 * Makes players whose name belongs to a bought account prove it to Mojang, on a server that runs with online-mode=false (so players without
 * a bought account can join as well). It listens to the login of every new connection; for such a name it does what a server in online mode
 * does itself (asks the client to encrypt the connection and checks the result with Mojang) and only then lets the login go on, with the
 * UUID and skin Mojang says. Everything else passes untouched. The server's own classes are reached with reflection, so a Minecraft update
 * that renames them makes the hook switch itself off (reported in the log) instead of breaking logins.
 */
public final class LoginHook {

    /** A player who proved his bought account; taken by the login event within a short time. */
    public record Verified(Mojang.Profile profile, String ip, long until) {
    }

    private static final String HANDLER = "smpauth_premium";
    private static final String ACCEPTOR = "smpauth_acceptor";
    private static final long VERIFIED_MILLIS = 60_000L;

    /** The server's classes, found by name. */
    private static final class Nms {
        final Class<?> helloIn;
        final Class<?> helloOut;
        final Class<?> keyIn;
        final Method helloName;
        final java.lang.reflect.Constructor<?> newHelloIn;
        final java.lang.reflect.Constructor<?> newHelloOut;
        final java.lang.reflect.Constructor<?> newDisconnect;
        final Method keySecret;
        final Method keyChallengeValid;
        final Method setEncryption;
        final Method asVanilla;
        final Object server;
        final Object connections;
        final java.security.KeyPair keyPair;

        Nms() throws ReflectiveOperationException {
            Object craft = Bukkit.getServer();
            server = craft.getClass().getMethod("getServer").invoke(craft);
            connections = server.getClass().getMethod("getConnection").invoke(server);
            keyPair = (java.security.KeyPair) server.getClass().getMethod("getKeyPair").invoke(server);
            if (keyPair == null) {
                throw new IllegalStateException("the server has no key pair");
            }
            String login = "net.minecraft.network.protocol.login.";
            helloIn = Class.forName(login + "ServerboundHelloPacket");
            helloOut = Class.forName(login + "ClientboundHelloPacket");
            keyIn = Class.forName(login + "ServerboundKeyPacket");
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            helloName = helloIn.getMethod("name");
            newHelloIn = helloIn.getConstructor(String.class, java.util.UUID.class);
            newHelloOut = helloOut.getConstructor(String.class, byte[].class, byte[].class, boolean.class);
            newDisconnect = Class.forName(login + "ClientboundLoginDisconnectPacket").getConstructor(component);
            keySecret = keyIn.getMethod("getSecretKey", PrivateKey.class);
            keyChallengeValid = keyIn.getMethod("isChallengeValid", byte[].class, PrivateKey.class);
            setEncryption = Class.forName("net.minecraft.network.Connection").getMethod("setEncryptionKey", SecretKey.class);
            asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asVanilla", Component.class);
        }
    }

    private final Nms nms;
    private final Mojang mojang;
    private final Gate gate;
    private final Function<String, Component> message;
    private final Logger log;
    private final Map<String, Verified> verified = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final int timeoutSeconds;

    private LoginHook(Nms nms, Mojang mojang, Gate gate, Function<String, Component> message, Logger log, int timeoutSeconds) {
        this.nms = nms;
        this.mojang = mojang;
        this.gate = gate;
        this.message = message;
        this.log = log;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Installs the hook on every port the server listens on.
     *
     * @param mojang         Mojang's services
     * @param gate           decides who has to be verified
     * @param message        the kick text for a message key
     * @param log            where problems are reported
     * @param timeoutSeconds how long a client may take for the encryption step
     * @return the hook
     * @throws ReflectiveOperationException if the server is not built the way this expects
     */
    public static LoginHook install(Mojang mojang, Gate gate, Function<String, Component> message, Logger log, int timeoutSeconds)
            throws ReflectiveOperationException {
        LoginHook hook = new LoginHook(new Nms(), mojang, gate, message, log, timeoutSeconds);
        Field channels = hook.nms.connections.getClass().getDeclaredField("channels");
        channels.setAccessible(true);
        ChannelHandler initializer = hook.initializer();
        boolean any = false;
        for (Object future : (List<?>) channels.get(hook.nms.connections)) {
            Channel listening = ((ChannelFuture) future).channel();
            listening.pipeline().addFirst(ACCEPTOR, hook.acceptor(initializer));
            any = true;
        }
        if (!any) {
            throw new IllegalStateException("the server does not listen on any port yet");
        }
        return hook;
    }

    /** Removes the hook from the listening ports and from the connections that are open. */
    public void remove() {
        try {
            Field channels = nms.connections.getClass().getDeclaredField("channels");
            channels.setAccessible(true);
            for (Object future : (List<?>) channels.get(nms.connections)) {
                Channel c = ((ChannelFuture) future).channel();
                if (c.pipeline().get(ACCEPTOR) != null) {
                    c.pipeline().remove(ACCEPTOR);
                }
            }
            for (Object connection : (List<?>) nms.connections.getClass().getMethod("getConnections").invoke(nms.connections)) {
                Channel c = (Channel) connection.getClass().getField("channel").get(connection);
                if (c != null && c.pipeline().get(HANDLER) != null) {
                    c.pipeline().remove(HANDLER);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Could not remove the premium hook cleanly: " + e);
        }
    }

    /**
     * @param name the player name
     * @param ip   the address he is joining from
     * @param now  the time
     * @return what Mojang verified for this name just now (once only), or {@code null}
     */
    public Verified take(String name, String ip, long now) {
        Verified v = verified.remove(name.toLowerCase(Locale.ROOT));
        return v != null && v.until() > now && v.ip().equals(ip) ? v : null;
    }

    private ChannelHandler acceptor(ChannelHandler initializer) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                // The server's own acceptor (further down) adds its initialiser to the new connection; ours is added after it so the pipeline is complete.
                ctx.fireChannelRead(msg);
                if (msg instanceof Channel child) {
                    child.pipeline().addLast(initializer);
                }
            }
        };
    }

    private ChannelHandler initializer() {
        return new SharedInitializer(this);
    }

    @ChannelHandler.Sharable
    private static final class SharedInitializer extends ChannelInitializer<Channel> {
        private final LoginHook hook;

        SharedInitializer(LoginHook hook) {
            this.hook = hook;
        }

        @Override
        protected void initChannel(Channel ch) {
            if (ch.pipeline().get("packet_handler") != null) {
                ch.pipeline().addBefore("packet_handler", HANDLER, hook.new Handler());
            } else {
                hook.log.warning("A connection has no packet_handler; it is not checked.");
            }
        }
    }

    private enum State { NEW, DECIDING, WAITING_KEY, VERIFYING, DONE, CLOSED }

    /** One per connection. */
    private final class Handler extends ChannelInboundHandlerAdapter {
        private State state = State.NEW;
        private String name;
        private Object hello;
        private byte[] challenge;
        private byte[] publicKey;

        private String ip(ChannelHandlerContext ctx) {
            return ctx.channel().remoteAddress() instanceof InetSocketAddress a && a.getAddress() != null ? a.getAddress().getHostAddress() : "?";
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (state == State.CLOSED) {
                    return;
                }
                if (state == State.DONE || state == State.NEW && !nms.helloIn.isInstance(msg)) {
                    ctx.fireChannelRead(msg);
                    return;
                }
                if (state == State.NEW) {
                    name = (String) nms.helloName.invoke(msg);
                    hello = msg;
                    state = State.DECIDING;
                    gate.decide(name, ip(ctx), System.currentTimeMillis()).whenCompleteAsync((verdict, error) -> decided(ctx, verdict, error), ctx.executor());
                    return;
                }
                if (state == State.WAITING_KEY && nms.keyIn.isInstance(msg)) {
                    key(ctx, msg);
                }
                // anything else while the check runs is not expected from a real client and is dropped
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.warning("Premium check failed for a connection from " + ip(ctx) + ": " + e);
                kick(ctx, "verify-error");
            }
        }

        private void decided(ChannelHandlerContext ctx, Gate.Verdict verdict, Throwable error) {
            try {
                if (error != null || verdict == null || verdict == Gate.Verdict.UNKNOWN) {
                    kick(ctx, "verify-error");
                } else if (verdict == Gate.Verdict.BUSY) {
                    kick(ctx, "verify-busy");
                } else if (verdict == Gate.Verdict.PASS) {
                    state = State.DONE;
                    ctx.fireChannelRead(hello);
                } else {
                    publicKey = nms.keyPair.getPublic().getEncoded();
                    challenge = new byte[4];
                    random.nextBytes(challenge);
                    state = State.WAITING_KEY;
                    ctx.writeAndFlush(nms.newHelloOut.newInstance("", publicKey, challenge, true));
                    ctx.executor().schedule(() -> {
                        if (state == State.WAITING_KEY || state == State.VERIFYING) {
                            kick(ctx, "verify-error");
                        }
                    }, timeoutSeconds, TimeUnit.SECONDS);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.warning("Premium check failed for " + name + ": " + e);
                kick(ctx, "verify-error");
            }
        }

        private void key(ChannelHandlerContext ctx, Object keyPacket) throws ReflectiveOperationException {
            PrivateKey priv = nms.keyPair.getPrivate();
            if (!(Boolean) nms.keyChallengeValid.invoke(keyPacket, challenge, priv)) {
                kick(ctx, "premium-name");
                return;
            }
            SecretKey secret = (SecretKey) nms.keySecret.invoke(keyPacket, priv);
            String hash = Mojang.serverHash("", secret.getEncoded(), publicKey);
            // Encrypt before anything else is sent, as a server in online mode does.
            nms.setEncryption.invoke(ctx.pipeline().get("packet_handler"), secret);
            state = State.VERIFYING;
            String asked = name;
            String ip = ip(ctx);
            mojang.hasJoined(asked, hash).whenCompleteAsync((profile, error) -> {
                if (error != null) {
                    log.warning("Mojang could not verify " + asked + ": " + (error.getCause() != null ? error.getCause() : error));
                    kick(ctx, "verify-error");
                } else if (profile == null || !profile.name().equalsIgnoreCase(asked)) {
                    log.info(asked + " from " + ip + " tried to use a bought account name without owning it.");
                    kick(ctx, "premium-name");
                } else {
                    try {
                        verified.put(asked.toLowerCase(Locale.ROOT), new Verified(profile, ip, System.currentTimeMillis() + VERIFIED_MILLIS));
                        state = State.DONE;
                        // The login goes on with the exact name Mojang has; the login event takes the UUID and skin from the entry above.
                        ctx.fireChannelRead(nms.newHelloIn.newInstance(profile.name(), profile.id()));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log.warning("Premium login of " + asked + " failed: " + e);
                        kick(ctx, "verify-error");
                    }
                }
            }, ctx.executor());
        }

        private void kick(ChannelHandlerContext ctx, String key) {
            state = State.CLOSED;
            try {
                Object packet = nms.newDisconnect.newInstance(nms.asVanilla.invoke(null, message.apply(key)));
                ctx.writeAndFlush(packet).addListener(ChannelFutureListener.CLOSE);
            } catch (ReflectiveOperationException | RuntimeException e) {
                ctx.close();
            }
        }
    }
}
