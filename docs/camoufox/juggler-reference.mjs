// Reference side of the manual comparison in docs/camoufox.md: Camoufox's own launcher (npm package
// "camoufox", Juggler), with exactly the launch options the playwright4j side gets. A diagnostic tool
// for the comparison, not part of playwright4j.
//
//   node juggler-reference.mjs save <options.json> <camoufox executable> [Camoufox options: JSON or a JSON file]
//   node juggler-reference.mjs run <options.json> <camoufox executable> <profile dir> [start URL] [minutes]
//
// "save" draws one fingerprint and writes the Camoufox variables (CAMOU_CONFIG_n, CAMOU_PREFS_n, ...),
// the Firefox preferences and the arguments; it never writes the rest of the process environment.
// "run" starts Camoufox with them, persistent and headed without viewport and media emulation, prints
// what the page sees (the same probe as CamoufoxManualSessionTest) and waits until the window is closed.
import { Camoufox, launchOptions } from 'camoufox';
import { readFileSync, writeFileSync } from 'node:fs';

const PROBE = `(() => { const r = {
  userAgent: navigator.userAgent, platform: navigator.platform, webdriver: navigator.webdriver,
  hardwareConcurrency: navigator.hardwareConcurrency, languages: navigator.languages,
  screen: screen.width + 'x' + screen.height, window: outerWidth + 'x' + outerHeight + ' / inner ' + innerWidth + 'x' + innerHeight,
  devicePixelRatio: devicePixelRatio, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  colorScheme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
  hasFocus: document.hasFocus(), visibilityState: document.visibilityState };
  try { const gl = document.createElement('canvas').getContext('webgl');
    const ext = gl && gl.getExtension('WEBGL_debug_renderer_info');
    r.webglRenderer = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null; } catch (e) { r.webglRenderer = String(e); }
  return JSON.stringify(r, null, 2); })()`;

const [mode, file, ...rest] = process.argv.slice(2);
if (mode === 'save') {
  const [executable, extra] = rest;
  // A file avoids quoting JSON on the Windows command line.
  const camoufoxOptions = !extra ? {} : JSON.parse(extra.trim().startsWith('{') ? extra : readFileSync(extra, 'utf8'));
  const options = await launchOptions({ ...camoufoxOptions, executable_path: executable, headless: false });
  const env = Object.fromEntries(Object.entries(options.env)
    .filter(([name]) => name.startsWith('CAMOU_') || name.startsWith('FONTCONFIG')));
  writeFileSync(file, JSON.stringify({ env, firefoxUserPrefs: options.firefoxUserPrefs, args: options.args }, null, 1));
  console.log(`saved ${Object.keys(env).join(', ')}, ${Object.keys(options.firefoxUserPrefs).length} preferences, `
    + `arguments ${JSON.stringify(options.args)} to ${file}`);
} else if (mode === 'run') {
  const [executable, profile, startUrl = 'about:blank', minutes = '30'] = rest;
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  const context = await Camoufox({
    from_options: {
      ...saved,
      env: { ...process.env, ...saved.env },
      executablePath: executable,
      headless: false,
      viewport: null,
      // With from_options the launcher takes the profile from there, not from its own options.
      user_data_dir: profile,
    },
    persistent_context: true,
  });
  const closed = new Promise(resolve => context.on('close', resolve));
  const page = context.pages()[0] ?? await context.newPage();
  if (startUrl !== 'about:blank')
    await page.goto(startUrl);
  console.log('[camoufox] what the page sees:\n' + await page.evaluate(PROBE));
  console.log(`[camoufox] browser is open; close its window to end (at most ${minutes} min)`);
  const timeUp = new Promise(resolve => setTimeout(() => resolve('time'), Number(minutes) * 60_000).unref());
  if (await Promise.race([closed, timeUp]) === 'time') {
    console.log('[camoufox] time is up, closing');
    await context.close();
  }
  console.log('[camoufox] browser closed');
} else {
  console.error('usage: node juggler-reference.mjs save <options.json> <executable> [options] | run <options.json> <executable> <profile> [url] [minutes]');
  process.exit(2);
}
