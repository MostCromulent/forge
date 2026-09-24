import { defineConfig } from '@playwright/test';

// Each test starts a Forge server of its own (server.ts), which takes a while: the card database loads first. With a
// server and home folder each, tests share nothing, so three run at once
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
