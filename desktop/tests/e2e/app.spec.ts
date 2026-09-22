import { test, expect, _electron as electron } from '@playwright/test';
import type { ElectronApplication, Page } from '@playwright/test';
import { mkdir, mkdtemp, readFile, readdir, copyFile, unlink } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';

const fixtures = path.resolve('test-results/fixtures');
let app: ElectronApplication;
let page: Page;
let output: string;
const hash = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
test.beforeEach(async () => {
  await mkdir('test-results/runs', { recursive: true });
  const root = await mkdtemp(path.resolve('test-results/runs', 'session-'));
  output = path.join(root, 'output'); await mkdir(output);
  const executablePath = process.env.TIMEPIC_PACKAGED_EXE;
  const cleanEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    !['path', 'node_path'].includes(key.toLowerCase())));
  const packagedEnv = executablePath ? {
    ...cleanEnv, PATH: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32'), NODE_PATH: '',
  } : process.env;
  app = await electron.launch({
    ...(executablePath ? { executablePath, cwd: path.dirname(executablePath), args: ['--user-data-dir=' + path.join(root, 'profile')] } : { args: [path.resolve('.')] }),
    env: { ...packagedEnv, TIMEPIC_TEST_USER_DATA: path.join(root, 'profile') }, timeout: 60000,
  });
  if (executablePath) {
    const info = await app.evaluate(({ app, session }) => {
      session.defaultSession.webRequest.onBeforeRequest(
        { urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] },
        (_details, callback) => callback({ cancel: true }),
      );
      return { packaged: app.isPackaged, path: app.getAppPath(), cwd: process.cwd(), nodePath: process.env.NODE_PATH };
    });
    expect(info.packaged).toBe(true);
    expect(info.path).toContain('app.asar');
    expect(info.cwd.toLowerCase()).toBe(path.dirname(executablePath).toLowerCase());
    expect(info.nodePath).toBe('');
  }
  page = await app.firstWindow();
  await page.context().route(/^https?:\/\//, route => route.abort());
  await expect(page.locator('body')).toHaveAttribute('data-ready', 'true');
});
test.afterEach(async () => {
  if (page && !page.isClosed() && await page.locator('#cancel-export').isVisible()) {
    await page.locator('#cancel-export').click();
    await expect(page.locator('#cancel-export')).toBeHidden({ timeout: 30000 });
  }
  if (app) await app.close();
});
async function choose(paths: string[]) {
  await app.evaluate(({ dialog }, filePaths) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths }); }, paths);
}
async function add(names: string[]) {
  await choose(names.map(name => path.isAbsolute(name) ? name : path.join(fixtures, name)));
  await page.locator('#add-images').click();
}
async function setOutput() {
  await choose([output]); await page.locator('#choose-output').click();
  await expect(page.locator('#output-path')).toHaveText(output);
}
async function readyPreview() { await expect(page.locator('#canvas-shell')).toBeVisible(); await expect(page.locator('#preview-message')).toBeHidden(); }
async function exportAll(expected: number, failures = 0) {
  await page.locator('#export-images').click();
  await expect(page.locator('#status-title')).toContainText(`导出完成 · 成功 ${expected} 张`, { timeout: 120000 });
  if (failures) await expect(page.locator('#status-title')).toContainText(`失败 ${failures} 张`);
  await expect(page.locator('#cancel-export')).toBeHidden();
}
async function pixels(file: string) {
  const bytes = await readFile(file);
  return page.evaluate(async data => {
    const bitmap = await createImageBitmap(new Blob([new Uint8Array(data)]), { imageOrientation: 'from-image' });
    const canvas = document.createElement('canvas'); canvas.width = bitmap.width; canvas.height = bitmap.height;
    const ctx = canvas.getContext('2d')!; ctx.drawImage(bitmap, 0, 0); bitmap.close();
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const digest = await crypto.subtle.digest('SHA-256', image.data.buffer);
    const corner = [...ctx.getImageData(canvas.width - 1, 0, 1, 1).data];
    return { width: canvas.width, height: canvas.height, alpha: corner[3], corner, hash: [...new Uint8Array(digest)].join(',') };
  }, [...bytes]);
}

