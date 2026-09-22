import './style.css';
import { defaultConfig, effectiveConfig, errorText, localTime, validateConfig } from './shared.ts';
import type { Overrides, Photo, Settings, WatermarkConfig } from './shared.ts';
import type { Bounds } from './watermark.ts';

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const api = window.timepic;
interface PhotoItem extends Photo { selected: boolean; overrides: Overrides; status: string; failed: boolean; output?: string }
const items: PhotoItem[] = [];
let config = defaultConfig();
let currentId = '';
let outputDirectory = '';
let exporting = false;
let importing = false;
let canceled = false;
let activeTab = 'common';
let currentBounds: Bounds | undefined;
let loadedId = '';
let previewVersion = 0;
let previewRunning = false;
let previewTimer: ReturnType<typeof setTimeout>;
let saveTimer: ReturnType<typeof setTimeout>;
let toastTimer: ReturnType<typeof setTimeout>;
const canvas = $<HTMLCanvasElement>('preview-canvas');
const fontUrl = new URL('fonts/NotoSansSC-Regular.otf', location.href).href;
let serial = 0;
interface WorkerResponse { bitmap?: ImageBitmap; buffer?: ArrayBuffer; bounds?: Bounds; width: number; height: number }
const pending = new Map<number, { resolve: (value: WorkerResponse) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
let worker: Worker;
function startWorker() {
  worker = new Worker(new URL('./image.worker.ts', import.meta.url), { type: 'module' });
  worker.onmessage = ({ data }) => {
    const promise = pending.get(data.request);
    if (!promise) { data.bitmap?.close(); return; }
    clearTimeout(promise.timer); pending.delete(data.request);
    if (data.error) promise.reject(new Error(data.error)); else promise.resolve(data);
  };
  worker.onerror = event => {
    event.preventDefault();
    resetWorker(new Error('图片处理进程异常，请重试或减小图片尺寸'));
  };
}
function resetWorker(error: Error) {
  worker.terminate(); loadedId = '';
  for (const request of pending.values()) { clearTimeout(request.timer); request.reject(error); }
  pending.clear(); startWorker();
}
function processImage(type: string, payload: Record<string, unknown> = {}, transfer: Transferable[] = []): Promise<WorkerResponse> {
  const request = ++serial;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => resetWorker(new Error('图片处理超时，请检查图片或重试')), 180000);
    pending.set(request, { resolve, reject, timer });
    worker.postMessage({ type, request, fontUrl, ...payload }, transfer);
  });
}
startWorker();

