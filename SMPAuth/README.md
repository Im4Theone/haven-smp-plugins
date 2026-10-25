# SMP-Auth 1.0.0 (author: Kaloyan)
Players WITHOUT a bought account register and log in; players WITH one walk in. Paper 1.21.11, Java 21.

## Setting up (important)
1. `server.properties`: `online-mode=false` (so players without a bought account can join) and `enforce-secure-profile=false`. Restart.
2. Bought accounts need **no other plugin** (no FastLogin, no ProtocolLib): SMP-Auth does the check itself, the same way an online-mode server does. When a
   name has to be proven, the game is asked to encrypt the connection and the answer is checked with Mojang's session server; only then does the login go on, with the
   account's real UUID and skin. Who has to prove it:
   - names marked as bought accounts (players type `/premium` once while logged in, or staff run `/auth premium <name>`), and
   - with `premium.auto-detect: true` (default) every new name that a bought account has, so new players with a bought account never even see `/register`.
   Names that already have a password account here log in with the password until their owner types `/premium`. Nobody can walk in with somebody else's bought name.
   If Mojang cannot be reached a NEW name cannot join for a moment (guessing would be unsafe); everybody else is not affected.
   If a Minecraft update changes the server's internals the check switches itself off with a message in the log and everybody simply registers and logs in.
   Check a player with `/auth uuid <player>` (a version 4 UUID = verified). If you installed FastLogin earlier, remove it and ProtocolLib; they are not needed.
3. `/auth` for staff: `unregister <name>`, `setpassword <name> <password>` (a forgotten password), `forcelogin <player>`, `info <name>`, `uuid <player>`, `premium <name>` / `cracked <name>` (mark or unmark a bought account), `reload`.

## For players
`/premium` (once, if you have a bought account and already registered with a password).
`/register <password> <password>`, `/login <password>` (`/l`), `/changepassword <old> <new>`, `/logout`, `/unregister <password>`. Rejoining within 10 minutes from the same address needs no
password (not for operators and staff). A player has 60 seconds to log in, then is kicked.

## Safety
- Passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes (310,000 rounds), checked in constant time, and hashed off the main thread.
- Until somebody has logged in they are blinded and cannot move, build, chat (a password typed into the chat is never shown), use any command except /login and /register,
  open or click inventories, pick up or drop items, or be hurt or hurt others.
- Five wrong passwords kick the player and lock the address for 10 minutes (the name needs 20 wrong tries, so nobody can easily lock a player out of their own account).
- Commands with passwords (`/login`, `/register`, `/changepassword`, `/unregister`, `/auth setpassword`) are removed from the console and the log files.
- A name must be typed with the capitals it was registered with; a name that a bought account was seen with cannot be used by a player without one; at most 3 accounts per address.
- A damaged `auth.yml` stops the plugin instead of letting anybody take a name.
Tests: 12 unit tests, plus an end-to-end test with real clients (register, login, logout, wrong passwords, kick, lock, session, wrong capitals).
