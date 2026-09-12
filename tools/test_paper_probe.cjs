const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parseOptions, encodeClick} = require('./paper_probe_helpers.cjs');
const base = ['--modules', 'node_modules'];

test('defaults and zero-second hold', () => {
  assert.equal(parseOptions(base).port, 25592);
  assert.equal(parseOptions([...base, '--hold', '0']).hold, 0);
});
test('reject unknown, repeated and incomplete arguments', () => {
  for (const extra of [['--host', 'example.invalid'], ['--version', '1.21.8'], ['--hold'], ['--hold', '1', '--hold', '2'], ['hold', '2']]) assert.throws(() => parseOptions([...base, ...extra]));
});
test('validate numbers and identity', () => {
  for (const extra of [['--port', '0'], ['--port', '65536'], ['--hold', '-1'], ['--hold', '3601'], ['--hold', 'NaN'], ['--name', 'invalid name']]) assert.throws(() => parseOptions([...base, ...extra]));
});
test('timeout probe requires deliberate ping withholding and observation time', () => {
  assert.throws(() => parseOptions([...base, '--expect', 'play-timeout']));
  assert.equal(parseOptions([...base, '--expect', 'play-timeout', '--reply-pings', 'false', '--play-seconds', '90']).expect, 'play-timeout');
});
test('anonymous empty compound uses a payload length, not a presence flag', () => {
  assert.equal(encodeClick('test:a', []).toString('hex'), '0806746573743a61020a00');
});
test('checkbox byte is inside the length-prefixed compound', () => {
  assert.equal(encodeClick('test:a', ['document_0']).toString('hex'), '0806746573743a61100a01000a646f63756d656e745f300100');
});
test('multi-byte payload length', () => {
  const packet = encodeClick('test:a', Array.from({length: 10}, (_, i) => 'document_' + i));
  assert.deepEqual([...packet.subarray(8, 11)], [0x8e, 0x01, 0x0a]);
});
