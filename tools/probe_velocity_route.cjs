/* Synthetic Java client for the local two-backend routing check. */
const path = require('node:path');
const {encodeClick} = require('./paper_probe_helpers.cjs');
const options = JSON.parse(process.argv[2]);
if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535) throw new Error('Invalid loopback port');
if (!['127.0.0.1', 'forced.test'].includes(options.host)) throw new Error('Invalid test virtual host');
if (!/^[A-Za-z0-9_]{1,16}$/.test(options.name)) throw new Error('Invalid test identity');
const protocol = require(path.resolve(options.modules, 'minecraft-protocol'));
const nbt = require(path.resolve(options.modules, 'prismarine-nbt'));
const client = protocol.createClient({host: '127.0.0.1', port: options.port, fakeHost: options.host,
  username: options.name, version: '1.21.8', auth: 'offline'});
const started = Date.now();
let dialog = false, accepted = false, playing = false, switching = false, moved = false, completed = false, failed = false;
const report = (event, details = {}) => console.log(JSON.stringify({event, time: Date.now(), seconds: (Date.now() - started) / 1000, ...details}));
const fail = message => { failed = true; report('failure', {message}); client.end('Probe failed'); };
const finish = () => { completed = true; client.end('Probe complete'); };
client.on('state', (state, previous) => report('state', {state, previous}));
client.on('show_dialog', packet => {
  if (client.state !== 'configuration') return fail('Dialog arrived outside configuration');
  if (options.expected !== 'accepted') return fail('Unexpected consent dialog');
  if (dialog) return fail('Unexpected repeated consent dialog');
  dialog = true;
  report('dialog');
  const text = JSON.stringify(nbt.simplify(packet.dialog));
  const action = (text.match(/consentgate:accept\/[a-z0-9/-]+/g) || [])[0];
  const inputs = [...new Set(text.match(/document_[0-9]+/g) || [])];
  if (!action || !inputs.length) return fail('Missing acceptance action or checkboxes');
  setTimeout(() => {
    accepted = true;
    report('accepted');
    client.writeRaw(encodeClick(action, inputs));
  }, 5000);
});
client.on('ping', packet => client.write('pong', {id: packet.id}));
client.on('position', packet => {
  if (packet.teleportId != null) client.write('teleport_confirm', {teleportId: packet.teleportId});
  if (!playing) {
    playing = true;
    if (options.expected === 'accepted' && !accepted) return fail('World entry before acceptance');
    if (options.expected === 'unavailable') return fail('Unexpected world entry');
    report('playing');
    setTimeout(() => {
      if (options.switch) {
        switching = true;
        client.chat('/server secondary');
        report('switch_sent');
      } else finish();
    }, 1500);
  } else if (switching && !moved) {
    moved = true;
    report('switched');
    setTimeout(finish, 1500);
  }
});
client.on('disconnect', packet => report('disconnect', {reason: packet.reason}));
client.on('kick_disconnect', packet => report('disconnect', {reason: packet.reason}));
client.on('error', error => {
  // The startup-denial path can close the socket before the bot consumes a disconnect packet.
  // The parent also requires the exact denial in the proxy log and zero backend connections.
  if (options.expected === 'unavailable' && error.code === 'ECONNRESET' && !dialog && !playing) report('reset');
  else fail(error.message);
});
client.on('end', () => {
  const passed = !failed && (options.expected === 'unavailable'
    ? !dialog && !accepted && !playing : completed && playing && (!options.switch || moved));
  report('result', {passed, dialog, accepted, playing, moved});
  process.exit(passed ? 0 : 1);
});
setTimeout(() => { report('failure', {message: 'Routing probe deadline'}); process.exit(1); }, 60000);