function current() { return items.find(item => item.id === currentId); }
function selected() { return items.filter(item => item.selected); }
function toast(message: string) {
  $('toast').textContent = message; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6000);
}
function report(error: unknown) { toast(errorText(error)); }
function bytesText(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`; }
function persist() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const settings: Settings = { template: config.template, note: config.note, scale: config.scale, anchorX: config.anchorX, anchorY: config.anchorY, outputDirectory };
    void api.saveSettings(settings).catch(report);
  }, 350);
}
function updateActions() {
  const count = selected().length;
  const busy = exporting || importing;
  $('photo-count').textContent = String(items.length);
  $<HTMLButtonElement>('add-images').disabled = busy;
  $<HTMLButtonElement>('empty-add').disabled = busy;
  $<HTMLButtonElement>('remove-selected').disabled = busy || !count;
  $<HTMLInputElement>('select-all').disabled = busy || !items.length;
  $<HTMLInputElement>('select-all').checked = items.length > 0 && count === items.length;
  $<HTMLInputElement>('select-all').indeterminate = count > 0 && count < items.length;
  $<HTMLButtonElement>('choose-output').disabled = busy;
  $<HTMLButtonElement>('open-output').disabled = !outputDirectory;
  $<HTMLFieldSetElement>('settings-fields').disabled = busy;
  const button = $<HTMLButtonElement>('export-images');
  button.disabled = busy || !count || !outputDirectory;
  button.replaceChildren(document.createTextNode(exporting ? '正在导出 ' : '导出选中图片 '));
  const number = document.createElement('span'); number.textContent = String(count);
  const arrow = document.createElement('b'); arrow.textContent = '↗';
  button.append(number, arrow);
  $('cancel-export').hidden = !exporting;
  $<HTMLButtonElement>('cancel-export').disabled = canceled;
  $('cancel-export').textContent = canceled ? '正在停止…' : '停止导出';
  $('output-path').textContent = outputDirectory || '选择一个独立文件夹，保存添加水印后的图片';
  $('output-path').title = outputDirectory;
}
function renderList() {
  const fragment = document.createDocumentFragment();
  for (const item of items) {
    const row = document.createElement('div'); row.className = `photo-row${item.id === currentId ? ' active' : ''}`;
    row.dataset.id = item.id; row.setAttribute('role', 'listitem'); row.tabIndex = 0; row.title = item.path;
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = item.selected;
    checkbox.disabled = exporting || importing; checkbox.setAttribute('aria-label', `选择 ${item.name}`);
    checkbox.addEventListener('click', event => event.stopPropagation());
    checkbox.addEventListener('change', () => { item.selected = checkbox.checked; updateActions(); });
    const thumb = document.createElement('span'); thumb.className = 'file-thumb'; thumb.textContent = item.format === 'jpeg' ? 'JPG' : item.format.toUpperCase();
    const info = document.createElement('div'); info.className = 'file-info';
    const name = document.createElement('div'); name.className = 'file-name'; name.textContent = item.name;
    const meta = document.createElement('div'); meta.className = 'file-meta';
    const size = document.createElement('span'); size.textContent = bytesText(item.size); meta.append(size);
    if (Object.keys(item.overrides).length) { const tag = document.createElement('span'); tag.className = 'override-tag'; tag.textContent = '· 单独设置'; meta.append(tag); }
    info.append(name, meta);
    if (item.status) { const status = document.createElement('div'); status.className = `file-status${item.failed ? ' failed' : ''}`; status.textContent = item.status; status.title = item.status; info.append(status); }
    row.append(checkbox, thumb, info);
    const activate = () => { if (exporting || importing) return; currentId = item.id; renderList(); syncIndividual(); schedulePreview(); };
    row.addEventListener('click', activate);
    row.addEventListener('keydown', event => { if (event.target === row && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); activate(); } });
    fragment.append(row);
  }
  $('photo-list').replaceChildren(fragment);
  $('photo-list').hidden = !items.length;
  $('library-empty').hidden = items.length > 0;
  updateActions();
}
function syncIndividual() {
  const item = current();
  const value = item ? effectiveConfig(config, item.overrides) : config;
  $('individual-hint').textContent = item ? `${item.name} · 未勾选的字段跟随批量设置` : '选择一张图片后，可单独修改时间和地址';
  $('individual-hint').title = $('individual-hint').textContent ?? '';
  for (const key of ['time', 'address'] as const) {
    const check = $<HTMLInputElement>(`override-${key}`);
    check.checked = item?.overrides[key] !== undefined; check.disabled = !item;
    const field = $<HTMLInputElement | HTMLTextAreaElement>(`individual-${key}`);
    field.value = value[key]; field.disabled = !check.checked || !item;
  }
  $<HTMLButtonElement>('reset-overrides').disabled = !item || !Object.keys(item.overrides).length;
}
function syncStyles() {
  document.querySelectorAll<HTMLButtonElement>('[data-template]').forEach(button => {
    const active = button.dataset.template === config.template;
    button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  document.querySelectorAll<HTMLButtonElement>('[data-position]').forEach(button => {
    const [x, y] = button.dataset.position!.split(',').map(Number);
    const active = Math.abs(config.anchorX - x) < .001 && Math.abs(config.anchorY - y) < .001;
    button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  $<HTMLInputElement>('watermark-scale').value = String(Math.round(config.scale * 100));
  $('scale-value').textContent = `${Math.round(config.scale * 100)}%`;
}
function fitPreview() {
  const stage = $('preview-stage');
  if (!canvas.width || !canvas.height) return;
  const ratio = Math.min(stage.clientWidth / canvas.width, stage.clientHeight / canvas.height);
  $('canvas-shell').style.width = `${Math.floor(canvas.width * ratio)}px`;
  $('canvas-shell').style.height = `${Math.floor(canvas.height * ratio)}px`;
  if (currentBounds) {
    const outline = $('watermark-outline');
    outline.style.left = `${currentBounds.x / canvas.width * 100}%`; outline.style.top = `${currentBounds.y / canvas.height * 100}%`;
    outline.style.width = `${currentBounds.width / canvas.width * 100}%`; outline.style.height = `${currentBounds.height / canvas.height * 100}%`;
  }
}
new ResizeObserver(fitPreview).observe($('preview-stage'));
function schedulePreview() {
  previewVersion++;
  clearTimeout(previewTimer); previewTimer = setTimeout(() => { void renderPreview(); }, 70);
}
async function renderPreview() {
  if (previewRunning) return;
  const item = current();
  const version = previewVersion;
  if (!item) {
    $('canvas-shell').hidden = true; $('preview-empty').hidden = false; $('preview-message').hidden = true;
    $('current-filename').textContent = '尚未选择图片'; $('image-dimensions').textContent = '实时预览'; currentBounds = undefined;
    return;
  }
  previewRunning = true;
  $('preview-empty').hidden = true;
  $('current-filename').textContent = item.name;
  if (loadedId !== item.id) { $('canvas-shell').hidden = true; $('image-dimensions').textContent = '读取中'; $('preview-message').textContent = '正在读取图片…'; $('preview-message').hidden = false; }
  try {
    const value = effectiveConfig(config, item.overrides); validateConfig(value);
    const payload: Record<string, unknown> = { source: item.id, format: item.format, config: value };
    const transfer: Transferable[] = [];
    if (loadedId !== item.id) {
      const bytes = await api.readImage(item.id);
      if (version !== previewVersion) return;
      const buffer = new Uint8Array(bytes).buffer; payload.buffer = buffer; transfer.push(buffer);
    }
    const result = await processImage('preview', payload, transfer);
    loadedId = item.id;
    if (version !== previewVersion || currentId !== item.id) { result.bitmap?.close(); return; }
    const bitmap = result.bitmap!;
    canvas.width = bitmap.width; canvas.height = bitmap.height;
    canvas.getContext('2d')!.drawImage(bitmap, 0, 0); bitmap.close();
    currentBounds = result.bounds;
    $('canvas-shell').hidden = false; $('preview-message').hidden = true;
    $('image-dimensions').textContent = `${result.width} × ${result.height}`;
    fitPreview();
  } catch (error) {
    loadedId = '';
    if (version === previewVersion) {
      $('canvas-shell').hidden = true; currentBounds = undefined;
      $('preview-message').textContent = errorText(error); $('preview-message').hidden = false;
      $('image-dimensions').textContent = '无法预览';
    }
  } finally {
    previewRunning = false;
    if (version !== previewVersion) void renderPreview();
  }
}

async function addImages() {
  importing = true; updateActions();
  try {
    const result = await api.chooseImages();
    let added = 0;
    for (const photo of result.photos) {
      if (items.some(item => item.id === photo.id)) continue;
      items.push({ ...photo, selected: true, overrides: {}, status: '', failed: false }); added++;
    }
    if (!currentId && items.length) currentId = items[0].id;
    if (result.errors.length) { $('error-details').textContent = result.errors.join('\n'); $<HTMLDialogElement>('error-dialog').showModal(); }
    if (added) { $('status-title').textContent = `已添加 ${items.length} 张图片`; $('status-detail').textContent = '填写时间与地址，选择输出目录后即可导出'; }
    else if (result.photos.length) toast('所选图片已在列表中，无需重复添加');
    syncIndividual(); schedulePreview();
  } catch (error) { report(error); }
  finally { importing = false; renderList(); }
}
$('add-images').addEventListener('click', () => { void addImages(); });
$('empty-add').addEventListener('click', () => { void addImages(); });
$('select-all').addEventListener('change', () => { for (const item of items) item.selected = $<HTMLInputElement>('select-all').checked; renderList(); });
$('remove-selected').addEventListener('click', async () => {
  const ids = selected().map(item => item.id);
  try {
    await api.forgetImages(ids);
    for (let i = items.length - 1; i >= 0; i--) if (ids.includes(items[i].id)) items.splice(i, 1);
    if (!current()) currentId = items[0]?.id ?? '';
    if (!items.length) { loadedId = ''; await processImage('clear'); }
    renderList(); syncIndividual(); schedulePreview();
  } catch (error) { report(error); }
});
$('close-errors').addEventListener('click', () => $<HTMLDialogElement>('error-dialog').close());
for (const tab of ['common', 'individual']) {
  $(`${tab}-tab`).addEventListener('click', () => {
    activeTab = tab;
    for (const name of ['common', 'individual']) { $(`${name}-tab`).setAttribute('aria-selected', String(activeTab === name)); $(`${name}-settings`).hidden = activeTab !== name; }
    syncIndividual();
  });
}
for (const key of ['time', 'address', 'note'] as const) {
  $(`common-${key}`).addEventListener('input', () => {
    config[key] = $<HTMLInputElement | HTMLTextAreaElement>(`common-${key}`).value;
    if (key !== 'note') syncIndividual();
    persist(); schedulePreview();
  });
}
$('time-now').addEventListener('click', () => { config.time = localTime(); $<HTMLInputElement>('common-time').value = config.time; syncIndividual(); schedulePreview(); });
for (const key of ['time', 'address'] as const) {
  $(`override-${key}`).addEventListener('change', () => {
    const item = current(); if (!item) return;
    if ($<HTMLInputElement>(`override-${key}`).checked) item.overrides[key] = config[key]; else delete item.overrides[key];
    syncIndividual(); renderList(); schedulePreview();
  });
  $(`individual-${key}`).addEventListener('input', () => {
    const item = current(); if (!item) return;
    item.overrides[key] = $<HTMLInputElement | HTMLTextAreaElement>(`individual-${key}`).value;
    renderList(); schedulePreview();
  });
}
$('reset-overrides').addEventListener('click', () => { const item = current(); if (item) item.overrides = {}; syncIndividual(); renderList(); schedulePreview(); });
document.querySelectorAll<HTMLButtonElement>('[data-template]').forEach(button => button.addEventListener('click', () => {
  config.template = button.dataset.template as WatermarkConfig['template']; syncStyles(); persist(); schedulePreview();
}));
document.querySelectorAll<HTMLButtonElement>('[data-position]').forEach(button => button.addEventListener('click', () => {
  [config.anchorX, config.anchorY] = button.dataset.position!.split(',').map(Number); syncStyles(); persist(); schedulePreview();
}));
$('watermark-scale').addEventListener('input', () => { config.scale = +$<HTMLInputElement>('watermark-scale').value / 100; syncStyles(); persist(); schedulePreview(); });
let drag: { pointer: number; x: number; y: number; anchorX: number; anchorY: number; freeX: number; freeY: number } | undefined;
canvas.addEventListener('pointerdown', event => {
  if (exporting || !currentBounds) return;
  const rect = canvas.getBoundingClientRect();
  const x = (event.clientX - rect.left) * canvas.width / rect.width;
  const y = (event.clientY - rect.top) * canvas.height / rect.height;
  const b = currentBounds;
  if (x < b.x || x > b.x + b.width || y < b.y || y > b.y + b.height) return;
  drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, anchorX: config.anchorX, anchorY: config.anchorY, freeX: (canvas.width - 2 * b.margin - b.width) * rect.width / canvas.width, freeY: (canvas.height - 2 * b.margin - b.height) * rect.height / canvas.height };
  canvas.setPointerCapture(event.pointerId); canvas.style.cursor = 'grabbing'; event.preventDefault();
});
canvas.addEventListener('pointermove', event => {
  if (!drag || event.pointerId !== drag.pointer) { canvas.style.cursor = exporting ? 'default' : 'move'; return; }
  const clamp = (value: number) => Math.min(1, Math.max(0, value));
  config.anchorX = drag.freeX > 0 ? clamp(drag.anchorX + (event.clientX - drag.x) / drag.freeX) : 0;
  config.anchorY = drag.freeY > 0 ? clamp(drag.anchorY + (event.clientY - drag.y) / drag.freeY) : 0;
  syncStyles(); schedulePreview();
});
const finishDrag = () => { if (drag) { drag = undefined; canvas.style.cursor = 'move'; persist(); } };
canvas.addEventListener('pointerup', finishDrag); canvas.addEventListener('pointercancel', finishDrag); canvas.addEventListener('lostpointercapture', finishDrag);
$('choose-output').addEventListener('click', async () => {
  try { const chosen = await api.chooseOutput(); if (chosen) { outputDirectory = chosen; persist(); updateActions(); } } catch (error) { report(error); }
});
$('open-output').addEventListener('click', () => { void api.openOutput().catch(report); });
$('cancel-export').addEventListener('click', () => { canceled = true; updateActions(); $('status-detail').textContent = '当前图片完成后停止，已导出的图片会保留'; });
$('export-images').addEventListener('click', () => { void exportImages(); });

async function exportImages() {
  if (exporting || importing) return;
  // Snapshot all effective settings so the queue is deterministic.
  const queue = selected().map(item => ({ item, config: effectiveConfig({ ...config }, { ...item.overrides }) }));
  if (!queue.length) return;
  try { for (const task of queue) validateConfig(task.config); } catch (error) { report(error); return; }
  exporting = true; canceled = false; renderList();
  const progress = $<HTMLProgressElement>('export-progress'); progress.max = queue.length; progress.value = 0; progress.hidden = false;
  let token = ''; let success = 0; let failed = 0; let processed = 0;
  try {
    token = await api.beginExport(queue.map(task => task.item.id));
    for (const { item } of queue) { item.status = '等待导出'; item.failed = false; }
    for (const task of queue) {
      if (canceled) break;
      const { item } = task;
      item.status = '正在处理…'; item.failed = false;
      $('status-title').textContent = `正在导出 ${processed + 1} / ${queue.length}`;
      $('status-detail').textContent = item.name; renderList();
      try {
        const bytes = await api.readImage(item.id);
        const buffer = new Uint8Array(bytes).buffer;
        const result = await processImage('export', { buffer, format: item.format, config: task.config }, [buffer]);
        item.output = await api.writeImage(token, item.id, new Uint8Array(result.buffer!));
        item.status = '✓ 已导出'; success++;
      } catch (error) { item.status = `失败：${errorText(error)}`; item.failed = true; failed++; }
      processed++; progress.value = processed; renderList();
      // Let paint and cancellation events run even for very small images.
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    for (const { item } of queue.slice(processed)) item.status = '已取消';
    $('status-title').textContent = `${canceled ? '已停止' : '导出完成'} · 成功 ${success} 张${failed ? `，失败 ${failed} 张` : ''}${canceled ? `，未处理 ${queue.length - processed} 张` : ''}`;
    $('status-detail').textContent = failed ? '失败原因显示在图片列表中，修正后可以重新导出' : '图片已保存到输出文件夹，原图保持不变';
  } catch (error) { report(error); $('status-title').textContent = '未能开始导出'; $('status-detail').textContent = errorText(error); }
  finally {
    if (token) await api.finishExport(token).catch(report);
    exporting = false; canceled = false; renderList(); syncIndividual();
  }
}

async function initialize() {
  try {
    const settings = await api.getSettings();
    config = { ...config, template: settings.template ?? config.template, note: settings.note ?? '', scale: settings.scale ?? 1, anchorX: settings.anchorX ?? 0, anchorY: settings.anchorY ?? 1 };
    outputDirectory = settings.outputDirectory ?? '';
  } catch (error) { report(error); }
  for (const key of ['time', 'address', 'note'] as const) $<HTMLInputElement>(`common-${key}`).value = config[key];
  syncStyles(); syncIndividual(); renderList();
  document.body.dataset.ready = 'true';
}
void initialize();
