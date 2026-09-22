import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/** Retry only failed opens of an EXE inside this project's build output. */
export async function retryExecutableOpen(operation, executable, outputRoot, wait = delay) {
  const target = path.resolve(executable);
  const relative = path.relative(path.resolve(outputRoot), target);
  const allowed = relative && !relative.startsWith('..') && !path.isAbsolute(relative) && path.extname(target).toLowerCase() === '.exe';
  const backoff = [1000, 2000, 4000, 8000];
  for (let attempt = 0; ; attempt++) {
    try { return await operation(); }
    catch (error) {
      const sameFile = typeof error.path === 'string' && path.resolve(error.path).toLowerCase() === target.toLowerCase();
      const retryable = allowed && sameFile && error.syscall === 'open' && ['EPERM', 'EACCES', 'EBUSY', 'UNKNOWN'].includes(error.code);
      if (!retryable || attempt === backoff.length) throw error;
      console.warn(`Executable temporarily unavailable; retry ${attempt + 1}/${backoff.length} in ${backoff[attempt]} ms: ${target}`);
      await wait(backoff[attempt]);
    }
  }
}
