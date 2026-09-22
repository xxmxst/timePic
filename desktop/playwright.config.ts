import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/e2e', outputDir: './test-results/e2e', timeout: 180000,
  expect: { timeout: 15000 }, workers: 1, fullyParallel: false,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { screenshot: 'only-on-failure', trace: 'retain-on-failure' },
});
