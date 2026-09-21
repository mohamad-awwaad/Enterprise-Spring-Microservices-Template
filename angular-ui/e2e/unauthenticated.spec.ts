import { test, expect } from '@playwright/test';

test.describe('Unauthenticated access', () => {
  test('visiting the app without a session shows the login page', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByText('SEC Microservice')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in with Keycloak' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Register' })).toBeVisible();
  });

  test('a direct visit to a protected route redirects to /login', async ({ page }) => {
    await page.goto('/orders');

    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('button', { name: 'Sign in with Keycloak' })).toBeVisible();
  });
});
