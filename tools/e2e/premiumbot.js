// End-to-end test of SMP-Auth's own bought-account check against a throwaway server and fakemojang.js.
// PHASE=1: auto-detect on (names that a bought account has are recognised); PHASE=2: auto-detect off (players type /premium once).
const mineflayer = require(process.env.BOTDIR + '/node_modules/mineflayer');
const crypto = require('crypto');
const PORT = parseInt(process.env.PORT || '25591');
const MOJANG = 'http://127.0.0.1:' + (process.env.MOJANG_PORT || '25592');
const PHASE = process.env.PHASE || '1';
const results = [];
function check(name, ok, extra) { results.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : '')); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const idOf = name => { const h = crypto.createHash('md5').update('mojang:' + name.toLowerCase()).digest(); h[6] = (h[6] & 0x0f) | 0x40; h[8] = (h[8] & 0x3f) | 0x80; return h.toString('hex'); };
const dashed = h => h.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5');

function join(name, bought) {
  return new Promise(resolve => {
    const opts = { host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', hideErrors: true };
    if (bought) Object.assign(opts, { auth: 'mojang', authServer: MOJANG, sessionServer: MOJANG, password: 'whatever', profilesFolder: false });
    else opts.auth = 'offline';
    const bot = mineflayer.createBot(opts);
    bot.lines = [];
    bot.kicked = null;
    bot.on('messagestr', m => bot.lines.push(m));
    bot.on('kicked', reason => { bot.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason); });
    bot.on('error', e => { bot.err = String(e); });
    bot.once('spawn', () => resolve(bot));
    bot.once('end', () => { if (!bot.entity) resolve(bot); });
    setTimeout(() => resolve(bot), 20000);
  });
}
const said = (bot, text) => bot.lines.some(l => l.toLowerCase().includes(text.toLowerCase()));
const uuidOf = bot => (bot.player && bot.player.uuid) || (bot.entity && bot.entity.uuid) || '';

(async () => {
  if (PHASE === '1') {
    // a normal player without a bought account
    let c = await join('Casual', false);
    await sleep(1500);
    check('a player without a bought account is asked to register', said(c, 'register'), c.lines.join(' | ').slice(0, 100) + (c.kicked || ''));
    check('... with a name based (version 3) UUID', uuidOf(c)[14] === '3', uuidOf(c));
    c.quit();
    await sleep(800);

    // a player with a bought account: walks in
    const p = await join('Prem1', true);
    await sleep(2500);
    check('a bought account joins', !!p.entity && p.kicked === null, String(p.kicked || p.err || ''));
    check('... is not asked to register or log in', !said(p, 'register') && !said(p, 'log in with'), p.lines.join(' | ').slice(0, 160));
    check('... is told it was verified', said(p, 'verified as a bought account'), p.lines.join(' | ').slice(0, 160));
    check('... gets its real Mojang UUID (version 4)', uuidOf(p) === dashed(idOf('Prem1')), uuidOf(p) + ' vs ' + dashed(idOf('Prem1')));
    p.chat('/gamemode creative');
    await sleep(800);
    check('... can play right away (not locked)', !said(p, 'log in first'), p.lines.slice(-2).join(' | '));
    p.quit();
    await sleep(1000);

    // somebody without a bought account trying to use that name
    const i = await join('Prem1', false);
    await sleep(2500);
    check('nobody can use the name of a bought account without owning it', i.kicked !== null && i.kicked.toLowerCase().includes('bought minecraft account'), String(i.kicked).slice(0, 140));
    // ... also when the name was never seen on this server
    const j = await join('Prem2', false);
    await sleep(2500);
    check('... even when the account never joined this server', j.kicked !== null && j.kicked.toLowerCase().includes('bought minecraft account'), String(j.kicked).slice(0, 140));

    // the owner of such a name still gets in afterwards (no lock-out)
    const k = await join('Prem2', true);
    await sleep(2500);
    check('the owner of that name walks in', !!k.entity && uuidOf(k) === dashed(idOf('Prem2')), String(k.kicked || k.err || ''));
    k.quit();
    await sleep(800);

    // capitals: the client says one thing, Mojang another
    const l = await join('prem1', true);
    await sleep(2500);
    // mojang auth gives the client its proper name, so the join is as "Prem1"
    check('the name with its right capitals is used', !!l.entity && l.username === 'Prem1', l.username);
    l.quit();
    await sleep(500);
  } else {
    // auto-detect is off: a bought account first plays like everybody, then types /premium
    let a = await join('Prem3', false);
    await sleep(1500);
    check('[no auto-detect] a new name just registers', said(a, 'register'), a.lines.join(' | ').slice(0, 100) + (a.kicked || ''));
    a.chat('/premium');
    await sleep(600);
    check('[no auto-detect] /premium needs a login first', said(a, 'log in first'));
    a.chat('/register Tr0ub4dor&3x Tr0ub4dor&3x');
    await sleep(3000);
    check('[no auto-detect] registering works', said(a, 'account was created'));
    a.chat('/premium');
    await sleep(1500);
    check('/premium marks the account', said(a, 'bought account') && said(a, 'Done'), a.lines.slice(-2).join(' | ').slice(0, 160));
    a.chat('/premium');
    await sleep(600);
    check('/premium twice says it is done already', said(a, 'already'));
    a.quit();
    await sleep(1500);

    let b = await join('Prem3', false);
    await sleep(2500);
    check('after /premium, joining without proving the account is refused', b.kicked !== null && b.kicked.toLowerCase().includes('bought minecraft account'), String(b.kicked).slice(0, 140));

    b = await join('Prem3', true);
    await sleep(2500);
    check('after /premium, the real account walks in without a password', !!b.entity && !said(b, 'log in with') && said(b, 'verified'), b.lines.join(' | ').slice(0, 160) + (b.kicked || b.err || ''));
    check('... with its Mojang UUID', uuidOf(b) === dashed(idOf('Prem3')), uuidOf(b));
    b.quit();
    await sleep(800);

    // a name that no bought account has cannot use /premium
    const c = await join('Casual2', false);
    await sleep(1500);
    c.chat('/register Tr0ub4dor&3x Tr0ub4dor&3x');
    await sleep(3000);
    c.chat('/premium');
    await sleep(1500);
    check('/premium is refused when no bought account has the name', said(c, 'no bought minecraft account'), c.lines.slice(-2).join(' | ').slice(0, 160));
    c.quit();
  }
  const failed = results.filter(r => !r[1]);
  console.log('\n' + (results.length - failed.length) + '/' + results.length + ' checks passed');
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.log('ERROR ' + e); process.exit(2); });