test('single preview, templates, drag, exact PNG output and original hashes', async () => {
  const source = path.join(fixtures, 'landscape.png'); const before = hash(await readFile(source));
  await add(['landscape.png']); await readyPreview();
  await page.locator('#common-time').fill('2024-02-29T12:30:45');
  await page.locator('#common-address').fill('北京市 · 朝阳区 · 望京街道\n测试项目 A 区');
  await page.locator('#common-note').fill('现场记录 · 今日晴');
  for (const template of ['simple', 'project', 'checkin']) {
    await page.locator(`[data-template="${template}"]`).click();
    await expect(page.locator(`[data-template="${template}"]`)).toHaveAttribute('aria-pressed', 'true');
    await page.waitForTimeout(180); await readyPreview();
  }
  const outline = await page.locator('#watermark-outline').boundingBox(); expect(outline).not.toBeNull();
  if (outline) {
    await page.mouse.move(outline.x + outline.width / 2, outline.y + outline.height / 2);
    await page.mouse.down(); await page.mouse.move(outline.x + outline.width / 2 + 30, outline.y + outline.height / 2 - 35, { steps: 5 }); await page.mouse.up();
  }
  await page.locator('[data-position="0,1"]').click(); await page.waitForTimeout(250); await readyPreview();
  await setOutput();
  const previewHash = await page.locator('#preview-canvas').evaluate(async element => {
    const canvas = element as HTMLCanvasElement;
    const data = canvas.getContext('2d')!.getImageData(0, 0, canvas.width, canvas.height).data;
    return [...new Uint8Array(await crypto.subtle.digest('SHA-256', data.buffer))].join(',');
  });
  await exportAll(1);
  expect(await pixels(path.join(output, 'landscape_watermark.png'))).toMatchObject({ width: 1200, height: 800, hash: previewHash });
  expect(hash(await readFile(source))).toBe(before);
  await exportAll(1);
  expect(await readdir(output)).toEqual(['landscape_watermark.png', 'landscape_watermark_2.png']);
  await mkdir('test-results/screenshots', { recursive: true });
  await page.locator('.settings-scroll').evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path: 'test-results/screenshots/editor.png' });
});

test('batch overrides, restore inheritance and checked export', async () => {
  await add(['landscape.jpg', 'landscape.png', 'landscape.webp']); await readyPreview();
  await add(['landscape.jpg']); await expect(page.locator('#photo-count')).toHaveText('3');
  await page.locator('#common-time').fill('2026-09-22T10:00'); await page.locator('#common-address').fill('批量地址');
  await page.locator('#individual-tab').click();
  await page.locator('#override-time').check(); await page.locator('#individual-time').fill('2020-01-02T03:04:05');
  await page.locator('#override-address').check(); await page.locator('#individual-address').fill('单独地址');
  await page.locator('#common-tab').click(); await page.locator('#common-address').fill('新批量地址');
  await page.locator('#individual-tab').click();
  await expect(page.locator('#individual-address')).toHaveValue('单独地址');
  await expect(page.locator('#individual-time')).toHaveValue('2020-01-02T03:04:05');
  await page.locator('.photo-row').nth(1).click();
  await expect(page.locator('#individual-address')).toHaveValue('新批量地址'); await expect(page.locator('#individual-address')).toBeDisabled();
  await page.locator('.photo-row').nth(0).click(); await page.locator('#reset-overrides').click();
  await expect(page.locator('#individual-address')).toHaveValue('新批量地址'); await expect(page.locator('#override-address')).not.toBeChecked();
  await page.locator('.photo-row input').nth(2).uncheck(); await setOutput(); await exportAll(2);
  expect((await readdir(output)).length).toBe(2);
  await page.locator('#remove-selected').click(); await expect(page.locator('#photo-count')).toHaveText('1');
});

