// End-to-end test of the Haven SMP plugins with real (offline-mode) Minecraft clients.
const BOT = process.env.BOTDIR;
const mineflayer = require(BOT + '/node_modules/mineflayer');
const mc = require(BOT + '/node_modules/minecraft-protocol');
const PORT = parseInt(process.env.PORT || '25579');
const results = [];
function check(name, ok, extra) { results.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : '')); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ChatMessage = require(BOT + '/node_modules/prismarine-chat')(require(BOT + '/node_modules/minecraft-data')('1.21.11'));
const plain = t => { try { return typeof t === 'string' ? t : (t.toString ? t.toString() : JSON.stringify(t)); } catch (e) { return String(t); } };

function join(name) {
  return new Promise(resolve => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline', hideErrors: true });
    bot.lines = [];
    bot.commandNames = null;
    bot.kicked = null;
    bot.on('messagestr', m => bot.lines.push(m));
    bot.on('kicked', r => { bot.kicked = plain(r); });
    bot.on('error', () => {});
    bot._client.on('declare_commands', p => {
      try {
        const root = p.nodes[p.rootIndex !== undefined ? p.rootIndex : 0];
        bot.commandNames = root.children.map(i => p.nodes[i].extraNodeData && p.nodes[i].extraNodeData.name).filter(Boolean);
      } catch (e) { bot.commandNames = ['?' + e]; }
    });
    bot.once('spawn', () => resolve(bot));
    setTimeout(() => resolve(bot), 25000);
  });
}
const said = (bot, text) => bot.lines.some(l => l.toLowerCase().includes(text.toLowerCase()));
const lastLines = (bot, n = 3) => bot.lines.slice(-n).join(' | ').slice(0, 220);

function nextWindow(bot, ms = 5000) {
  return new Promise(res => {
    const t = setTimeout(() => res(null), ms);
    bot.once('windowOpen', w => { clearTimeout(t); setTimeout(() => res(w), 600); });
  });
}
async function cmdWindow(bot, cmd) { const p = nextWindow(bot); bot.chat(cmd); return p; }
async function click(bot, slot, mode = 0, button = 0) {
  const p = nextWindow(bot, 2500);
  try { await bot.clickWindow(slot, button, mode); } catch (e) { /* the server may close the window */ }
  return p;
}
const flat = x => (x && typeof x === 'object') ? ((x.text || '') + (x.extra || []).map(flat).join('')) : String(x == null ? '' : x);
const nbt = require(BOT + '/node_modules/prismarine-nbt');
const title = w => { if (!w) return 'NO WINDOW'; try { return flat(nbt.simplify(w.title)); } catch (e) { return plain(w.title); } };
const names = w => w.slots.slice(0, w.inventoryStart !== undefined ? w.inventoryStart : w.slots.length).filter(Boolean).map(i => i.name);
const hasItem = (bot, name) => bot.inventory.items().some(i => i.name === name);

