#!/bin/bash
# Throwaway server boot for SMP-Shop (never your real server). Usage: boot.sh <server-folder> <plugins-folder> <work-dir>
set -e
SRC="$1"; JARS="$2"; WORK="$3"
JAVA="/c/Program Files/Java/jdk-21.0.12.1/bin/java.exe"
rm -rf "$WORK/srv" && mkdir -p "$WORK/srv/plugins/DonutShop"
for f in server.jar libraries versions cache; do cp -r "$SRC/$f" "$WORK/srv/"; done
cd "$WORK/srv"
echo "eula=true" > eula.txt
printf 'server-port=25596\nonline-mode=false\nlevel-type=minecraft\:flat\ngenerate-structures=false\nview-distance=4\nsimulation-distance=4\n' > server.properties
cp "$JARS/Vault-1.7.3.jar" "$JARS/SMP-Spawners-1.0.0.jar" "$JARS/SMP-Crates-1.0.0.jar" "$JARS/SMP-Shop-1.0.0.jar" "$JARS/SMP-Scoreboard-1.0.0.jar" plugins/
for j in SMP-Core-1.0.0 SMP-Economy-1.0.0 SMP-VaultBridge-1.0.0; do cp "$SRC/plugins/$j.jar" plugins/; done
cp "$SRC/plugins/DonutShop/shards.yml" plugins/DonutShop/shards.yml
{ sleep 45; echo "shards"; echo "shards give Nobody 5"; echo "shop"; echo "plugins"; sleep 2; echo "stop"; } | "$JAVA" -Xmx1G -jar server.jar --nogui > "$WORK/boot.log" 2>&1 || true
grep -nE "SMP-Shop|Shop|ERROR|Exception|Could not|Copied" "$WORK/boot.log" | grep -v "No key layers" | cut -c1-220 | head -30
cat plugins/SMP-Shop/shards.yml
