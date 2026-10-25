package net.kaloyan.smpauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing and the rules a password must follow. Passwords are never stored: only a salted PBKDF2-HMAC-SHA256 hash
 * ({@code pbkdf2$iterations$salt$hash}, Base64), compared in constant time.
 */
public final class Passwords {

    /** Iterations for new hashes (older hashes keep the number written in them). */
    public static final int ITERATIONS = 310_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> COMMON = Set.of("password", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123", "iloveyou",
            "11111111", "00000000", "abcd1234", "password1", "passw0rd", "minecraft", "letmein1", "admin123", "welcome1", "12341234");

    private Passwords() {
    }

    /** What is wrong with a password. */
    public enum Problem {
        /** Fine. */
        NONE,
        /** Shorter than the minimum. */
        TOO_SHORT,
        /** Longer than the maximum. */
        TOO_LONG,
        /** The same as the player's name. */
        SAME_AS_NAME,
        /** One of the passwords everybody tries first. */
        TOO_COMMON,
        /** Contains spaces or control characters (it would break commands). */
        BAD_CHARACTERS
    }

    /**
     * @param password the password
     * @param name     the player's name
     * @param min      the shortest allowed length
     * @param max      the longest allowed length
     * @return what is wrong with it, or {@link Problem#NONE}
     */
    public static Problem check(String password, String name, int min, int max) {
        if (password.length() < min) {
            return Problem.TOO_SHORT;
        }
        if (password.length() > max) {
            return Problem.TOO_LONG;
        }
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return Problem.BAD_CHARACTERS;
            }
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.equals(name.toLowerCase(Locale.ROOT))) {
            return Problem.SAME_AS_NAME;
        }
        if (COMMON.contains(lower)) {
            return Problem.TOO_COMMON;
        }
        return Problem.NONE;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * @param password the password
     * @return a new salted hash to store
     */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, ITERATIONS, 32);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * @param password the password somebody typed
     * @param stored   a hash made by {@link #hash}
     * @return whether the password is the right one (always {@code false} for a stored value that is not valid)
     */
    public static boolean verify(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1000 || iterations > 5_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** @return a hash of nothing in particular, compared against when an account does not exist, so a wrong name takes as long as a wrong password */
    public static String dummy() {
        return DUMMY;
    }

    private static final String DUMMY = "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8))
            + "$" + Base64.getEncoder().encodeToString(new byte[32]);
}
