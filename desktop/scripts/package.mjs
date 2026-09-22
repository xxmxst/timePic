import { build, Platform, Arch } from 'electron-builder';
import { createRequire } from 'node:module';
import { rm } from 'node:fs/promises';
import { compressWindowsZip } from './windows-zip.mjs';
import path from 'node:path';
import { retryExecutableOpen } from './executable-retry.mjs';

const require = createRequire(import.meta.url);
const electronPath = require('electron');
const target = process.argv[2] || 'zip';
if (!['zip', 'dir'].includes(target)) throw new Error('Supported targets: zip, dir');
if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('Build on Windows x64.');
const outputRoot = path.resolve('release');
// Scoped wrappers for the pinned builder version, without modifying dependency files.
const integrity = require('app-builder-lib/out/electron/electronWin.js');
const resources = require('app-builder-lib/out/util/resEdit.js');
const originalIntegrity = integrity.addWinAsarIntegrity;
const originalResources = resources.editWindowsResources;
integrity.addWinAsarIntegrity = (file, data) =>
  retryExecutableOpen(() => originalIntegrity(file, data), file, outputRoot);
resources.editWindowsResources = options =>
  retryExecutableOpen(() => originalResources(options), options.file, outputRoot);
try {
  await build({
    targets: Platform.WINDOWS.createTarget('dir', Arch.x64),
    config: {
      // Copy the installed, checksum-verified runtime; avoid renaming a fresh extraction.
      electronDist: path.dirname(electronPath),
      electronLanguages: ['zh-CN', 'en-US'],
      afterPack: async context => {
        await rm(path.join(context.appOutDir, 'resources', 'default_app.asar'), { force: true });
        await rm(path.join(context.appOutDir, 'version'), { force: true });
      },
    },
  });
} finally {
  integrity.addWinAsarIntegrity = originalIntegrity;
  resources.editWindowsResources = originalResources;
}
if (target === 'zip') {
  const version = require('../package.json').version;
  const zip = path.join(outputRoot, 'TimePic-Desktop-' + version + '-win-x64.zip');
  const source = path.join(outputRoot, 'win-unpacked');
  console.log('Creating standalone ZIP with Windows Compress-Archive...');
  await compressWindowsZip(source, zip);
  console.log('Created: ' + zip);
}
