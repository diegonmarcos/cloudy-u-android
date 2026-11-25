/* mySocials image fallback (#1913).
 * One declared CDN base for every page of the set. The data files still carry the legacy
 * front-assets-cdn URLs; they are rewritten at load to MYSOCIALS_CDN_BASE, so once the media
 * folder is pushed to cloudy_db_assets-cdn the images light up with no code change.
 * Any image that still fails to load swaps to an inline SVG placeholder (caption initial or
 * camera glyph); the element, caption, counts and links stay. */
(function () {
  'use strict';
  var MYSOCIALS_CDN_BASE = 'https://cdn.jsdelivr.net/gh/diegonmarcos/cloudy_db_assets-cdn@main/b_Media/mySocials/static/media';
  var LEGACY_RE = /^https:\/\/cdn\.jsdelivr\.net\/gh\/diegonmarcos\/(?:front-assets-cdn|cloudy_db_assets-cdn)@main\/b_Media\/mySocials\/static\/media/;
  window.MYSOCIALS_CDN_BASE = MYSOCIALS_CDN_BASE;

  function rebase(url) {
    return url && LEGACY_RE.test(url) ? url.replace(LEGACY_RE, MYSOCIALS_CDN_BASE) : url;
  }

  function placeholder(alt) {
    var c = (alt || '').trim().charAt(0);
    var generic = /^(post|story \d+|post photo|story)$/i.test((alt || '').trim());
    var glyph;
    if (c && !generic && /[0-9A-Za-zÀ-ɏ]/.test(c)) {
      glyph = '<text x="50" y="50" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="44" fill="#9aa3b2">' + c.toUpperCase().replace(/[<>&]/g, '') + '</text>';
    } else {
      glyph = '<g fill="none" stroke="#9aa3b2" stroke-width="4" stroke-linejoin="round"><path d="M28 38h10l4-6h16l4 6h10a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H28a4 4 0 0 1-4-4V42a4 4 0 0 1 4-4z"/><circle cx="50" cy="56" r="10"/></g>';
    }
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><rect width="100" height="100" fill="#1b2030"/>' + glyph + '</svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }

  function fix(img) {
    if (!img || img.tagName !== 'IMG' || img.getAttribute('data-fb') === 'done') return;
    var s = img.getAttribute('src');
    var r = rebase(s);
    if (r && r !== s) img.setAttribute('src', r);
  }

  function swap(img) {
    img.setAttribute('data-fb', 'done');
    img.setAttribute('data-fb-src', img.getAttribute('src') || '');
    img.removeAttribute('srcset');
    img.setAttribute('src', placeholder(img.getAttribute('alt')));
    img.style.objectFit = 'cover';
  }

  document.addEventListener('error', function (ev) {
    var img = ev.target;
    if (!img || img.tagName !== 'IMG' || img.getAttribute('data-fb') === 'done') return;
    if ((img.getAttribute('src') || '').indexOf('data:image/svg+xml') === 0) return;
    swap(img);
  }, true);

  function scan(root) {
    if (root.nodeType !== 1) return;
    if (root.tagName === 'IMG') fix(root);
    var list = root.querySelectorAll ? root.querySelectorAll('img') : [];
    for (var i = 0; i < list.length; i++) fix(list[i]);
  }

  new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      if (m.type === 'attributes') fix(m.target);
      else m.addedNodes.forEach(scan);
    });
  }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
})();
