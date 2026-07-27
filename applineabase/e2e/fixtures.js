const base = require('@playwright/test');

/**
 * Extiende el "page" de Playwright para que cualquier error de consola o de página inesperado
 * haga fallar el test automaticamente, en vez de que cada spec tenga que escuchar los eventos
 * por su cuenta.
 *
 * "am5 is not defined" (amCharts5, cargado desde cdn.amcharts.com en frontend/index.html) es
 * ruido conocido SOLO en el sandbox de desarrollo de Claude Code: ese entorno bloquea el CDN a
 * nivel de proxy (confirmado con curl: 403 en el tunel). En GitHub Actions y en produccion hay
 * internet normal, asi que si este mismo error aparece ahi es una senal real (el CDN cayo, o la
 * red de la empresa lo esta bloqueando) y NO debe ignorarse - por eso el filtro de am5 solo se
 * activa con CI_SANDBOX_IGNORE_AM5=true, nunca por defecto.
 */
const IGNORE_SIEMPRE = [/TUNNEL/, /\b404\b/];
const IGNORE_SOLO_SANDBOX = [/am5.*is not defined/i];

const test = base.test.extend({
  page: async ({ page }, use) => {
    const errores = [];
    const ignorarAm5 = process.env.CI_SANDBOX_IGNORE_AM5 === 'true';
    const patrones = ignorarAm5 ? [...IGNORE_SIEMPRE, ...IGNORE_SOLO_SANDBOX] : IGNORE_SIEMPRE;

    page.on('pageerror', (e) => {
      if (!patrones.some((p) => p.test(e.message))) {
        errores.push(`pageerror: ${e.message}`);
      }
    });
    page.on('console', (msg) => {
      if (msg.type() === 'error' && !patrones.some((p) => p.test(msg.text()))) {
        errores.push(`console: ${msg.text()}`);
      }
    });

    await use(page);

    if (errores.length > 0) {
      throw new Error(`Errores inesperados de navegador:\n${errores.join('\n')}`);
    }
  },
});

module.exports = { test, expect: base.expect };
