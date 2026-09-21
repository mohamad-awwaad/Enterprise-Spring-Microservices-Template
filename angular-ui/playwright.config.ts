import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for the end-to-end suite.
 *
 * Runs against the already-running local stack (Angular dev server + BFF + Keycloak + gateway +
 * services) - see E2E_BASE_URL / E2E_KEYCLOAK_* env vars below. Playwright does not start any of
 * those servers itself.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: {
    timeout: 10_000
  },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  reporter: 'list',
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
