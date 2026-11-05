const allowed = new Set(['modules', 'port', 'hold', 'name', 'expect', 'play-seconds', 'reply-pings', 'action', 'version']);

function parseOptions(args) {
  const options = {port: '25592', hold: '75', name: 'ConsentProbe', expect: 'accepted', 'play-seconds': '0', 'reply-pings': 'true', action: 'accept', version: '26.1'};
  const seen = new Set();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index].slice(2);
    if (!args[index].startsWith('--') || !allowed.has(key) || seen.has(key) || args[index + 1] == null || args[index + 1].startsWith('--')) throw new Error('Invalid or repeated option: ' + args[index]);
    seen.add(key);
    options[key] = args[index + 1];
  }
  if (!options.modules) throw new Error('Provide --modules with the dependency node_modules directory');
  if (!['1.21.8', '26.1'].includes(options.version)) throw new Error('Unsupported probe version');
  for (const key of ['port', 'hold', 'play-seconds']) {
    if (!/^\d+$/.test(options[key])) throw new Error('Invalid numeric option: ' + key);
    options[key] = Number(options[key]);
    if (!Number.isSafeInteger(options[key])) throw new Error('Invalid numeric option: ' + key);
  }
  if (options.port < 1 || options.port > 65535 || options.hold > 3600 || options['play-seconds'] > 3600) throw new Error('Numeric option out of range');
  if (!/^[A-Za-z0-9_]{1,16}$/.test(options.name)) throw new Error('Invalid offline player name');
  if (!['accepted', 'denied', 'rejoin', 'unavailable', 'save-failed', 'preview', 'play-timeout'].includes(options.expect)) throw new Error('Invalid expected result');
  if (!['accept', 'leave'].includes(options.action)) throw new Error('Invalid action');
  if (options.action === 'leave' && options.expect !== 'denied') throw new Error('Leave requires --expect denied');
  if (!['true', 'false'].includes(options['reply-pings'])) throw new Error('Invalid ping reply option');
  if (options.expect === 'play-timeout' && (options['reply-pings'] !== 'false' || options['play-seconds'] === 0)) throw new Error('play-timeout needs --reply-pings false and a positive --play-seconds');
  return options;
}

function varint(value) {
  const bytes = [];
  do {
    let byte = value & 127;
    value >>>= 7;
    if (value) byte |= 128;
    bytes.push(byte);
  } while (value);
  return Buffer.from(bytes);
}

// Checkbox values: a real client sends TAG_Byte 1 or 0. The other kinds are for rejection tests.
const VALUES = {
  byte: [1, Buffer.from([1])],
  unchecked: [1, Buffer.from([0])],
  int: [3, Buffer.from([0, 0, 0, 1])],
  string: [8, Buffer.from([0, 4, ...Buffer.from('true')])]
};

function encodeClick(action, inputs, packetId = 8, values = 'byte') {
  const [type, value] = VALUES[values];
  const fields = inputs.map(key => {
    const name = Buffer.from(key);
    const length = Buffer.alloc(2);
    length.writeUInt16BE(name.length);
    return Buffer.concat([Buffer.from([type]), length, name, value]);
  });
  const payload = Buffer.concat([Buffer.from([10]), ...fields, Buffer.from([0])]);
  const identifier = Buffer.from(action);
  // custom_click_action (configuration 0x08 by default), followed by length-prefixed anonymous NBT.
  return Buffer.concat([varint(packetId), varint(identifier.length), identifier, varint(payload.length), payload]);
}

module.exports = {parseOptions, encodeClick};
