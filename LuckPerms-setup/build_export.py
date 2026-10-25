"""Builds LuckPerms-setup/haven.json.gz: the old haven.json.gz with every command of haven-new-plugins.txt applied."""
import gzip, json, re, sys, datetime

old, commands, out = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(gzip.open(old))
groups = data["groups"]

def nodes(group):
    return groups[group]["nodes"]

applied = 0
for line in open(commands, encoding="utf-8"):
    m = re.match(r"^lp group (\w+) permission (set|unset) (\S+)(?: (true|false))?\s*$", line)
    if not m:
        continue
    group, action, node, value = m.groups()
    ns = nodes(group)
    ns[:] = [n for n in ns if not (n["type"] == "permission" and n["key"] == node)]
    if action == "set":
        ns.append({"type": "permission", "key": node, "value": value == "true"})
    applied += 1

# keep each group's nodes in a stable order: inheritance, prefix, weight, then permissions sorted by name
order = {"inheritance": 0, "prefix": 1, "weight": 2, "permission": 3}
for g in groups.values():
    g["nodes"].sort(key=lambda n: (order.get(n["type"], 9), n["key"]))

data["metadata"] = {"generatedBy": "Console", "generatedAt": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
with gzip.open(out, "wt", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
print("applied", applied, "commands")
for name, g in groups.items():
    perms = [("" if n["value"] else "-") + n["key"] for n in g["nodes"] if n["type"] == "permission"]
    print(f"{name}: {len(perms)} permission nodes")
