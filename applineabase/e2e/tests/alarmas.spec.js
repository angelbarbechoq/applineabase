const { test, expect } = require('../fixtures');
const { login } = require('../helpers/auth');

test.describe('Alarmas', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('/alarmas carga para un usuario con permiso', async ({ page }) => {
    await page.goto('/alarmas');
    await expect(page.locator('vaadin-grid')).toBeVisible({ timeout: 15_000 });
  });

  test('/alarmas/historial muestra el catálogo completo de líneas', async ({ page }) => {
    await page.goto('/alarmas/historial');

    const lineaFiltro = page.locator('vaadin-combo-box').first();
    await lineaFiltro.click();
    const opciones = page.locator('vaadin-combo-box-item');
    expect(await opciones.count()).toBeGreaterThan(1);
    await page.keyboard.press('Escape');
  });

  test('/alarmas/config valida campos obligatorios al guardar', async ({ page }) => {
    await page.goto('/alarmas/config');

    const guardarBtn = page.locator('vaadin-button', { hasText: 'Guardar' });
    await guardarBtn.click();
    await expect(page.locator('vaadin-notification-card')).toBeVisible({ timeout: 5_000 });
  });

  test('/alarmas/historial descarga un CSV con el encabezado esperado', async ({ page }) => {
    await page.goto('/alarmas/historial');

    const link = page.locator('a', { hasText: 'Descargar CSV' });
    await expect(link).toBeVisible({ timeout: 15_000 });

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      link.click(),
    ]);
    expect(download.suggestedFilename()).toBe('alarmas-historial.csv');

    const stream = await download.createReadStream();
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    const contenido = Buffer.concat(chunks).toString('utf8');
    expect(contenido).toContain('Inicio;Línea/Máquina;Tipo;Estado;Detalle');
  });
});
