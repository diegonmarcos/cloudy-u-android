(async () => {
  /* #1981 the Collections guard, ONE expression for every runner: Playwright on the MyTrips site (collections-site-1981.mjs) and the Cloud Me emulator WebView
     (webview-eval-1980.js collections, over Chrome DevTools). Run on the page that shows the Collections cards. Returns JSON {ok, problems[], cards, overlays[]}.
     Checks: 16 cards, every card image loaded (complete, naturalWidth > 0), none a placeholder (naturalWidth < 100) or from another origin (no image service), then each
     card is opened: the overlay images all loaded, same-origin, and its photo credit (author, licence, source link) is shown; then closed. */
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const settle = async (get, ms = 15000) => { const t = Date.now(); while (Date.now() - t < ms) { if (get().every(i => i.complete)) return; await sleep(250); } };
  const same = i => { try { const u = new URL(i.currentSrc || i.src, location.href); return u.protocol === location.protocol && u.host === location.host; } catch (e) { return false; } };
  const stat = l => ({ total: l.length, broken: l.filter(i => i.complete && i.naturalWidth === 0).length, pending: l.filter(i => !i.complete).length,
    external: l.filter(i => !same(i)).length, tiny: l.filter(i => i.complete && i.naturalWidth > 0 && i.naturalWidth < 100).length });
  const problems = [], out = { overlays: [] };
  for (let k = 0; k < 40 && !document.querySelector('.theme-card'); k++) await sleep(500);
  const cards = [...document.querySelectorAll('.theme-card')]; out.cards = cards.length;
  if (cards.length !== 16) problems.push('cards: ' + cards.length + ' (want 16)');
  const ci = () => [...document.querySelectorAll('.theme-card__img')];
  await settle(ci); out.cardImages = stat(ci());
  const s = out.cardImages;
  if (s.total !== cards.length) problems.push('card images: ' + s.total + ' for ' + cards.length + ' cards');
  for (const k of ['broken', 'pending', 'external', 'tiny']) if (s[k]) problems.push('card images ' + k + ': ' + s[k]);
  for (const c of cards) {
    const title = (c.querySelector('.theme-card__title') || {}).textContent || '?';
    c.click();
    for (let k = 0; k < 20 && !document.querySelector('.theme-overlay--visible'); k++) await sleep(150);
    const oi = () => [...document.querySelectorAll('.theme-overlay img')];
    await sleep(300); await settle(oi);
    const st = stat(oi()), cr = document.querySelector('.theme-overlay__credit'), txt = cr ? cr.textContent.trim() : '', links = cr ? [...cr.querySelectorAll('a')].filter(a => /^https?:/.test(a.href)).length : 0;
    out.overlays.push(Object.assign({ title, credit: txt }, st));
    if (st.total < 3) problems.push(title + ': overlay has ' + st.total + ' images (want 3)');
    for (const k of ['broken', 'pending', 'external', 'tiny']) if (st[k]) problems.push(title + ': overlay images ' + k + ': ' + st[k]);
    if (!/^Photo: .+/.test(txt) || txt.indexOf('Wikimedia Commons') < 0 || links < 2) problems.push(title + ': overlay shows no photo credit (' + txt + ')');
    const b = document.querySelector('.theme-overlay__back'); if (b) b.click(); else problems.push(title + ': overlay has no Back button');
    for (let k = 0; k < 20 && document.querySelector('.theme-overlay--visible'); k++) await sleep(150);
    await sleep(650);
  }
  out.problems = problems; out.ok = problems.length === 0; out.url = location.href;
  return JSON.stringify(out);
})()
