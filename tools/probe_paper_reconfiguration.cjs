/* Loopback client for repeated Paper configuration checks. */
const path = require('node:path');
const {encodeClick} = require('./paper_probe_helpers.cjs');
const options = JSON.parse(process.argv[2]);
if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) throw new Error('Invalid loopback port');
if (!['1.21.8', '26.1'].includes(options.version)) throw new Error('Invalid test version');
if (!Number.isInteger(options.hold) || options.hold < 0 || options.hold > 300) throw new Error('Invalid hold');
const protocol = require(path.resolve(options.modules, 'minecraft-protocol'));
const nbt = require(path.resolve(options.modules, 'prismarine-nbt'));
const client = protocol.createClient({host: '127.0.0.1', port: options.port,
  username: 'ReconfigureProbe', version: options.version, auth: 'offline'});
const started = Date.now();
let dialogs = 0, plays = 0, accepted = 0, positioned = false, failed = false, finished = false;
const report = (event, details = {}) => console.log(JSON.stringify({event, seconds: (Date.now() - started) / 1000, ...details}));
client.on('state', (state, previous) => {
  if (state === 'configuration') positioned = false;
  report('state', {state, previous});
});
client.on('show_dialog', packet => {
  dialogs++;
  if (client.state !== 'configuration') { failed = true; return client.end('Unexpected dialog stage'); }
  report('dialog', {dialogs});
  const text = JSON.stringify(nbt.simplify(packet.dialog));
  const action = (text.match(/consentgate:accept\/[a-z0-9/-]+/g) || [])[0];
  const inputs = [...new Set(text.match(/document_[0-9]+/g) || [])];
  if (!action || !inputs.length) throw new Error('Missing consent controls');
  setTimeout(() => { accepted++; client.writeRaw(encodeClick(action, inputs)); }, options.hold * 1000);
});
client.on('ping', packet => client.write('pong', {id: packet.id}));
client.on('position', packet => {
  if (packet.teleportId != null) client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (positioned) return;
  positioned = true;
  plays++;
  const expectedAcceptances = 1;
  if (accepted !== expectedAcceptances || dialogs !== expectedAcceptances) { failed = true; return client.end('Unexpected admission or duplicate consent'); }
  report('playing', {plays, dialogs});
  if (plays === 4) setTimeout(() => { finished = true; client.end('Probe complete'); }, 1000);
});
client.on('disconnect', packet => report('disconnect', {reason: packet.reason}));
client.on('kick_disconnect', packet => report('disconnect', {reason: packet.reason}));
client.on('error', error => { failed = true; report('error', {message: error.message}); });
client.on('end', reason => {
  const passed = finished && !failed && plays === 4 && accepted === 1;
  report('result', {passed, plays, dialogs, accepted, reason});
  process.exit(passed ? 0 : 1);
});
setTimeout(() => { report('error', {message: 'Probe deadline'}); process.exit(1); }, (options.hold + 100) * 1000);
