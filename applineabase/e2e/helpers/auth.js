/**
 * Login con el usuario ADMIN que siembra com.example.security.DataSeeder al primer arranque
 * (username "admin" - ver esa clase para la contraseña). Si ese valor cambia en el proyecto,
 * hay que actualizarlo aca o pasarlo por APP_TEST_USER/APP_TEST_PASS.
 */
async function login(page) {
  await page.goto('/');
  await page.locator('input[name="username"]').fill(process.env.APP_TEST_USER || 'admin');
  await page.locator('input[name="password"]').fill(process.env.APP_TEST_PASS || 'admin123');
  await page.locator('input[name="password"]').press('Enter');
  await page.locator('input[name="username"]').waitFor({ state: 'detached', timeout: 15_000 });
}

module.exports = { login };
