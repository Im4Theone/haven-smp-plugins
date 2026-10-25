// A pretend Mojang for testing SMP-Auth's bought-account check without a real account.
// Serves what a game client and the server ask: /authenticate (client login), /session/minecraft/join (client), /session/minecraft/hasJoined (server),
// /lookup/name/<name> (does a bought account have this name). Names in PREMIUM (comma separated) "exist".
const http = require('http');
const crypto = require('crypto');
const PORT = parseInt(process.env.MOJANG_PORT || '25592');
const PREMIUM = (process.env.PREMIUM || 'Prem1,Prem2,Prem3,Notch').split(',');

// a stable version 4 UUID per name (what Mojang hands out)
function idOf(name) {
  const h = crypto.createHash('md5').update('mojang:' + name.toLowerCase()).digest();
  h[6] = (h[6] & 0x0f) | 0x40;
  h[8] = (h[8] & 0x3f) | 0x80;
  return h.toString('hex');
}
const properName = n => PREMIUM.find(p => p.toLowerCase() === n.toLowerCase());
const joins = new Map(); // server hash -> { id, name }
const log = [];

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
    log.push(req.method + ' ' + url.pathname);
    if (url.pathname === '/authenticate') {
      const o = JSON.parse(body);
      const name = properName(o.username);
      if (!name) return send(403, { error: 'ForbiddenOperationException', errorMessage: 'no such account' });
      const profile = { id: idOf(name), name };
      return send(200, { accessToken: 'token-' + name, clientToken: o.clientToken || 'c', selectedProfile: profile, availableProfiles: [profile], user: { id: profile.id } });
    }
    if (url.pathname === '/session/minecraft/join') {
      const o = JSON.parse(body);
      const name = PREMIUM.find(p => idOf(p) === o.selectedProfile && o.accessToken === 'token-' + p);
      if (!name) return send(403, { error: 'ForbiddenOperationException' });
      joins.set(o.serverId, { id: idOf(name), name });
      return send(204);
    }
    if (url.pathname === '/session/minecraft/hasJoined') {
      const j = joins.get(url.searchParams.get('serverId'));
      const asked = url.searchParams.get('username') || '';
      if (!j || j.name.toLowerCase() !== asked.toLowerCase()) return send(204);
      return send(200, { id: j.id, name: j.name, properties: [{ name: 'textures', value: Buffer.from('{"fake":true}').toString('base64'), signature: 'c2ln' }] });
    }
    if (url.pathname.startsWith('/lookup/name/')) {
      const name = properName(decodeURIComponent(url.pathname.slice('/lookup/name/'.length)));
      return name ? send(200, { id: idOf(name), name }) : send(404, { errorMessage: 'Not found' });
    }
    if (url.pathname === '/_log') return send(200, log);
    send(404, {});
  });
}).listen(PORT, '127.0.0.1', () => console.log('fake mojang on ' + PORT + ', bought accounts: ' + PREMIUM.join(',')));
module.exports = { idOf };
