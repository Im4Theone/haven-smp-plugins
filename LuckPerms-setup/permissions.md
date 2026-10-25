# Who can see and use what (Haven SMP)

Ranks (low to high): Wanderer (default), Settler, Keeper, Beacon, Lookout, Warden, Guardian (admin), Founder. Each rank inherits the ranks below.

**Staff commands belong to Guardian and Founder only.** Everybody else does not see them in their command list or in tab-complete.

| Command | Permission | Who sees it |
|---|---|---|
| /ban /tempban /ipban /unban /kick /mute /unmute /warn /history /punishment /banlist /alts /punish | smppunish.* | Guardian, Founder |
| /v (vanish), /freeze, /trap | smpcatch.* | Guardian, Founder |
| /kitadmin | smpkits.admin | Guardian, Founder |
| /ordersadmin | smporders.admin | Guardian, Founder |
| /spawners | smpspawners.admin | Guardian, Founder |
| /protect, /drill, /setspawn, /havenbuild | smpprotect.admin, smpdrill.admin, smptravel.admin, havenbuilder.build | Guardian, Founder |
| /smpcore, /eco | smpcore.*, smpeconomy.admin.* | Guardian, Founder |
| /keyall | smpcrates.keyall | only you (see the notes in haven-new-plugins.txt) |
| /kit | smpkits.use (default true) | everybody. Wanderer kit for everyone, rank kits unlocked up to your rank, higher ones locked; Guardian is told the rank gets no kits; the Founder has all kits |
| /shop /shards /order /ah /crates /rtp /home /spawn /hub /tpa /back /worth /balance /pay /scoreboard | public | everybody |

Not touched by the plugins: LuckPerms (`/lp`), Multiverse, FastAsyncWorldEdit and spark keep their own default (operators only).
Operators (op) see every command regardless of ranks, so test with a non-op account.
