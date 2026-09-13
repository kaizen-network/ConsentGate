const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parseOptions, encodeClick} = require('./paper_probe_helpers.cjs');
const base = ['--modules', 'node_modules'];

test('defaults and zero-second hold', () => {
  assert.equal(parseOptions(base).port, 25592);
  assert.equal(parseOptions([...base, '--hold', '0']).hold, 0);
});

test('explicit supported boundary version preserves the existing default', () => {
  assert.equal(parseOptions(base).version, '26.1');
  assert.equal(parseOptions([...base, '--version', '1.21.8']).version, '1.21.8');
});
test('reject unknown, repeated and incomplete arguments', () => {
  for (const extra of [['--host', 'example.invalid'], ['--version', '1.21.7'], ['--hold'], ['--hold', '1', '--hold', '2'], ['hold', '2']]) assert.throws(() => parseOptions([...base, ...extra]));
});
test('validate numbers and identity', () => {
  for (const extra of [['--port', '0'], ['--port', '65536'], ['--hold', '-1'], ['--hold', '3601'], ['--hold', 'NaN'], ['--name', 'invalid name']]) assert.throws(() => parseOptions([...base, ...extra]));
});
test('timeout probe requires deliberate ping withholding and observation time', () => {
  assert.throws(() => parseOptions([...base, '--expect', 'play-timeout']));
  assert.equal(parseOptions([...base, '--expect', 'play-timeout', '--reply-pings', 'false', '--play-seconds', '90']).expect, 'play-timeout');
});
test('leave and accepted rejoin require explicit expectations', () => {
  assert.throws(() => parseOptions([...base, '--action', 'leave']));
  assert.throws(() => parseOptions([...base, '--action', 'unknown']));
  assert.equal(parseOptions([...base, '--action', 'leave', '--expect', 'denied']).action, 'leave');
  assert.equal(parseOptions([...base, '--expect', 'rejoin']).expect, 'rejoin');
});
test('remote outage checks distinguish failed lookup from failed save', () => {
  for (const expected of ['unavailable', 'save-failed']) {
    assert.equal(parseOptions([...base, '--expect', expected]).expect, expected);
    assert.throws(() => parseOptions([...base, '--expect', expected, '--action', 'leave']));
  }
});

test('preview requires acceptance followed by its explicit disconnect result', () => {
  assert.equal(parseOptions([...base, '--expect', 'preview']).expect, 'preview');
  assert.throws(() => parseOptions([...base, '--expect', 'preview', '--action', 'leave']));
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
