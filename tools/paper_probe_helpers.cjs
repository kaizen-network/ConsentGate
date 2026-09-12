const allowed = new Set(['modules', 'port', 'hold', 'name', 'expect', 'play-seconds', 'reply-pings']);

function parseOptions(args) {
  const options = {port: '25592', hold: '75', name: 'ConsentProbe', expect: 'accepted', 'play-seconds': '0', 'reply-pings': 'true'};
  const seen = new Set();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index].slice(2);
    if (!args[index].startsWith('--') || !allowed.has(key) || seen.has(key) || args[index + 1] == null || args[index + 1].startsWith('--')) throw new Error('Invalid or repeated option: ' + args[index]);
    seen.add(key);
    options[key] = args[index + 1];
  }
  if (!options.modules) throw new Error('Provide --modules with the dependency node_modules directory');
  for (const key of ['port', 'hold', 'play-seconds']) {
    if (!/^\d+$/.test(options[key])) throw new Error('Invalid numeric option: ' + key);
    options[key] = Number(options[key]);
    if (!Number.isSafeInteger(options[key])) throw new Error('Invalid numeric option: ' + key);
  }
  if (options.port < 1 || options.port > 65535 || options.hold > 3600 || options['play-seconds'] > 3600) throw new Error('Numeric option out of range');
  if (!/^[A-Za-z0-9_]{1,16}$/.test(options.name)) throw new Error('Invalid offline player name');
  if (!['accepted', 'denied', 'play-timeout'].includes(options.expect)) throw new Error('Invalid expected result');
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

function encodeClick(action, inputs) {
  const fields = inputs.map(key => {
    const name = Buffer.from(key);
    const length = Buffer.alloc(2);
    length.writeUInt16BE(name.length);
    return Buffer.concat([Buffer.from([1]), length, name, Buffer.from([1])]);
  });
  const payload = Buffer.concat([Buffer.from([10]), ...fields, Buffer.from([0])]);
  const identifier = Buffer.from(action);
  // Java 26.1 configuration custom_click_action, followed by length-prefixed anonymous NBT.
  return Buffer.concat([Buffer.from([8]), varint(identifier.length), identifier, varint(payload.length), payload]);
}

module.exports = {parseOptions, encodeClick};
