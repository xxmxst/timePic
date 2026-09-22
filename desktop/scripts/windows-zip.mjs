import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import path from 'node:path';

export async function compressWindowsZip(source, zip) {
  const powershell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  const command = [
    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()",
    "$ErrorActionPreference = 'Stop'",
    "$ProgressPreference = 'SilentlyContinue'",
    'try {',
    '  $entries = Get-ChildItem -LiteralPath $env:TIMEPIC_ARCHIVE_SOURCE -Force',
    '  Compress-Archive -LiteralPath $entries.FullName -DestinationPath $env:TIMEPIC_ARCHIVE_TARGET -CompressionLevel Optimal -Force',
    '} catch {',
    '  [Console]::Error.WriteLine($_.Exception.Message)',
    "  if ($_.FullyQualifiedErrorId -like 'CompressArchiveUnauthorizedAccessError*') { exit 73 }",
    '  exit 1',
    '}',
  ].join('\n');
  const backoff = [1000, 2000, 4000, 8000];
  for (let attempt = 0; ; attempt++) {
    try {
      await promisify(execFile)(powershell, ['-NoProfile', '-NonInteractive', '-Command', command], {
        windowsHide: true, env: { ...process.env, TIMEPIC_ARCHIVE_SOURCE: source, TIMEPIC_ARCHIVE_TARGET: zip },
      });
      return;
    } catch (error) {
      if (error.code !== 73 || attempt === backoff.length) throw error;
      console.warn(`Archive input temporarily unavailable; retry ${attempt + 1}/${backoff.length} in ${backoff[attempt]} ms`);
      await delay(backoff[attempt]);
    }
  }
}
