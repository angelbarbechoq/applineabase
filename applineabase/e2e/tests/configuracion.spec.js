const { test, expect } = require('../fixtures');
const { login } = require('../helpers/auth');

test.describe('Configuración y Usuarios', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('/configuracion carga sin errores', async ({ page }) => {
    await page.goto('/configuracion');
    await expect(page.locator('vaadin-tabsheet, h3').first()).toBeVisible({ timeout: 15_000 });
  });

  test('/usuarios muestra el catálogo de zonas', async ({ page }) => {
    await page.goto('/usuarios');

    const zonaCombo = page.locator('vaadin-combo-box').first();
    await zonaCombo.click();
    const opciones = page.locator('vaadin-combo-box-item');
    expect(await opciones.count()).toBeGreaterThan(0);
    await page.keyboard.press('Escape');
  });
});
