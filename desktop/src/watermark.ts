import { parseTime, validateConfig } from './shared.ts';
import type { WatermarkConfig } from './shared.ts';

type Context = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
export interface Bounds { x: number; y: number; width: number; height: number; margin: number }
interface TextLine { text: string; size: number; bold: boolean; color: string; y: number }
const gold = '#f5c76a';
const family = 'TimePicSans, sans-serif';
const font = (size: number, bold: boolean) => `${bold ? 700 : 400} ${size}px ${family}`;

function wrap(ctx: Context, text: string, width: number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.replace(/\r/g, '').split('\n')) {
    let line = '';
    for (const char of paragraph) {
      if (line && ctx.measureText(line + char).width > width) { lines.push(line); line = ''; }
      line += char;
    }
    lines.push(line);
  }
  return lines;
}

/** Layout is in a 1000-unit coordinate system, shared by preview and full-size export. */
export function drawWatermark(ctx: Context, width: number, height: number, config: WatermarkConfig): Bounds {
  validateConfig(config);
  const date = parseTime(config.time);
  const pad = (n: number) => String(n).padStart(2, '0');
  const day = `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())}`;
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  const weekday = `星期${'日一二三四五六'[date.getDay()]}`;
  const docHeight = 1000 * height / width;
  const unit = Math.min(1, docHeight / 1000) * config.scale;
  const padding = 25 * unit;
  const blockWidth = Math.min(750 * unit, 936);
  const lines: TextLine[] = [];
  let y = padding;
  const add = (text: string, size: number, bold = false, color = '#ffffff', gap = 10) => {
    if (!text.trim()) return;
    const scaledSize = size * unit;
    ctx.font = font(scaledSize, bold);
    for (const part of wrap(ctx, text, blockWidth - 2 * padding)) {
      lines.push({ text: part, size: scaledSize, bold, color, y });
      y += scaledSize * 1.4;
    }
    y += gap * unit;
  };
  if (config.template === 'simple') {
    add(clock, 92, true); add(`${day}  ${weekday}`, 32);
    add(config.address, 32, false, '#ffffff', 7); add(config.note, 28, false, '#e5e8ed', 0);
  } else if (config.template === 'checkin') {
    add('日常打卡  /  TIMEPIC', 25, true, gold, 16);
    add(clock, 80, true); add(`${day}  ${weekday}`, 31, false, '#ffffff', 18);
    add(config.address, 33); add(config.note, 29, false, '#e1e5eb', 0);
  } else {
    add('工程记录', 40, true, gold, 20);
    add(`时间  ${day.replaceAll('.', '-')} ${clock}`, 32);
    add(config.address.trim() ? `地点  ${config.address}` : '', 32);
    add(config.note.trim() ? `备注  ${config.note}` : '', 30, false, '#ffffff', 0);
  }
  const blockHeight = y + padding;
  const margin = 30 * Math.min(1, docHeight / 1000);
  const factor = Math.min(1, (1000 - 2 * margin) / blockWidth, (docHeight - 2 * margin) / blockHeight);
  const bw = blockWidth * factor;
  const bh = blockHeight * factor;
  const x = margin + (1000 - 2 * margin - bw) * config.anchorX;
  const top = margin + (docHeight - 2 * margin - bh) * config.anchorY;
  const ratio = width / 1000;
  ctx.save();
  ctx.scale(ratio, ratio); ctx.translate(x, top); ctx.scale(factor, factor);
  if (config.template !== 'simple') {
    ctx.fillStyle = config.template === 'checkin' ? 'rgba(20,26,34,.753)' : 'rgba(17,24,31,.832)';
    ctx.beginPath(); ctx.roundRect(0, 0, blockWidth, blockHeight, 20 * unit); ctx.fill();
    if (config.template === 'project') {
      ctx.fillStyle = gold; ctx.beginPath(); ctx.roundRect(0, 20 * unit, 7 * unit, blockHeight - 40 * unit, 3 * unit); ctx.fill();
    }
  } else {
    ctx.shadowColor = '#000000'; ctx.shadowBlur = 3 * unit * ratio * factor; ctx.shadowOffsetY = 2 * unit * ratio * factor;
  }
  ctx.textBaseline = 'top';
  for (const line of lines) {
    ctx.font = font(line.size, line.bold); ctx.fillStyle = line.color;
    ctx.fillText(line.text, padding, line.y);
  }
  ctx.restore();
  return { x: x * ratio, y: top * ratio, width: bw * ratio, height: bh * ratio, margin: margin * ratio };
}
