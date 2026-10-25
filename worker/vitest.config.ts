import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: './wrangler.jsonc' },
    miniflare: {
      bindings: {
        SEOUL_SUBWAY_KEY: 'test-only-never-a-real-key',
        SEOUL_BUS_KEY: 'test-only-never-a-real-bus-key',
      },
    },
  })],
  test: { fileParallelism: false, testTimeout: 15_000 },
});
