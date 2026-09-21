import { expect, test } from '@playwright/test';
import { loginViaKeycloak } from './support/keycloak';

// Login must happen before the profile steps, and logout must happen last, so this whole
// journey runs serially on one page/session rather than Playwright's usual fresh-context-per-test
// isolation.
test.describe.serial('Profile journey', () => {
  test('logs in, creates a profile if missing, verifies it, then logs out', async ({ page }) => {
    await loginViaKeycloak(page);
    await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible();

    await page.getByRole('link', { name: 'Profile', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Profile', exact: true })).toBeVisible();
    // Wait out the initial GET /bff/api/profile before inspecting which state we landed in -
    // isVisible() itself doesn't wait, so checking it while the spinner is still up would race.
    await expect(page.getByRole('progressbar')).toHaveCount(0);

    // Profile may already exist from an earlier manual/test run. The app has no "edit profile"
    // feature (profile.service only supports create/get/delete), so to reach a known, idempotent
    // starting point we delete any pre-existing profile before creating a fresh one.
    if (await page.getByRole('button', { name: 'Delete Profile' }).isVisible()) {
      await deleteCurrentProfile(page);
    }

    await expect(page.getByLabel('First Name')).toBeVisible();

    const firstName = 'Ada';
    const lastName = 'Lovelace';
    const email = 'ada.lovelace@example.com';

    await page.getByLabel('First Name').fill(firstName);
    await page.getByLabel('Last Name').fill(lastName);
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Gender').click();
    await page.getByRole('option', { name: 'Female' }).click();
    await page.getByLabel('Age').fill('30');

    await page.getByRole('button', { name: 'Create Profile' }).click();

    // Verify: the card now shows the profile that was just created.
    await expect(page.getByText(`${firstName} ${lastName}`)).toBeVisible();
    await expect(page.getByText(email)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Delete Profile' })).toBeVisible();

    // Clean up the test data we created, since the UI supports deleting a profile.
    await deleteCurrentProfile(page);
    await expect(page.getByLabel('First Name')).toBeVisible();

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('button', { name: 'Sign in with Keycloak' })).toBeVisible();

    const response = await page.request.get('/bff/user', {
      // Ask as the Angular app does (JSON): the BFF answers 401 for API clients and 302 to the
      // login page for plain browser navigations.
      headers: { Accept: 'application/json' },
      maxRedirects: 0
    });
    expect(response.status()).toBe(401);
  });
});

async function deleteCurrentProfile(page: import('@playwright/test').Page): Promise<void> {
  await page.getByRole('button', { name: 'Delete Profile' }).click();
  await expect(page.getByRole('heading', { name: 'Delete Profile' })).toBeVisible();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Delete Profile' })).toHaveCount(0);
}
