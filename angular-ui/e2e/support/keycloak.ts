import { Page, expect } from '@playwright/test';

export const KEYCLOAK_USERNAME = process.env['E2E_KEYCLOAK_USER'] ?? 'user';
export const KEYCLOAK_PASSWORD = process.env['E2E_KEYCLOAK_PASSWORD'] ?? 'password';

/**
 * Starts from the Angular login page, clicks through to Keycloak, signs in with the given
 * credentials and waits for the app to land back on an authenticated route.
 */
export async function loginViaKeycloak(
  page: Page,
  username = KEYCLOAK_USERNAME,
  password = KEYCLOAK_PASSWORD
): Promise<void> {
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in with Keycloak' }).click();

  // Keycloak's own hosted login form.
  await page.getByLabel('Username or email').fill(username);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign In' }).click();

  await expect(page).toHaveURL(/\/dashboard/, { timeout: 20_000 });
}
