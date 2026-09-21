import { expect, test } from '@playwright/test';
import { loginViaKeycloak } from './support/keycloak';

// Login must happen before creating an order, and logout must happen last, so this whole journey
// runs serially on one page/session rather than Playwright's usual fresh-context-per-test
// isolation.
test.describe.serial('Orders journey', () => {
  test('logs in, creates an order, confirms it appears in the list, then logs out', async ({
    page
  }) => {
    await loginViaKeycloak(page);
    await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();

    await page.getByRole('link', { name: 'Orders', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible();

    const [response] = await Promise.all([
      page.waitForResponse(
        res => res.url().includes('/bff/api/orders') && res.request().method() === 'POST'
      ),
      page.getByRole('button', { name: 'Create Order' }).click()
    ]);

    expect(response.ok()).toBe(true);
    const created = await response.json();

    await expect(page.getByRole('cell', { name: created.orderNumber })).toBeVisible();

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('button', { name: 'Sign in with Keycloak' })).toBeVisible();

    const userResponse = await page.request.get('/bff/user', {
      // Ask as the Angular app does (JSON): the BFF answers 401 for API clients and 302 to the
      // login page for plain browser navigations.
      headers: { Accept: 'application/json' },
      maxRedirects: 0
    });
    expect(userResponse.status()).toBe(401);
  });
});
