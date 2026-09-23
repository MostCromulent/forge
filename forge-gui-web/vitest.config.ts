import { defineConfig } from 'vitest/config';

// The browser client's own tests. They run on Node: what they test (the model, the auto-pass countdown, motion's rules) never
// touches the page.
export default defineConfig({
  test: {
    include: ['src/test/ts/**/*.test.ts'],
  },
});
