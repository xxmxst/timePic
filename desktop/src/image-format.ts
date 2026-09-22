import type { ImageFormat } from './shared.ts';

const ascii = (bytes: Uint8Array, offset: number, size: number) => String.fromCharCode(...bytes.subarray(offset, offset + size));
const uint32 = (bytes: Uint8Array, offset: number, littleEndian: boolean) => new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(offset, littleEndian);

/** Check the actual container, including animated PNG/WebP, before decoding. */
export function inspectImage(bytes: Uint8Array): ImageFormat {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpeg';
  if (bytes.length >= 8 && bytes[0] === 137 && ascii(bytes, 1, 7) === 'PNG\r\n\x1a\n') {
    for (let offset = 8; offset + 12 <= bytes.length;) {
      const length = uint32(bytes, offset, false);
      const type = ascii(bytes, offset + 4, 4);
      if (type === 'acTL') throw new Error('暂不支持动画 PNG，请先转换为静态图片');
      if (offset + 12 + length > bytes.length) throw new Error('PNG 文件不完整');
      offset += 12 + length;
      if (type === 'IEND') break;
    }
    return 'png';
  }
  if (bytes.length >= 12 && ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WEBP') {
    for (let offset = 12; offset + 8 <= bytes.length;) {
      const type = ascii(bytes, offset, 4);
      const length = uint32(bytes, offset + 4, true);
      if (type === 'ANIM' || type === 'ANMF' || (type === 'VP8X' && offset + 8 < bytes.length && (bytes[offset + 8] & 2))) {
        throw new Error('暂不支持动画 WebP，请先转换为静态图片');
      }
      if (offset + 8 + length > bytes.length) throw new Error('WebP 文件不完整');
      offset += 8 + length + (length % 2);
    }
    return 'webp';
  }
  throw new Error('图片格式无效，仅支持静态 JPG、PNG、WebP');
}
