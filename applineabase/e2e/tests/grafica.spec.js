const { test, expect } = require('../fixtures');
const { login } = require('../helpers/auth');

test.describe('Gráficas (/grafica)', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('carga, permite click en el gráfico y cambiar de pestaña', async ({ page }) => {
    await page.goto('/grafica');

    const chartDiv = page.locator('#chartdiv_industrial');
    await expect(chartDiv).toBeVisible({ timeout: 15_000 });

    const box = await chartDiv.boundingBox();
    if (box) {
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    }

    const tabTemperatura = page.locator('vaadin-tab', { hasText: 'Temperatura' });
    if (await tabTemperatura.count() > 0) {
      await tabTemperatura.click();
    }

    const tabPF = page.locator('vaadin-tab', { hasText: 'PF general' });
    if (await tabPF.count() > 0) {
      await tabPF.click();
    }
  });
});
