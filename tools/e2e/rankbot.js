// End-to-end test of the /ranks menu (SMP-Info) with a real client. Needs a throwaway server with only SMP-Info (online-mode=false).
const BOT = process.env.BOTDIR;
const mineflayer = require(BOT + '/node_modules/mineflayer');
const nbt = require(BOT + '/node_modules/prismarine-nbt');
const PORT = parseInt(process.env.PORT || '25595');
const results = [];
function check(name, ok, extra) { results.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : '')); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const flat = x => (x && typeof x === 'object') ? ((x.text || '') + (x.extra || []).map(flat).join('')) : String(x == null ? '' : x);
const text = x => { try { return flat(nbt.simplify(x)); } catch (e) { return JSON.stringify(x); } };

function nextWindow(bot, ms = 5000) {
  return new Promise(res => {
    const t = setTimeout(() => res(null), ms);
    bot.once('windowOpen', w => { clearTimeout(t); setTimeout(() => res(w), 600); });
  });
}

(async () => {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'Buyer', version: '1.21.11', auth: 'offline', hideErrors: true });
  bot.lines = [];
  bot.on('messagestr', m => bot.lines.push(m));
  bot.on('error', () => {});
  await new Promise(r => bot.once('spawn', r));
  await sleep(1500);
  const opening = nextWindow(bot);
  bot.chat('/ranks');
  const w = await opening;
  check('/ranks opens a menu', !!w, w ? text(w.title) : '');
  const items = w ? w.slots.slice(0, 27) : [];
  const named = items.filter(i => i && i.name !== 'black_stained_glass_pane');
  check('it shows the five ranks and the store button', named.length === 6, named.map(i => i.name).join(','));
  check('with the right icons', ['iron_ingot', 'gold_ingot', 'diamond', 'emerald', 'netherite_ingot', 'emerald'].every((n, k) => named[k] && named[k].name === n), named.map(i => i.name).join(','));
  const first = items[10];
  const lore = first && first.components ? JSON.stringify(first.components) : JSON.stringify(first && first.nbt);
  check('a rank shows its price and perks', lore.includes('2.99') && lore.includes('homes'), lore.slice(0, 160));
  const closed = new Promise(res => bot.once('windowClose', () => res(true)));
  bot.simpleClick.leftMouse(10);
  const didClose = await Promise.race([closed, sleep(3000).then(() => false)]);
  await sleep(800);
  check('clicking a rank closes the menu', didClose);
  check('and sends the store link', bot.lines.some(l => l.includes('https://your-store.example')), bot.lines.slice(-2).join(' | ').slice(0, 160));
  // taking items out is impossible
  const again = nextWindow(bot);
  bot.chat('/rank');
  const w2 = await again;
  check('/rank is an alias', !!w2);
  try { await bot.clickWindow(11, 0, 1); } catch (e) { /* closed by the click */ }
  await sleep(500);
  check('nothing can be taken out', !bot.inventory.items().some(i => i.name === 'gold_ingot'));
  const failed = results.filter(r => !r[1]);
  console.log('\n' + (results.length - failed.length) + '/' + results.length + ' checks passed');
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.log('ERROR ' + e); process.exit(2); });
