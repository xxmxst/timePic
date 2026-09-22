import { open, readFile, realpath, stat, unlink } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { inspectImage } from '../src/image-format.ts';
import type { Photo } from '../src/shared.ts';

export const pathKey = (value: string) => process.platform === 'win32' ? path.normalize(value).toLowerCase() : path.normalize(value);
const maxBytes = 512 * 1024 * 1024;

export async function checkOutputDirectory(directory: string, sources: string[]): Promise<string> {
  const canonical = await realpath(directory);
  if (!(await stat(canonical)).isDirectory()) throw new Error('请选择一个输出文件夹');
  const sourceDirs = await Promise.all(sources.map(async source => {
    try { return path.dirname(await realpath(source)); }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      // A removed input should fail per image, without blocking the rest of the batch.
      try { return await realpath(path.dirname(source)); }
      catch (directoryError) {
        if ((directoryError as NodeJS.ErrnoException).code !== 'ENOENT') throw directoryError;
        return path.dirname(path.resolve(source));
      }
    }
  }));
  if (sourceDirs.some(dir => pathKey(dir) === pathKey(canonical))) {
    throw new Error('输出目录不能与原图所在目录相同，请另选文件夹或新建子文件夹');
  }
  return canonical;
}

/** Exclusive creation ensures existing files and originals can never be overwritten. */
export async function writeUnique(directory: string, originalName: string, bytes: Uint8Array): Promise<string> {
  const parsed = path.parse(path.basename(originalName));
  const base = parsed.name.slice(0, 100);
  for (let suffix = 1; suffix <= 100000; suffix++) {
    const name = `${base}_watermark${suffix === 1 ? '' : `_${suffix}`}${parsed.ext.toLowerCase()}`;
    const target = path.join(directory, name);
    let handle;
    try { handle = await open(target, 'wx'); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'EEXIST') continue; throw error; }
    try {
      await handle.writeFile(bytes);
      await handle.sync();
      await handle.close();
      return target;
    } catch (error) {
      await handle.close().catch(() => {});
      await unlink(target).catch(() => {});
      throw error;
    }
  }
  throw new Error('同名输出文件过多，请更换输出目录');
}

export class PhotoStore {
  readonly photos = new Map<string, Photo>();
  async add(paths: string[]): Promise<{ photos: Photo[]; errors: string[] }> {
    const photos: Photo[] = [];
    const errors: string[] = [];
    for (const source of paths) {
      try {
        const canonical = await realpath(source);
        if (!/\.(jpe?g|png|webp)$/i.test(canonical)) throw new Error('仅支持 JPG、PNG、WebP');
        const existing = [...this.photos.values()].find(photo => pathKey(photo.path) === pathKey(canonical));
        if (existing) { photos.push(existing); continue; }
        const info = await stat(canonical);
        if (!info.isFile()) throw new Error('不是图片文件');
        if (info.size > maxBytes) throw new Error('单张图片不能超过 512 MB');
        // Do not decode here: import stays responsive and the worker handles bad/animated files per item.
        const extension = path.extname(canonical).toLowerCase();
        const format = extension === '.png' ? 'png' : extension === '.webp' ? 'webp' : 'jpeg';
        const photo: Photo = { id: randomUUID(), name: path.basename(canonical), path: canonical, size: info.size, format };
        this.photos.set(photo.id, photo); photos.push(photo);
      } catch (error) { errors.push(`${path.basename(source)}：${error instanceof Error ? error.message : String(error)}`); }
    }
    return { photos, errors };
  }
  get(id: string): Photo {
    const photo = this.photos.get(id);
    if (!photo) throw new Error('图片已移除，请重新选择');
    return photo;
  }
  async read(id: string): Promise<Uint8Array> {
    const photo = this.get(id);
    const info = await stat(photo.path);
    if (info.size > maxBytes) throw new Error('单张图片不能超过 512 MB');
    const bytes = await readFile(photo.path);
    if (inspectImage(bytes) !== photo.format) throw new Error('图片内容与文件扩展名不符，请修正后重新添加');
    return bytes;
  }
}
