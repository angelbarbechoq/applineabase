// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * baseURL apunta a una instancia de la app ya corriendo (ver .github/workflows/e2e.yml, que la
 * arranca antes de este paso) - estas pruebas no levantan la app por si mismas.
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
  use: {
    baseURL: process.env.APP_BASE_URL || 'http://localhost:8081',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Solo para correr localmente contra un Chromium ya instalado en una ruta no estandar (p. ej.
    // este sandbox de desarrollo); en CI real "playwright install" deja la version correcta donde
    // Playwright la espera, asi que esta variable queda sin definir y no hace falta.
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH }
      : {},
  },
});
