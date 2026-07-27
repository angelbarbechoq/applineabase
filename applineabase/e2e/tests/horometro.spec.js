const { test, expect } = require('../fixtures');
const { login } = require('../helpers/auth');

test.describe('Horómetro (/horometro)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('carga y permite cambiar de pestaña por zona', async ({ page }) => {
    await page.goto('/horometro');
    await expect(page.locator('vaadin-tabs').first()).toBeVisible({ timeout: 15_000 });

    const tabExtrusion = page.locator('vaadin-tab', { hasText: 'Extrusión' });
    if (await tabExtrusion.count() > 0) {
      await tabExtrusion.click();
    }

    const tabCasaFuerza = page.locator('vaadin-tab', { hasText: 'Casa Fuerza' });
    if (await tabCasaFuerza.count() > 0) {
      await tabCasaFuerza.click();
    }
  });
});
