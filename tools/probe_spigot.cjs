/* Loopback-only bot for the Spigot hold. Reports packets as JSON lines and takes one JSON action per stdin line. */
const path = require('node:path');
const readline = require('node:readline');
const {encodeClick} = require('./paper_probe_helpers.cjs');
const [modules, port, name, version, mode] = process.argv.slice(2);
if (!modules || !/^\d+$/.test(port) || !/^[A-Za-z0-9_]{1,16}$/.test(name || '') || !['1.21.8', '26.1'].includes(version)
    || ![undefined, 'drop'].includes(mode)) {
  throw new Error('Usage: probe_spigot.cjs <node_modules> <port> <offline name> <1.21.8|26.1> [drop]');
}
const protocol = require(path.resolve(modules, 'minecraft-protocol'));
const nbt = require(path.resolve(modules, 'prismarine-nbt'));
// Serverbound custom_click_action IDs (minecraft-data 3.117.0). The payload layout is the same in both stages.
const CLICK = {configuration: 0x08, play: {'1.21.8': 0x41, '26.1': 0x44}[version]};
const client = protocol.createClient({host: '127.0.0.1', port: Number(port), username: name, version, auth: 'offline'});
const started = Date.now();
let actions = [], inputs = [], position = null, sequence = 1;
const report = (event, details = {}) => console.log(JSON.stringify({event, seconds: (Date.now() - started) / 1000, ...details}));
const text = value => JSON.stringify(value && value.type ? nbt.simplify(value) : value);

client.on('show_dialog', packet => {
  const raw = packet.dialog && packet.dialog.data ? packet.dialog.data : packet.dialog;
  const serialized = JSON.stringify(nbt.simplify(raw));
  actions = [...new Set(serialized.match(/consentgate:[a-z0-9/._-]+/g) || [])];
  inputs = [...new Set(serialized.match(/document_[0-9]+/g) || [])];
  report('dialog', {state: client.state, actions, inputs, after: (serialized.match(/"after_action":"([a-z_]+)"/) || [])[1], text: serialized});
});
client.on('clear_dialog', () => report('clear_dialog', {state: client.state}));
client.on('login', () => { position = null; report('play'); });
// drop: leave right after the pre-login check, while the server configures the connection.
client.on('state', state => { if (mode === 'drop' && state === 'configuration') client.end('Dropped during configuration'); });
client.on('position', packet => {
  if (packet.teleportId != null) client.write('teleport_confirm', {teleportId: packet.teleportId});
  // Like a real client once terrain has loaded. Until then the server ignores movement and blocks damage.
  if (!position) client.write('player_loaded', {});
  position = {x: packet.x, y: packet.y, z: packet.z};
  report('position', position);
});
client.on('system_chat', packet => report('chat', {text: text(packet.content)}));
client.on('player_chat', packet => report('chat', {text: packet.plainMessage, sender: packet.senderUuid}));
client.on('profileless_chat', packet => report('chat', {text: text(packet.message)}));
client.on('player_info', packet => {
  for (const entry of packet.data || []) if (entry.player) report('player_info', {name: entry.player.name, uuid: entry.uuid});
});
client.on('player_remove', packet => report('player_remove', {players: packet.players}));
client.on('spawn_entity', packet => report('spawn_entity', {uuid: packet.objectUUID, type: packet.type}));
client.on('update_health', packet => report('health', {health: packet.health, food: packet.food}));
client.on('block_change', packet => report('block_change', {location: packet.location, type: packet.type}));
client.on('open_window', packet => report('open_window', {id: packet.windowId}));
client.on('kick_disconnect', packet => report('kick', {reason: text(packet.reason)}));
client.on('disconnect', packet => report('kick', {reason: text(packet.reason)}));
client.on('error', error => report('error', {message: error.message}));
client.on('end', reason => { report('end', {reason}); process.exit(0); });

/** Checkbox payload variants: `byte` is what a real client sends; the rest must never release the player. */
function click(prefix, values, token) {
  let action = actions.find(value => value.startsWith('consentgate:' + prefix + '/'));
  if (!action) { report('error', {message: 'Missing action: ' + prefix}); return; }
  if (token) action = action.replace(/[^/]+$/, token);
  const packet = encodeClick(action, values === 'none' ? [] : inputs, CLICK[client.state] ?? CLICK.play, values === 'none' ? 'byte' : values);
  client.writeRaw(packet);
  report('clicked', {action: prefix, values, token: !!token, state: client.state});
}

readline.createInterface({input: process.stdin}).on('line', line => {
  const command = JSON.parse(line);
  try {
    switch (command.do) {
      case 'click': click(command.action, command.values || 'byte', command.token); break;
      case 'move': client.write('position', {x: position.x + (command.dx || 0), y: position.y + (command.dy || 0),
        z: position.z + (command.dz || 0), flags: {onGround: true, hasHorizontalCollision: false}}); report('moved', command); break;
      case 'chat': client._signedChat(command.text); report('chatted', command); break;
      // Movement keys, as a client sends them only with no screen open.
      case 'input': client.write('player_input', {inputs: {forward: !!command.forward, backward: false, left: false, right: false,
        jump: false, shift: false, sprint: false}}); report('input', command); break;
      case 'loaded': client.write('player_loaded', {}); report('loaded'); break;
      // Steering, as the rider of a boat sends it.
      case 'vehicle_move': client.write('vehicle_move', {x: command.x, y: command.y, z: command.z, yaw: 0, pitch: 0, onGround: true});
        report('vehicle_moved', command); break;
      case 'dig': {
        const location = {x: Math.floor(position.x), y: Math.floor(position.y) - 1, z: Math.floor(position.z)};
        client.write('block_dig', {status: 0, location, face: 1, sequence: sequence++});
        report('dug', {location});
        break;
      }
      case 'drop': client.write('block_dig', {status: 4, location: {x: 0, y: 0, z: 0}, face: 0, sequence: 0}); report('dropped'); break;
      case 'quit': client.end('Probe complete'); break;
      default: report('error', {message: 'Unknown action ' + command.do});
    }
  } catch (error) { report('error', {message: error.message}); }
});
setTimeout(() => { report('error', {message: 'Bot deadline reached'}); process.exit(1); }, 600 * 1000);
