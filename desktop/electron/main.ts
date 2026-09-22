import { app, BrowserWindow, dialog, ipcMain, net, protocol, session, shell } from 'electron';
import type { IpcMainInvokeEvent } from 'electron';
import { readFile, writeFile, rename, mkdir, realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { PhotoStore, checkOutputDirectory, writeUnique, pathKey } from './files.ts';
import { inspectImage } from '../src/image-format.ts';
import type { Settings } from '../src/shared.ts';

protocol.registerSchemesAsPrivileged([{ scheme: 'timepic', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);
const profileDirectory = app.commandLine.getSwitchValue('user-data-dir');
if (profileDirectory) app.setPath('userData', path.resolve(profileDirectory));
if (!app.isPackaged && process.env.TIMEPIC_TEST_USER_DATA) app.setPath('userData', process.env.TIMEPIC_TEST_USER_DATA);
const photos = new PhotoStore();
let window: BrowserWindow;
let outputDirectory = '';
let settings: Partial<Settings> = {};
let saveQueue = Promise.resolve();
let job: { id: string; directory: string; ids: Set<string>; writing: boolean } | null = null;

function handle(channel: string, callback: (...args: any[]) => unknown) {
  ipcMain.handle(channel, (event: IpcMainInvokeEvent, ...args: unknown[]) => {
    if (event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) throw new Error('无效的窗口请求');
    return callback(...args);
  });
}
function idle() { if (job) throw new Error('正在导出，请等待完成或取消'); }
function safeSettings(input: Partial<Settings>): Partial<Settings> {
  return {
    template: ['simple', 'checkin', 'project'].includes(input.template ?? '') ? input.template : 'checkin',
    note: typeof input.note === 'string' ? input.note.slice(0, 300) : '',
    scale: typeof input.scale === 'number' && Number.isFinite(input.scale) ? Math.min(1.5, Math.max(0.5, input.scale)) : 1,
    anchorX: typeof input.anchorX === 'number' && Number.isFinite(input.anchorX) ? Math.min(1, Math.max(0, input.anchorX)) : 0,
    anchorY: typeof input.anchorY === 'number' && Number.isFinite(input.anchorY) ? Math.min(1, Math.max(0, input.anchorY)) : 1,
    outputDirectory: typeof input.outputDirectory === 'string' ? input.outputDirectory : '',
  };
}
async function saveSettings(input: Partial<Settings>) {
  settings = safeSettings({ ...input, outputDirectory });
  const contents = JSON.stringify(settings, null, 2);
  const target = path.join(app.getPath('userData'), 'settings.json');
  saveQueue = saveQueue.catch(() => {}).then(async () => {
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(`${target}.tmp`, contents, 'utf8');
    await rename(`${target}.tmp`, target);
  });
  await saveQueue;
}

async function setup() {
  const root = path.resolve(__dirname, '../dist');
  protocol.handle('timepic', request => {
    const url = new URL(request.url);
    if (url.host !== 'app' || request.method !== 'GET') return new Response('Not found', { status: 404 });
    let pathname: string;
    try { pathname = decodeURIComponent(url.pathname); } catch { return new Response('Bad request', { status: 400 }); }
    const target = path.resolve(root, `.${pathname === '/' ? '/index.html' : pathname}`);
    if (!target.startsWith(`${root}${path.sep}`)) return new Response('Forbidden', { status: 403 });
    return net.fetch(pathToFileURL(target).toString());
  });
  try { settings = safeSettings(JSON.parse(await readFile(path.join(app.getPath('userData'), 'settings.json'), 'utf8'))); } catch { settings = {}; }
  outputDirectory = settings.outputDirectory ?? '';
  window = new BrowserWindow({
    width: 1380, height: 900, minWidth: 1040, minHeight: 720, show: false,
    title: 'TimePic · 图片水印', backgroundColor: '#101214', autoHideMenuBar: true,
    icon: path.join(root, 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true, spellcheck: false },
  });
  window.setMenu(null);
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
  handle('settings:get', () => ({ ...settings, outputDirectory }));
  handle('settings:save', saveSettings);
  handle('images:choose', async () => {
    idle();
    const result = await dialog.showOpenDialog(window, { title: '选择需要添加水印的图片', properties: ['openFile', 'multiSelections'], filters: [{ name: '静态图片', extensions: ['jpg', 'jpeg', 'png', 'webp'] }] });
    return photos.add(result.canceled ? [] : result.filePaths);
  });
  handle('images:forget', (ids: string[]) => { idle(); if (Array.isArray(ids)) for (const id of ids) photos.photos.delete(id); });
  handle('images:read', (id: string) => photos.read(id));
  handle('output:choose', async () => {
    idle();
    const result = await dialog.showOpenDialog(window, { title: '选择独立的输出文件夹', defaultPath: outputDirectory || undefined, properties: ['openDirectory', 'createDirectory'] });
    if (result.canceled || !result.filePaths[0]) return null;
    outputDirectory = await checkOutputDirectory(result.filePaths[0], [...photos.photos.values()].map(photo => photo.path));
    await saveSettings(settings);
    return outputDirectory;
  });
  handle('export:begin', async (ids: string[]) => {
    idle();
    if (!Array.isArray(ids) || !ids.length || ids.length > 10000 || ids.some(id => typeof id !== 'string')) throw new Error('请先选择需要导出的图片');
    if (!outputDirectory) throw new Error('请先选择输出目录');
    // Reserve the job before yielding so simultaneous requests cannot start two queues.
    const pending = { id: randomUUID(), directory: '', ids: new Set(ids), writing: false };
    job = pending;
    try {
      ids.forEach(id => photos.get(id));
      pending.directory = await checkOutputDirectory(outputDirectory, [...photos.photos.values()].map(photo => photo.path));
      return pending.id;
    } catch (error) { job = null; throw error; }
  });
  handle('export:write', async (token: string, id: string, bytes: Uint8Array) => {
    const current = job;
    if (!current || current.id !== token || !current.ids.has(id) || current.writing || !current.directory) throw new Error('导出任务已结束或无效');
    const photo = photos.get(id);
    if (!(bytes instanceof Uint8Array) || bytes.length > 1024 * 1024 * 1024 || inspectImage(bytes) !== photo.format) throw new Error('导出数据格式无效');
    current.writing = true;
    try {
      if (pathKey(await realpath(outputDirectory)) !== pathKey(current.directory)) throw new Error('输出目录已发生变化，请重新选择');
      const filename = await writeUnique(current.directory, photo.name, bytes);
      current.ids.delete(id);
      return filename;
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code === 'ENOSPC') throw new Error('输出磁盘空间不足');
      if (code === 'EACCES' || code === 'EPERM') throw new Error('没有输出目录的写入权限');
      throw error;
    } finally { current.writing = false; }
  });
  handle('export:finish', (token: string) => { if (job?.id === token && !job.writing) job = null; });
  handle('output:open', async () => { if (outputDirectory) { const error = await shell.openPath(outputDirectory); if (error) throw new Error('无法打开输出目录：' + error); } });
  window.on('close', event => {
    if (!job) return;
    event.preventDefault();
    void dialog.showMessageBox(window, { type: 'info', title: '正在导出', message: '请先点击“停止导出”，当前图片完成后即可关闭软件。', buttons: ['知道了'] });
  });
  window.once('ready-to-show', () => window.show());
  const devUrl = !app.isPackaged ? process.env.TIMEPIC_DEV_URL : undefined;
  if (devUrl && devUrl !== 'http://127.0.0.1:5173') throw new Error('无效的开发服务器地址');
  await window.loadURL(devUrl || 'timepic://app/index.html');
}
app.whenReady().then(setup).catch(error => { dialog.showErrorBox('TimePic 启动失败', String(error)); app.quit(); });
app.on('window-all-closed', () => app.quit());
