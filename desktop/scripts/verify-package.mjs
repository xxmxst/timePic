import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, readFile, writeFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const version = require('../package.json').version;
const zip = path.join(root, 'release', `TimePic-Desktop-${version}-win-x64.zip`);
await stat(zip);
await mkdir(path.join(root, 'test-results'), { recursive: true });
const extracted = await mkdtemp(path.join(root, 'test-results', '解压 验证 '));
const powershell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
await promisify(execFile)(powershell, ['-NoProfile', '-NonInteractive', '-Command',
  "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'; Expand-Archive -LiteralPath $env:TIMEPIC_VERIFY_ZIP -DestinationPath $env:TIMEPIC_VERIFY_DIR"
], { windowsHide: true, env: { ...process.env, TIMEPIC_VERIFY_ZIP: zip, TIMEPIC_VERIFY_DIR: extracted } });
const executable = path.join(extracted, 'TimePic.exe');
await stat(executable);
console.log('Testing extracted executable: ' + executable);
const result = await promisify(execFile)(process.execPath, [path.join(root, 'node_modules', '@playwright', 'test', 'cli.js'), 'test', '--reporter=list'], {
  cwd: root, windowsHide: true, maxBuffer: 4 * 1024 * 1024,
  env: { ...process.env, TIMEPIC_PACKAGED_EXE: executable },
}).catch(error => { process.stdout.write(error.stdout || ''); process.stderr.write(error.stderr || ''); throw error; });
process.stdout.write(result.stdout);
process.stderr.write(result.stderr);
const bytes = await readFile(zip);
const record = {
  verifiedAt: new Date().toISOString(), zip, executable,
  bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'),
  isolatedCwd: true, systemOnlyPath: true, remoteRequestsBlocked: true,
  tests: result.stdout,
};
await writeFile(path.join(root, 'test-results', 'package-verification.json'), JSON.stringify(record, null, 2), 'utf8');
console.log('Verified ZIP SHA-256: ' + record.sha256);
