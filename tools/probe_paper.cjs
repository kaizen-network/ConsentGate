/* Loopback-only Paper probe. Requires minecraft-protocol and prismarine-nbt. */
const path = require('node:path');
const {parseOptions, encodeClick} = require('./paper_probe_helpers.cjs');
const options = parseOptions(process.argv.slice(2));
const protocol = require(path.resolve(options.modules, 'minecraft-protocol'));
const nbt = require(path.resolve(options.modules, 'prismarine-nbt'));
const port = options.port;
const hold = options.hold;
const expected = options.expect;
const playSeconds = options['play-seconds'];
const client = protocol.createClient({host: '127.0.0.1', port, username: options.name,
  version: '26.1', auth: 'offline'});
const started = Date.now();
let actions = [], inputs = [], selected = false, acceptanceSent = false, joined = false, left = false;
let keepalives = 0, pings = 0, timer, lastHeartbeatLog = 0, firstDialogAt = 0, finished = false, hadError = false, previewComplete = false;
const report = (event, details = {}) => console.log(JSON.stringify({event, seconds: (Date.now() - started) / 1000, ...details}));
function click(prefix) {
  const action = actions.find(value => value.startsWith('consentgate:' + prefix + '/'));
  if (!action) throw new Error('Missing action: ' + prefix);
  client.writeRaw(encodeClick(action, inputs));
  report('action', {action: prefix});
}
client.on('show_dialog', packet => {
  if (client.state !== 'configuration' || !packet.dialog) return;
  const dialog = nbt.simplify(packet.dialog), serialized = JSON.stringify(dialog);
  actions = [...new Set(serialized.match(/consentgate:[a-z0-9/-]+/g) || [])];
  inputs = [...new Set(serialized.match(/document_[0-9]+/g) || [])];
  report('dialog', {title: dialog.title, inputs});
  if (expected === 'rejoin') { hadError = true; client.end('Unexpected consent on accepted rejoin'); return; }
  if (!firstDialogAt) firstDialogAt = Date.now();
  if (!selected && actions.some(value => value.startsWith('consentgate:language/'))) {
    selected = true;
    click('language/0');
  } else if (!timer && actions.some(value => value.startsWith('consentgate:accept/'))) {
    timer = setTimeout(() => {
      if (options.action === 'leave') { left = true; click('leave'); }
      else { acceptanceSent = true; click('accept'); }
    }, hold * 1000);
  }
});
client.on('keep_alive', () => {
  keepalives++;
  if (Date.now() - lastHeartbeatLog >= 10000) { lastHeartbeatLog = Date.now(); report('keepalive', {state: client.state, keepalives}); }
});
client.on('ping', packet => {
  pings++;
  if (options['reply-pings'] !== 'false') client.write('pong', {id: packet.id});
});
client.on('position', packet => { if (packet.teleportId != null) client.write('teleport_confirm', {teleportId: packet.teleportId}); });
client.on('success', () => report('authenticated', {uuid: client.uuid}));
client.on('login', () => {
  joined = true;
  report('play', {acceptanceSent, dialogWaitSeconds: firstDialogAt ? (Date.now() - firstDialogAt) / 1000 : null});
  if ((expected !== 'rejoin' && !acceptanceSent) || ['denied', 'unavailable', 'save-failed', 'preview'].includes(expected)) {
    hadError = true;
    report('failure', {reason: 'Unexpected admission'}); client.end('Probe failed'); return;
  }
  setTimeout(() => { finished = true; client.end('Probe complete'); }, playSeconds * 1000);
});
client.on('disconnect', packet => {
  previewComplete = JSON.stringify(packet.reason).includes('Preview complete. No acceptance was saved.');
  report('disconnect', {reason: packet.reason});
});
client.on('kick_disconnect', packet => report('kick', {reason: packet.reason}));
client.on('error', error => { hadError = true; report('error', {message: error.message}); });
client.on('end', reason => {
  clearTimeout(timer);
  const matched = expected === 'accepted' ? joined && acceptanceSent && finished
    : expected === 'rejoin' ? joined && !firstDialogAt && !acceptanceSent && finished
    : expected === 'unavailable' ? !joined && !firstDialogAt && !acceptanceSent
    : expected === 'save-failed' ? !joined && !!firstDialogAt && acceptanceSent
    : expected === 'preview' ? !joined && !!firstDialogAt && acceptanceSent && previewComplete
    : expected === 'denied' ? !!firstDialogAt && !joined && !acceptanceSent && (options.action !== 'leave' || left)
    : joined && acceptanceSent && !finished && pings > 0;
  const passed = matched && !hadError;
  report('result', {passed, expected, reason, joined, acceptanceSent, keepalives, pings});
  process.exit(passed ? 0 : 1);
});
setTimeout(() => { report('failure', {reason: 'Probe deadline reached'}); process.exit(1); }, (hold + playSeconds + 45) * 1000);
