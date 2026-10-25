// Sends one console command to a throwaway server through RCON: node rcon.js <port> <password> <command...>
const net = require('net');
const [port, password, ...cmd] = process.argv.slice(2);
const packet = (id, type, body) => {
  const b = Buffer.from(body, 'utf8');
  const out = Buffer.alloc(14 + b.length);
  out.writeInt32LE(10 + b.length, 0);
  out.writeInt32LE(id, 4);
  out.writeInt32LE(type, 8);
  b.copy(out, 12);
  return out;
};
const s = net.connect(parseInt(port), '127.0.0.1', () => s.write(packet(1, 3, password)));
let step = 0;
s.on('data', d => {
  if (step === 0) { step = 1; s.write(packet(2, 2, cmd.join(' '))); return; }
  console.log(d.slice(12, d.length - 2).toString('utf8'));
  s.end();
});
s.on('error', e => { console.log('rcon error: ' + e.message); process.exit(1); });
s.on('close', () => process.exit(0));
