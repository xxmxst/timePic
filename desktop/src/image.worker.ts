/// <reference lib="webworker" />
import { inspectImage } from './image-format.ts';
import { drawWatermark } from './watermark.ts';
import type { ImageFormat, WatermarkConfig } from './shared.ts';

const worker = self as unknown as DedicatedWorkerGlobalScope;
let fontReady: Promise<void> | undefined;
let preview: ImageBitmap | undefined;
let previewSource = '';
let sourceWidth = 0;
let sourceHeight = 0;
let chain = Promise.resolve();

function loadFont(url: string): Promise<void> {
  return fontReady ??= (async () => {
    const face = new FontFace('TimePicSans', `url("${url}")`);
    await face.load();
    (self as unknown as { fonts: FontFaceSet }).fonts.add(face);
  })();
}
async function decode(buffer: ArrayBuffer, format: ImageFormat): Promise<ImageBitmap> {
  const bytes = new Uint8Array(buffer);
  if (inspectImage(bytes) !== format) throw new Error('图片格式与扩展名不符');
  let bitmap: ImageBitmap;
  try { bitmap = await createImageBitmap(new Blob([buffer], { type: `image/${format}` }), { imageOrientation: 'from-image' }); }
  catch { throw new Error('无法读取图片，文件可能已损坏或格式不受支持'); }
  if (bitmap.width * bitmap.height > 120_000_000 || bitmap.width > 32767 || bitmap.height > 32767) {
    bitmap.close(); throw new Error('图片尺寸过大（上限 1.2 亿像素、单边 32767），请先调整尺寸');
  }
  return bitmap;
}

worker.onmessage = event => {
  const message = event.data;
  chain = chain.then(async () => {
    try {
      if (message.type === 'clear') {
        preview?.close(); preview = undefined; previewSource = '';
        worker.postMessage({ request: message.request }); return;
      }
      await loadFont(message.fontUrl);
      if (message.type === 'preview') {
        if (previewSource !== message.source || !preview) {
          preview?.close(); preview = undefined; previewSource = '';
          const original = await decode(message.buffer, message.format);
          try {
            sourceWidth = original.width; sourceHeight = original.height;
            const ratio = Math.min(1, 1500 / Math.max(sourceWidth, sourceHeight));
            preview = await createImageBitmap(original, { resizeWidth: Math.max(1, Math.round(sourceWidth * ratio)), resizeHeight: Math.max(1, Math.round(sourceHeight * ratio)), resizeQuality: 'high' });
            previewSource = message.source;
          } finally { original.close(); }
        }
        const canvas = new OffscreenCanvas(preview.width, preview.height);
        const ctx = canvas.getContext('2d')!;
        ctx.drawImage(preview, 0, 0);
        const bounds = drawWatermark(ctx, canvas.width, canvas.height, message.config as WatermarkConfig);
        const bitmap = canvas.transferToImageBitmap();
        worker.postMessage({ request: message.request, bitmap, bounds, width: sourceWidth, height: sourceHeight }, [bitmap]);
        canvas.width = 1; canvas.height = 1;
      } else if (message.type === 'export') {
        const original = await decode(message.buffer, message.format);
        const canvas = new OffscreenCanvas(original.width, original.height);
        try {
          const ctx = canvas.getContext('2d');
          if (!ctx) throw new Error('无法分配图片内存，请关闭其他程序后重试');
          ctx.drawImage(original, 0, 0);
          drawWatermark(ctx, canvas.width, canvas.height, message.config);
          const type = `image/${message.format}`;
          const blob = await canvas.convertToBlob({ type, quality: 0.95 });
          if (blob.type !== type) throw new Error('当前运行环境不支持该图片格式的编码');
          const buffer = await blob.arrayBuffer();
          worker.postMessage({ request: message.request, buffer, width: canvas.width, height: canvas.height }, [buffer]);
        } finally { original.close(); canvas.width = 1; canvas.height = 1; }
      } else throw new Error('未知的图片处理请求');
    } catch (error) { worker.postMessage({ request: message.request, error: error instanceof Error ? error.message : String(error) }); }
  });
};
