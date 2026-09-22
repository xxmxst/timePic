import { contextBridge, ipcRenderer } from 'electron';
import type { DesktopApi } from '../src/shared.ts';

const api: DesktopApi = {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: value => ipcRenderer.invoke('settings:save', value),
  chooseImages: () => ipcRenderer.invoke('images:choose'),
  forgetImages: ids => ipcRenderer.invoke('images:forget', ids),
  readImage: id => ipcRenderer.invoke('images:read', id),
  chooseOutput: () => ipcRenderer.invoke('output:choose'),
  beginExport: ids => ipcRenderer.invoke('export:begin', ids),
  writeImage: (job, id, bytes) => ipcRenderer.invoke('export:write', job, id, bytes),
  finishExport: job => ipcRenderer.invoke('export:finish', job),
  openOutput: () => ipcRenderer.invoke('output:open'),
};
contextBridge.exposeInMainWorld('timepic', api);
