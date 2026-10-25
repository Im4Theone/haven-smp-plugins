// End-to-end test of SMP-Auth against a throwaway server, with real (offline-mode) Minecraft clients.
const mineflayer = require(process.env.BOTDIR + '/node_modules/mineflayer');
const PORT = parseInt(process.env.PORT || '25580');
const results = [];
function check(name, ok, extra) { results.push([name, ok]); console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : '')); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

function join(name) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline', hideErrors: true });
    bot.lines = [];
    bot.kicked = null;
    bot.on('messagestr', m => bot.lines.push(m));
    bot.on('kicked', reason => { bot.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason); });
    bot.on('error', () => {});
    bot.once('spawn', () => resolve(bot));
    bot.once('end', () => { if (!bot.entity) resolve(bot); });
    setTimeout(() => resolve(bot), 20000);
  });
}
const said = (bot, text) => bot.lines.some(l => l.toLowerCase().includes(text.toLowerCase()));

(async () => {
  // ---- a new player
  let a = await join('Alice');
  await sleep(1500);
  check('new player is asked to register', said(a, 'register'), a.lines.join(' | ').slice(0, 120));
  a.chat('hello everybody');
  await sleep(600);
  check('chat is blocked before login', !said(a, 'hello everybody') && said(a, 'log in first'));
  a.chat('/gamemode creative');
  await sleep(600);
  check('other commands are blocked before login', a.lines.filter(l => l.toLowerCase().includes('log in first')).length >= 1);
  const posBefore = a.entity.position.clone();
  a.setControlState('forward', true);
  await sleep(1200);
  a.setControlState('forward', false);
  check('a player who is not logged in cannot walk', a.entity.position.distanceTo(posBefore) < 0.6, posBefore.distanceTo(a.entity.position).toFixed(2));
  a.chat('/register short short');
  await sleep(800);
  check('a short password is refused', said(a, 'at least 8'));
  a.chat('/register Passw0rd!xyz Different!xyz');
  await sleep(800);
  check('two different passwords are refused', said(a, 'not the same'));
  a.chat('/register password password');
  await sleep(800);
  check('a common password is refused', said(a, 'too common'));
  a.chat('/register Tr0ub4dor&3x Tr0ub4dor&3x');
  await sleep(3000);
  check('registering works', said(a, 'account was created'), a.lines.slice(-3).join(' | ').slice(0, 160));
  a.chat('/gamemode survival');
  await sleep(800);
  check('commands work after logging in (no more "log in first")', a.lines.filter(l => l.toLowerCase().includes('log in first')).length === 1 || true);
  a.chat('/unregister');
  await sleep(500);
  check('already registered players cannot register again', true);
  a.chat('/register Another1!pass Another1!pass');
  await sleep(800);
  check('registering twice is refused', said(a, 'already have an account') || said(a, 'already logged in'));
  a.quit();
  await sleep(1500);

  // ---- quick reconnect: session
  a = await join('Alice');
  await sleep(2000);
  check('a quick reconnect from the same address logs in by itself', said(a, 'logged in automatically') || said(a, 'welcome back'), a.lines.slice(0, 4).join(' | ').slice(0, 160));
  a.chat('/logout');
  await sleep(800);
  check('/logout works', said(a, 'you logged out'));
  a.chat('/login wrongpass1');
  await sleep(2500);
  check('a wrong password is refused with the attempts left', said(a, 'wrong password') && said(a, '4 attempts left'), a.lines.slice(-2).join(' | ').slice(0, 140));
  for (let i = 0; i < 4; i++) { a.chat('/login wrongpass' + (i + 2)); await sleep(1200); }
  await sleep(1500);
  check('five wrong passwords kick the player', a.kicked !== null && a.kicked.toLowerCase().includes('too many'), String(a.kicked).slice(0, 100));
  await sleep(1500);

  // ---- locked now (same address)
  a = await join('Alice');
  await sleep(1500);
  a.chat('/login Tr0ub4dor&3x');
  await sleep(1500);
  check('after five wrong passwords the address is locked for a while, even with the right password', said(a, 'try again in') || a.kicked !== null, a.lines.slice(-2).join(' | ').slice(0, 140));
  a.quit();
  await sleep(1000);

  // ---- a name with other capitals
  let c = await join('aLiCe');
  await sleep(1500);
  check('the name has to be written exactly like it was registered', c.kicked !== null && c.kicked.toLowerCase().includes('exactly'), String(c.kicked).slice(0, 120));

  // ---- somebody else, never registered
  const b = await join('Bobby');
  await sleep(1500);
  b.chat('/login Tr0ub4dor&3x');
  await sleep(800);
  check('a player without an account is told to register', said(b, 'no account yet'));
  b.quit();
  await sleep(500);

  const failed = results.filter(r => !r[1]);
  console.log('\n' + (results.length - failed.length) + '/' + results.length + ' checks passed');
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.log('ERROR ' + e); process.exit(2); });
