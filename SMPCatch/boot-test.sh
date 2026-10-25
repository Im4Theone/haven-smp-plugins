#!/bin/bash
# Throwaway server (never your real one): places decoys in a real generated world and checks the blocks.
# Usage: boot-test.sh <server-folder> <work-dir>
set -e
SRC="$1"; WORK="$2"
JAVA="/c/Program Files/Java/jdk-21.0.12.1/bin/java.exe"
HERE="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$WORK/srv" && mkdir -p "$WORK/srv/plugins"
for f in server.jar libraries versions cache; do cp -r "$SRC/$f" "$WORK/srv/"; done
cd "$WORK/srv"
echo "eula=true" > eula.txt
printf 'server-port=25592\nonline-mode=false\nlevel-seed=12345\ngenerate-structures=false\nview-distance=8\nsimulation-distance=8\n' > server.properties
cp "$HERE/build/libs/SMP-Catch-1.0.0.jar" plugins/
cat > "$WORK/driver.py" <<'PY'
import sys,re
text=open(sys.argv[1]).read()
for m in re.finditer(r"- '?(-?\d+),(-?\d+),(-?\d+),(\w+),(\w+)'?", text):
    x,y,z,orig,role=m.groups()
    if role=="ore":
        print(f"execute if block {x} {y} {z} minecraft:diamond_ore run say ORE_OK {x} {y} {z}")
        print(f"execute if block {x} {y} {z} minecraft:deepslate_diamond_ore run say ORE_OK {x} {y} {z}")
    if role=="container":
        print(f"execute if block {x} {y} {z} minecraft:chest run say CHEST_OK {x} {y} {z}")
    if role=="cavity":
        print(f"execute if block {x} {y} {z} minecraft:air run say CAVITY_OK {x} {y} {z}")
PY
{
  sleep 50
  echo "forceload add -64 -64 64 64"
  sleep 25
  echo "trap vein diamond 3 60 world 0 0"
  echo "trap stash chest 60 world 0 0"
  echo "trap list"
  echo "trap suspects"
  sleep 4
  python -I "$WORK/driver.py" plugins/SMP-Catch/traps.yml
  sleep 4
  echo "trap remove 1"
  echo "trap list"
  sleep 3
  echo "say AFTER_REMOVE"
  python -I "$WORK/driver.py" plugins/SMP-Catch/traps.yml | head -4
  echo "trap removeall confirm"
  sleep 3
  echo "stop"
} | "$JAVA" -Xmx2G -jar server.jar --nogui > "$WORK/boot.log" 2>&1 || true
grep -nE "SMP-Catch|Placed|Stash|#[0-9]|ORE_OK|CHEST_OK|CAVITY_OK|AFTER_REMOVE|ERROR|Exception|Removed|Decoys" "$WORK/boot.log" | grep -v "No key layers" | cut -c1-200 | head -70
