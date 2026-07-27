const { test, expect } = require('../fixtures');
const { login } = require('../helpers/auth');

test.describe('Histórico (/historico)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('carga y permite cambiar de máquina', async ({ page }) => {
    await page.goto('/historico');

    const maquinaCombo = page.locator('vaadin-combo-box').first();
    await expect(maquinaCombo).toBeVisible({ timeout: 15_000 });

    await maquinaCombo.click();
    const opciones = page.locator('vaadin-combo-box-item');
    if (await opciones.count() > 1) {
      await opciones.nth(1).click();
    } else {
      await page.keyboard.press('Escape');
    }
  });
});
