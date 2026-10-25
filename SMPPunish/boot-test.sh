#!/bin/bash
# Throwaway server (never your real one): runs the punishment commands from the console. Usage: boot-test.sh <server-folder> <work-dir>
set -e
SRC="$1"; WORK="$2"
JAVA="/c/Program Files/Java/jdk-21.0.12.1/bin/java.exe"
HERE="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$WORK/srv" && mkdir -p "$WORK/srv/plugins"
for f in server.jar libraries versions cache; do cp -r "$SRC/$f" "$WORK/srv/"; done
cd "$WORK/srv"
echo "eula=true" > eula.txt
printf 'server-port=25591\nonline-mode=false\nlevel-type=minecraft\:flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\n' > server.properties
cat > usercache.json <<'JSON'
[{"name":"Tester","uuid":"11111111-2222-3333-4444-555555555555","expiresOn":"2099-01-01 00:00:00 +0000"}]
JSON
cp "$HERE/build/libs/SMP-Punish-1.0.0.jar" plugins/
{
  sleep 40
  for c in "ban Nobody" "tempban Tester" "tempban Tester 7d x-ray" "ban Tester cheating" "history Tester" "banlist" "unban Tester appeal ok" "unban Tester" "ban Tester xray -s" "punishment 2" \
           "warn Tester spam" "warn Tester spam again" "warn Tester third" "history Tester" "mute Tester 30m" "mute Tester" "unmute Tester" "alts Tester" "punishment remove 2 mistake" "banlist" "punish reload" "kick Tester"; do
    echo "$c"; sleep 1
  done
  echo "stop"
} | "$JAVA" -Xmx1G -jar server.jar --nogui > "$WORK/boot.log" 2>&1 || true
grep -nE "SMP-Punish|BAN|MUTE|WARN|KICK|#[0-9]|banned|Bans|Usage|unknown|know|ERROR|Exception|reloaded|entr|already|not muted|not banned" "$WORK/boot.log" | grep -v "No key layers" | cut -c1-210 | head -70
cat plugins/SMP-Punish/punishments.yml | head -30