test('alpha, all EXIF directions and bad images continue safely', async () => {
  const names = ['transparent.png', 'transparent.webp', ...Array.from({ length: 8 }, (_, i) => `orientation-${i + 1}.jpg`), 'broken.jpg', 'animated.webp', 'animated.png', 'panorama.png', 'tiny.png'];
  const before = await Promise.all(names.map(async name => hash(await readFile(path.join(fixtures, name)))));
  await add(names); await readyPreview(); await setOutput(); await exportAll(12, 3);
  for (const name of ['transparent.png', 'transparent.webp']) expect(await pixels(path.join(output, name.replace('.', '_watermark.')))).toMatchObject({ width: 720, height: 960, alpha: 0 });
  for (let i = 1; i <= 8; i++) {
    const result = await pixels(path.join(output, `orientation-${i}_watermark.jpg`));
    expect([result.width, result.height]).toEqual(i >= 5 ? [400, 640] : [640, 400]);
    const original = await pixels(path.join(fixtures, `orientation-${i}.jpg`));
    result.corner.forEach((value, index) => expect(Math.abs(value - original.corner[index])).toBeLessThan(12));
  }
  expect(await pixels(path.join(output, 'panorama_watermark.png'))).toMatchObject({ width: 800, height: 200 });
  expect(await pixels(path.join(output, 'tiny_watermark.png'))).toMatchObject({ width: 1, height: 1 });
  expect(await Promise.all(names.map(async name => hash(await readFile(path.join(fixtures, name)))))).toEqual(before);
});

test('rejects source directory and handles write denial without partial output', async () => {
  await add(['landscape.png']); await readyPreview();
  await choose([fixtures]); await page.locator('#choose-output').click();
  await expect(page.locator('#toast')).toContainText('输出目录不能与原图所在目录相同'); await setOutput();
  await app.evaluate(async () => {
    const fs = process.getBuiltinModule('fs/promises') as typeof import('node:fs/promises'); const original = fs.open;
    fs.open = (async (...args: Parameters<typeof original>) => {
      if (args[1] === 'wx') throw Object.assign(new Error('Access denied'), { code: 'EACCES' });
      return original(...args);
    }) as typeof original;
  });
  await exportAll(0, 1); await expect(page.locator('.file-status.failed')).toContainText('写入权限');
  expect(await readdir(output)).toEqual([]);
});

test('100 image batch cancellation and complete retry', async () => {
  const names = Array.from({ length: 100 }, (_, i) => path.join(fixtures, 'batch', `batch-${String(i).padStart(3, '0')}.jpg`));
  const before = await Promise.all(names.map(async name => hash(await readFile(name))));
  await add(names); await readyPreview(); await setOutput();
  await page.locator('#export-images').click(); await expect(page.locator('#cancel-export')).toBeVisible(); await page.locator('#cancel-export').click();
  await expect(page.locator('#status-title')).toContainText('已停止', { timeout: 30000 }); await expect(page.locator('#cancel-export')).toBeHidden();
  const canceledCount = (await readdir(output)).length; expect(canceledCount).toBeLessThan(100);
  await exportAll(100); expect((await readdir(output)).length).toBe(100 + canceledCount);
  expect(await Promise.all(names.map(async name => hash(await readFile(name))))).toEqual(before);
});

test('long Chinese text stays inside image and small window remains usable', async () => {
  await add(['panorama.png']); await readyPreview();
  await page.locator('#common-address').fill('北京市朝阳区望京街道工程现场，水印中文地址自动换行。'.repeat(10));
  await page.locator('#common-note').fill('备注长文本测试。'.repeat(20));
  await page.locator('#watermark-scale').focus(); await page.locator('#watermark-scale').press('End'); await page.locator('[data-template="project"]').click(); await page.locator('[data-position="1,1"]').click();
  await page.waitForTimeout(300); await readyPreview();
  const image = await page.locator('#preview-canvas').boundingBox(); const watermark = await page.locator('#watermark-outline').boundingBox(); expect(image && watermark).toBeTruthy();
  if (image && watermark) {
    expect(watermark.x).toBeGreaterThanOrEqual(image.x - 1); expect(watermark.y).toBeGreaterThanOrEqual(image.y - 1);
    expect(watermark.x + watermark.width).toBeLessThanOrEqual(image.x + image.width + 1); expect(watermark.y + watermark.height).toBeLessThanOrEqual(image.y + image.height + 1);
  }
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1040, 720));
  await expect(page.locator('#choose-output')).toBeInViewport(); await expect(page.locator('#export-images')).toBeInViewport();
  expect(await page.evaluate(() => document.body.scrollWidth <= innerWidth)).toBe(true);
});

test('missing file after import fails per item and remaining photos are exported', async () => {
  const vanished = path.join(path.dirname(output), 'vanished.jpg');
  await copyFile(path.join(fixtures, 'landscape.jpg'), vanished);
  await add(['landscape.png', vanished]); await readyPreview();
  await unlink(vanished); await setOutput(); await exportAll(1, 1);
  expect(await readdir(output)).toEqual(['landscape_watermark.png']);
});
