import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// Measurements are run by hand, one at a time so they do not compete for the CPU, and never with the tests
export default defineConfig({
  ...base,
  testMatch: '*.measure.ts',
  workers: 1,
  fullyParallel: false,
});
