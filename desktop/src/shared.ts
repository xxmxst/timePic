export type Template = 'simple' | 'checkin' | 'project';
export type ImageFormat = 'jpeg' | 'png' | 'webp';
export interface WatermarkConfig {
  template: Template;
  time: string;
  address: string;
  note: string;
  scale: number;
  anchorX: number;
  anchorY: number;
}
export interface Photo {
  id: string;
  name: string;
  path: string;
  size: number;
  format: ImageFormat;
}
export interface Overrides { time?: string; address?: string }
export interface Settings {
  template: Template; note: string; scale: number; anchorX: number; anchorY: number;
  outputDirectory: string;
}
export interface DesktopApi {
  getSettings(): Promise<Partial<Settings>>;
  saveSettings(value: Settings): Promise<void>;
  chooseImages(): Promise<{ photos: Photo[]; errors: string[] }>;
  forgetImages(ids: string[]): Promise<void>;
  readImage(id: string): Promise<Uint8Array>;
  chooseOutput(): Promise<string | null>;
  beginExport(ids: string[]): Promise<string>;
  writeImage(job: string, id: string, bytes: Uint8Array): Promise<string>;
  finishExport(job: string): Promise<void>;
  openOutput(): Promise<void>;
}
export function localTime(date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
export function defaultConfig(): WatermarkConfig {
  return { template: 'checkin', time: localTime(), address: '', note: '', scale: 1, anchorX: 0, anchorY: 1 };
}
export function effectiveConfig(common: WatermarkConfig, overrides: Overrides): WatermarkConfig {
  return { ...common, ...overrides };
}
export function parseTime(value: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) throw new Error('请填写完整的日期和时间');
  const [, year, month, day, hour, minute, second = '0'] = match;
  const d = new Date(0);
  d.setFullYear(+year, +month - 1, +day);
  d.setHours(+hour, +minute, +second, 0);
  if (+year < 1900 || +year > 9999 || d.getFullYear() !== +year || d.getMonth() !== +month - 1 || d.getDate() !== +day || d.getHours() !== +hour || d.getMinutes() !== +minute || d.getSeconds() !== +second) {
    throw new Error('日期或时间无效，请检查后重试');
  }
  return d;
}
export function validateConfig(config: WatermarkConfig): void {
  parseTime(config.time);
  if (!['simple', 'checkin', 'project'].includes(config.template)) throw new Error('水印模板无效');
  if (config.address.length > 300 || config.note.length > 300) throw new Error('地址和备注分别最多 300 字');
  if (!Number.isFinite(config.scale) || config.scale < 0.5 || config.scale > 1.5) throw new Error('水印大小无效');
  if (![config.anchorX, config.anchorY].every(v => Number.isFinite(v) && v >= 0 && v <= 1)) throw new Error('水印位置无效');
}
export function errorText(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');
}
