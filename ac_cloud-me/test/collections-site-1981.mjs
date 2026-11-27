#!/usr/bin/env node
/* #1981 Playwright guard on the MyTrips site: Collections view, zero broken images on the cards and in every overlay, same-origin photos, credit shown.
 * Usage: node collections-site-1981.mjs URL OUT_DIR   (URL of the MyTrips site, e.g. https://diegonmarcos.github.io/mymaps-mytrips/ or a local copy of its dist)
 * Runs collections-probe-1981.expr.js (the expression the emulator WebView run uses too). Writes OUT_DIR/<name>.json and screenshots collections.png, collections-overlay.png.
 * Exit 1 when the probe reports a problem. Needs `playwright` (installed by the workflow). */
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const [url, out = 'out'] = process.argv.slice(2);
if (!url) { console.error('usage: collections-site-1981.mjs URL OUT_DIR'); process.exit(2); }
fs.mkdirSync(out, { recursive: true });
const probe = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'collections-probe-1981.expr.js'), 'utf8');
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2 });
await p.goto(url, { waitUntil: 'load', timeout: 60000 });
await p.waitForTimeout(3000);
await p.getByText('Collections', { exact: true }).first().click();
await p.waitForSelector('.theme-card', { timeout: 30000 }).catch(() => {});
await p.waitForTimeout(4000);
await p.screenshot({ path: path.join(out, 'collections.png') });
const res = JSON.parse(await p.evaluate(probe));
fs.writeFileSync(path.join(out, 'probe.json'), JSON.stringify(res, null, 2));
const first = p.locator('.theme-card').first();
if (await first.count()) { await first.click(); await p.waitForTimeout(2500); await p.screenshot({ path: path.join(out, 'collections-overlay.png') }); }
await b.close();
console.log(JSON.stringify({ ok: res.ok, cards: res.cards, cardImages: res.cardImages, overlays: res.overlays.length, problems: res.problems }, null, 1));
process.exit(res.ok ? 0 : 1);
