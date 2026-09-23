import { defineConfig } from '@playwright/test';

// Each test starts a Forge server of its own (server.ts), which takes a while: the card database loads first
export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  timeout: 240_000,
  expect: { timeout: 20_000 },
  workers: 1,
  reporter: [['list']],
  use: {
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 20_000,
  },
});
