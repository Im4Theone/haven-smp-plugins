#!/bin/bash
# Throwaway server boot for SMP-Auction (never your real server). Usage: boot-test.sh <server-folder> <plugins-folder> <work-dir>
set -e
SRC="$1"; JARS="$2"; WORK="$3"
JAVA="/c/Program Files/Java/jdk-21.0.12.1/bin/java.exe"
rm -rf "$WORK/srv" && mkdir -p "$WORK/srv/plugins"
for f in server.jar libraries versions cache; do cp -r "$SRC/$f" "$WORK/srv/"; done
cd "$WORK/srv"
echo "eula=true" > eula.txt
printf 'server-port=25594\nonline-mode=false\nlevel-type=minecraft\:flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\n' > server.properties
cp "$JARS/Vault-1.7.3.jar" "$JARS/SMP-Auction-1.0.0.jar" plugins/
for j in SMP-Core-1.0.0 SMP-Economy-1.0.0 SMP-VaultBridge-1.0.0; do cp "$SRC/plugins/$j.jar" plugins/; done
{ sleep 45; echo "ah"; echo "ah info"; echo "ah cancel 1"; echo "ah reload"; echo "ah history"; echo "plugins"; sleep 2; echo "stop"; } | "$JAVA" -Xmx1G -jar server.jar --nogui > "$WORK/boot.log" 2>&1 || true
grep -nE "SMP-Auction|Auction|ERROR|Exception|Could not" "$WORK/boot.log" | grep -v "No key layers" | cut -c1-200 | head -30
