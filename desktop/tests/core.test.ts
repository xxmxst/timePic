import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, readdir, symlink } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { defaultConfig, effectiveConfig, parseTime, validateConfig } from '../src/shared.ts';
import { inspectImage } from '../src/image-format.ts';
import { PhotoStore, checkOutputDirectory, writeUnique } from '../electron/files.ts';

await mkdir('test-results', { recursive: true });
const root = await mkdtemp(path.resolve('test-results', 'core-'));
const source = path.join(root, 'source'); const output = path.join(root, 'output');
await mkdir(source); await mkdir(output);
const image = new Uint8Array([255, 216, 255, 224, 1, 2, 3]);
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

test('manual times validate leap days and reject invalid calendar fields', () => {
  assert.equal(parseTime('2024-02-29T15:36:48').getSeconds(), 48);
  assert.equal(parseTime('2026-09-22T10:30').getSeconds(), 0);
  for (const bad of ['', '2025-02-29T10:30:00', '2026-13-01T00:00:00', '2026-01-32T00:00:00', '2026-01-01T24:00:00', '2026-01-01T12:70:00']) assert.throws(() => parseTime(bad));
});
test('overrides follow common fields until explicitly overridden, including empty addresses', () => {
  const config = { ...defaultConfig(), time: '2026-01-01T10:00:00', address: '公共地址' };
  const overrides = { time: '2024-02-29T12:30:45', address: '' };
  assert.equal(effectiveConfig(config, {}).address, '公共地址');
  assert.equal(effectiveConfig(config, overrides).address, '');
  config.address = '修改后'; config.time = '2026-02-02T11:00:00';
  assert.equal(effectiveConfig(config, overrides).time, '2024-02-29T12:30:45');
  assert.equal(effectiveConfig(config, {}).address, '修改后');
});
test('rejects nonfinite sizes, invalid anchors and too-long text', () => {
  for (const patch of [{ scale: NaN }, { scale: 5 }, { anchorX: -1 }, { address: '地'.repeat(301) }]) assert.throws(() => validateConfig({ ...defaultConfig(), ...patch }));
});
test('detects animation containers and invalid content before bitmap decoding', () => {
  assert.equal(inspectImage(image), 'jpeg');
  assert.throws(() => inspectImage(new Uint8Array([1, 2, 3])));
  const animation = [
    'RIFF\x04\x00\x00\x00WEBPANIM\x00\x00\x00\x00',
    '\x89PNG\r\n\x1a\n\x00\x00\x00\x00acTL\x00\x00\x00\x00',
  ];
  for (const data of animation) assert.throws(() => inspectImage(new Uint8Array(Buffer.from(data, 'latin1'))), /动画/);
});
test('import deduplicates canonical paths and never changes source bytes', async () => {
  const file = path.join(source, 'original.JPG'); await writeFile(file, image);
  const before = hash(await readFile(file));
  const store = new PhotoStore();
  const result = await store.add([file, path.join(source, '.', 'original.JPG')]);
  assert.equal(store.photos.size, 1); assert.equal(result.photos[0].id, result.photos[1].id);
  assert.equal(hash(await store.read(result.photos[0].id)), before);
  assert.equal(hash(await readFile(file)), before);
});
test('rejects source folder, dot aliases and junction aliases as output', async () => {
  const file = path.join(source, 'directory-check.jpg'); await writeFile(file, image);
  await assert.rejects(checkOutputDirectory(source, [file]), /原图所在目录/);
  await assert.rejects(checkOutputDirectory(path.join(source, '.'), [file]), /原图所在目录/);
  const alias = path.join(root, 'source-alias');
  await symlink(source, alias, process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(checkOutputDirectory(alias, [file]), /原图所在目录/);
  const child = path.join(source, 'new-output'); await mkdir(child);
  assert.equal(await checkOutputDirectory(child, [file]), child);
});
test('parallel same-name exports are exclusive and keep existing outputs unchanged', async () => {
  const first = path.join(output, 'same_watermark.jpg'); await writeFile(first, 'existing');
  const results = await Promise.all(Array.from({ length: 15 }, () => writeUnique(output, 'same.jpg', image)));
  assert.equal(new Set(results).size, 15);
  assert.equal(await readFile(first, 'utf8'), 'existing');
  for (const result of results) assert.equal(hash(await readFile(result)), hash(image));
});
test('write failure cleans up its newly created output file', async () => {
  await assert.rejects(writeUnique(output, 'failure.png', undefined as unknown as Uint8Array));
  assert.ok(!(await readdir(output)).some(name => name.startsWith('failure')));
});
test('same-name files from different directories do not overwrite either source', async () => {
  const other = path.join(root, 'other'); await mkdir(other);
  const a = path.join(source, 'same.jpg'); const b = path.join(other, 'same.jpg');
  await writeFile(a, image); await writeFile(b, new Uint8Array([...image, 9]));
  const store = new PhotoStore(); const imported = await store.add([a, b]);
  assert.equal(imported.photos.length, 2);
  for (const photo of imported.photos) await writeUnique(output, photo.name, await store.read(photo.id));
  assert.equal(hash(await readFile(a)), hash(image)); assert.equal(hash(await readFile(b)), hash(new Uint8Array([...image, 9])));
});
test('mismatched extensions and files replaced after import fail safely', async () => {
  const file = path.join(source, 'mismatch.png'); await writeFile(file, image);
  const store = new PhotoStore(); const result = await store.add([file]);
  await assert.rejects(store.read(result.photos[0].id), /扩展名/);
  await writeFile(file, 'broken'); await assert.rejects(store.read(result.photos[0].id), /格式无效/);
});
