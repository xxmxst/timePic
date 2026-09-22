/// <reference types="vite/client" />
import type { DesktopApi } from './shared.ts';
declare global { interface Window { timepic: DesktopApi } }
