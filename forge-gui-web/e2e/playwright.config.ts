import { defineConfig } from '@playwright/test';

// Each test starts a Forge server and home folder of its own (server.ts) and shares nothing, so three run at once
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  workers: 3,
  fullyParallel: true,
  reporter: [['list']],
  use: {
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 20_000,
  },
});