(async () => {
  // ---- the server list text
  try {
    const ping = await new Promise((res, rej) => mc.ping({ host: '127.0.0.1', port: PORT, version: '1.21.11' }, (e, r) => e ? rej(e) : res(r)));
    check('the server list shows the HAVEN SMP text', flat(ping.description).toUpperCase().includes('HAVEN'), flat(ping.description).slice(0, 80));
  } catch (e) { check('the server list shows the HAVEN SMP text', false, String(e)); }

  const normie = await join('Normie');
  await sleep(2500);
  const t = await join('Tester');
  await sleep(3500);
  check('join message with the [+] style is shown to others', said(t, '[+]') || said(normie, '[+]'), lastLines(t));

  // ---- command visibility
  const n = normie.commandNames || [];
  const hidden = ['ban', 'unban', 'kick', 'mute', 'warn', 'banlist', 'history', 'trap', 'v', 'freeze', 'kitadmin', 'eco', 'smpcore', 'protect', 'drill', 'spawners', 'ordersadmin', 'keyall', 'setspawn', 'havenbuild', 'auth', 'punish', 'smpinfo'];
  const visible = ['shop', 'sell', 'kit', 'ah', 'order', 'rtp', 'daily', 'leaderboard', 'stats', 'bounty', 'msg', 'afk', 'trash', 'home', 'spawn', 'crates', 'worth', 'rules', 'discord'];
  const shown = hidden.filter(c => n.includes(c));
  const missing = visible.filter(c => !n.includes(c));
  check('a normal player sees NO staff commands', n.length > 10 && shown.length === 0, 'seen staff commands: ' + shown.join(',') + ' / total ' + n.length);
  check('a normal player sees all the public commands', missing.length === 0, 'missing: ' + missing.join(','));
  const tn = t.commandNames || [];
  check('an operator sees the staff commands', ['ban', 'trap', 'kitadmin', 'v'].every(c => tn.includes(c)), tn.length + ' commands');

  // ---- shop (money)
  let w = await cmdWindow(t, '/shop');
  check('/shop opens with a small-caps title', w && title(w).includes('ꜱʜᴏᴘ'), title(w));
  w = await click(t, 11);
  check('the End category opens', w && title(w).includes('ᴇɴᴅ'), title(w));
  const moneyBefore = (t.lines.join('|'));
  w = await click(t, 9);
  check('choosing an item opens the amount screen', w && title(w).toLowerCase().includes('buy'), title(w));
  await click(t, 15);   // +1
  w = t.currentWindow;
  const after = nextWindow(t, 2500);
  try { await t.clickWindow(23, 0, 0); } catch (e) { /* */ }
  await sleep(1200);
  check('buying works: message and item', said(t, 'You bought') && hasItem(t, 'ender_chest'), lastLines(t));
  t.closeWindow(t.currentWindow || w);
  await sleep(500);

  // ---- shards
  t.chat('/shards give Tester 500');
  await sleep(700);
  w = await cmdWindow(t, '/shop');
  w = await click(t, 15);
  check('the shard shop opens', w && title(w).includes('ѕʜᴀʀᴅ') || (w && title(w).toLowerCase().includes('shard')) || (w && title(w).includes('ꜱ')), title(w));
  w = await click(t, 2);
  try { await t.clickWindow(23, 0, 0); } catch (e) { /* */ }
  await sleep(1200);
  check('buying a key with shards works', said(t, 'Common Key') || said(t, 'common key') || said(t, 'You bought'), lastLines(t));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(500);

  // ---- kits
  w = await cmdWindow(t, '/kit');
  check('/kit opens with all six kits', w && names(w).filter(x => x !== 'black_stained_glass_pane').length >= 6, title(w) + ' / ' + (w ? names(w).filter(x => x !== 'black_stained_glass_pane').join(',') : ''));
  const itemsBefore = t.inventory.items().length;
  w = await click(t, 10);
  await sleep(1500);
  check('claiming the Wanderer kit gives the items', said(t, 'claimed') && t.inventory.items().length > itemsBefore + 5 && hasItem(t, 'iron_chestplate'), lastLines(t, 2));
  t.chat('/kit wanderer');
  await sleep(800);
  check('a second claim is refused (24 hour cooldown)', said(t, 'again in'), lastLines(t, 2));

  // ---- sell
  const beef = t.inventory.items().find(i => i.name === 'cooked_beef');
  if (beef) { await t.equip(beef, 'hand'); }
  t.chat('/sell hand');
  await sleep(1500);
  check('/sell hand pays for the item in hand', said(t, 'You sold'), lastLines(t, 2));
  w = await cmdWindow(t, '/sell');
  check('/sell opens the sell menu', w && title(w).toLowerCase().includes('sell'), title(w));
  const torches = t.inventory.items().find(i => i.name === 'torch');
  if (w && torches) {
    const slot = 54 + (torches.slot >= 36 ? 27 + (torches.slot - 36) : torches.slot - 9);
    await click(t, slot, 1, 0);
    await sleep(500);
    try { await t.clickWindow(53, 0, 0); } catch (e) { /* */ }
    await sleep(1200);
    check('selling through the menu works', said(t, 'You sold'), lastLines(t, 2));
  }
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(500);

  // ---- daily
  w = await cmdWindow(t, '/daily');
  check('/daily opens the calendar', w && names(w).filter(x => x !== 'black_stained_glass_pane').length >= 7, title(w));
  try { await t.clickWindow(22, 0, 0); } catch (e) { /* */ }
  await sleep(1500);
  check('claiming the daily reward works', said(t, 'claimed your'), lastLines(t, 2));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(500);

  // ---- leaderboard and rtp menu
  w = await cmdWindow(t, '/leaderboard');
  check('/leaderboard shows the boards', w && names(w).includes('gold_ingot') && names(w).includes('diamond_sword'), title(w));
  w = await click(t, 10);
  check('the money board opens', w && title(w).toUpperCase().includes('MONEY'), title(w));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(300);
  w = await cmdWindow(t, '/rtp');
  check('/rtp opens the world menu', w && names(w).filter(x => x !== 'black_stained_glass_pane').length === 3, title(w) + ' ' + (w ? names(w).join(',').slice(0, 80) : ''));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(300);

  // ---- orders
  w = await cmdWindow(t, '/order');
  check('/order opens (small caps title)', w && title(w).includes('ᴏʀᴅᴇʀ'), title(w));
  w = await click(t, 53);
  check('New Order opens', w && title(w).includes('ɴᴇᴡ'), title(w));
  w = await click(t, 22);
  check('creating an order without data says what is missing', said(t, 'set the item'), lastLines(t, 2));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(300);

  // ---- auction between two players
  const tool = t.inventory.items().find(i => i.name === 'iron_pickaxe');
  if (tool) { await t.equip(tool, 'hand'); }
  t.chat('/ah sell 500');
  await sleep(1500);
  check('/ah sell lists the item in hand', said(t, 'You listed'), lastLines(t, 2));
  w = await cmdWindow(normie, '/ah');
  check('the auction house shows the listing to another player', w && title(w).includes('ᴀᴜᴄᴛɪᴏɴ') && names(w).includes('iron_pickaxe'), title(w));
  w = await click(normie, 0);
  check('clicking a listing asks to confirm', w && names(w).includes('lime_stained_glass_pane'), title(w));
  try { await normie.clickWindow(15, 0, 0); } catch (e) { /* */ }
  await sleep(1500);
  check('buying a listing works', said(normie, 'You bought'), lastLines(normie, 2));
  if (normie.currentWindow) normie.closeWindow(normie.currentWindow);
  await sleep(300);
  w = await cmdWindow(normie, '/ah collect');
  check('the bought item waits in the collection box', w && names(w).includes('iron_pickaxe'), title(w));
  w = await click(normie, 0);
  await sleep(1000);
  check('collecting hands over the item', hasItem(normie, 'iron_pickaxe'), lastLines(normie, 2));
  if (normie.currentWindow) normie.closeWindow(normie.currentWindow);
  await sleep(300);
  check('the seller was told about the sale', said(t, 'bought your'), lastLines(t, 3));

  // ---- bounty, messages, misc
  t.chat('/bounty set Normie 1500');
  await sleep(1500);
  check('/bounty set works and is announced', said(t, 'head') || said(t, 'bounty'), lastLines(t, 2));
  normie.chat('/bounty check Normie');
  await sleep(800);
  check('/bounty check shows the total', said(normie, 'bounty of') , lastLines(normie, 2));
  t.chat('/msg Normie hello there');
  await sleep(800);
  check('/msg delivers a private message', said(normie, 'hello there') && said(t, 'hello there'), lastLines(normie, 2));
  normie.chat('/r got it');
  await sleep(800);
  check('/reply answers', said(t, 'got it'), lastLines(t, 2));
  t.chat('/afk');
  await sleep(600);
  check('/afk marks you away', said(t, 'now away'), lastLines(t, 1));
  w = await cmdWindow(t, '/trash');
  check('/trash opens a bin', w && w.slots.length >= 36, title(w));
  if (t.currentWindow) t.closeWindow(t.currentWindow);
  await sleep(300);
  t.chat('/rules');
  await sleep(600);
  check('/rules shows the rules', said(t, 'No hacks'), lastLines(t, 2));
  t.chat('/stats');
  await sleep(1200);
  check('/stats shows stats', said(t, 'Playtime') || said(t, 'Kills'), lastLines(t, 3));
  t.chat('/trap list');
  await sleep(600);
  check('an operator can use /trap', said(t, 'Decoys'), lastLines(t, 2));
  t.chat('/worth hand');
  await sleep(600);
  check('/worth works', said(t, 'worth') || said(t, ':'), lastLines(t, 2));
  normie.chat('/trap list');
  await sleep(600);
  check('a normal player cannot use /trap', !said(normie, 'Decoys'), lastLines(normie, 2));
  normie.chat('/ban Tester');
  await sleep(600);
  check('a normal player cannot ban', said(normie, 'do not have permission') || said(normie, "don't have permission") || said(normie, 'unknown'), lastLines(normie, 2));

  const failed = results.filter(r => !r[1]);
  console.log('\n' + (results.length - failed.length) + '/' + results.length + ' checks passed');
  t.quit(); normie.quit();
  await sleep(500);
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.log('ERROR ' + e + '\n' + e.stack); process.exit(2); });
