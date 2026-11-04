/* cloud-media-center — the seven pages.
 *
 * Two halves. RENDER is pure: data in, HTML string out, no DOM and no
 * network, so test/test-media-center-pages.sh runs it under node against the
 * committed web/data. BOOT is the browser half: it loads ../data/<page>.json,
 * wires search and chips, and hands every outbound link to the system through
 * tauri-plugin-opener (a plain new tab when the page runs outside the app).
 */
(function (root) {
  'use strict';

  var PAGES = [
    { id: 'music', label: 'Music', icon: '♪' },
    { id: 'videos', label: 'Videos', icon: '▶' },
    { id: 'gallery', label: 'Gallery', icon: '▦' },
    { id: 'books', label: 'Books', icon: '❏' },
    { id: 'feed', label: 'Feed', icon: '≡' },
    { id: 'movies', label: 'Movies', icon: '◉' },
    { id: 'documentaries', label: 'Docs', icon: '◎' }
  ];
  // not a data page (no web/data/<id>.json): the synced playlists of #1469, drawn by assets/media-sync.js
  var EXTRA = [{ id: 'library', label: 'Library', icon: '☰' }];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function norm(s) { return String(s || '').toLowerCase(); }
  function matches(q, parts) {
    if (!q) return true;
    var hay = norm(parts.join(' '));
    return norm(q).split(/\s+/).every(function (w) { return hay.indexOf(w) >= 0; });
  }
  function ext(url, inner, cls) {
    return '<a class="' + (cls || '') + '" href="' + esc(url) + '" data-ext>' + inner + '</a>';
  }
  function img(cls, url, fallbackText, fallbackColor) {
    var style = url ? ' style="background-image:url(\'' + esc(url) + '\')"' : '';
    var fb = url ? '' : '<div class="fallback" style="background:' + esc(fallbackColor || '#2a3142') + '">' + esc(fallbackText) + '</div>';
    return '<div class="card__img ' + cls + '"' + style + '>' + fb;
  }
  function ytThumb(id) { return 'https://i.ytimg.com/vi/' + id + '/mqdefault.jpg'; }
  function ytUrl(id) { return 'https://www.youtube.com/watch?v=' + id; }
  function hue(s) { var h = 0; for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return 'hsl(' + h + ',45%,32%)'; }
  function plural(n, w) { return n + ' ' + w + (n === 1 ? '' : 's'); }

  /* ── music ─────────────────────────────────────────────────────────── */
  function tidalUrl(d, title, artist) { return d.search + encodeURIComponent((artist + ' ' + title).trim()); }
  function musicPlaylists(d, section) {
    var out = [];
    d.sections.forEach(function (s) {
      if (section && s.name !== section) return;
      s.folders.forEach(function (f) { f.playlists.forEach(function (p) { out.push({ section: s.name, folder: f.name, p: p }); }); });
    });
    return out;
  }
  function renderMusic(d, st) {
    st = st || {};
    if (st.cfg && st.cfg.search) d = Object.assign({}, d, { search: st.cfg.search });
    if (st.playlist) {
      var hit = musicPlaylists(d).filter(function (x) { return x.folder + '/' + x.p.name === st.playlist; })[0];
      if (!hit) return '<p class="empty">Playlist not found.</p>';
      var rows = hit.p.items.filter(function (t) { return matches(st.q, t); });
      return '<button class="btn" data-back>‹ ' + esc(hit.folder) + '</button>' +
        '<h2 class="section-title">' + esc(hit.p.name) + ' · ' + plural(hit.p.items.length, 'track') + '</h2>' +
        '<div class="list">' + rows.map(function (t, i) {
          return ext(tidalUrl(d, t[0], t[1]), '<span class="row__num">' + (i + 1) + '</span><span class="row__main"><div class="row__title">' +
            esc(t[0]) + '</div><div class="row__sub">' + esc(t[1]) + '</div></span><span class="row__go">↗</span>', 'row');
        }).join('') + '</div>';
    }
    if (st.q) {
      var found = [];
      musicPlaylists(d, st.section).some(function (x) {
        x.p.items.forEach(function (t) { if (found.length < 200 && matches(st.q, t)) found.push({ t: t, x: x }); });
        return found.length >= 200;
      });
      if (!found.length) return '<p class="empty">No track matches “' + esc(st.q) + '”.</p>';
      return '<h2 class="section-title">' + (found.length >= 200 ? 'First 200 tracks' : plural(found.length, 'track')) + '</h2><div class="list">' +
        found.map(function (h) {
          return ext(tidalUrl(d, h.t[0], h.t[1]), '<span class="row__main"><div class="row__title">' + esc(h.t[0]) + '</div><div class="row__sub">' +
            esc(h.t[1]) + ' · ' + esc(h.x.p.name) + '</div></span><span class="row__go">↗</span>', 'row');
        }).join('') + '</div>';
    }
    return d.sections.filter(function (s) { return !st.section || s.name === st.section; }).map(function (s) {
      return s.folders.map(function (f) {
        return '<h2 class="section-title">' + esc(s.name) + ' · ' + esc(f.name) + '</h2><div class="grid">' + f.playlists.map(function (p) {
          return '<div class="card" data-playlist="' + esc(f.name + '/' + p.name) + '">' + img('', p.cover, p.name, hue(f.name + p.name)) + '</div>' +
            '<div class="card__body"><div class="card__title">' + esc(p.name) + '</div><div class="card__meta">' + plural(p.tracks, 'track') + '</div></div></div>';
        }).join('') + '</div>';
      }).join('');
    }).join('');
  }

  /* ── videos ────────────────────────────────────────────────────────── */
  function videoCard(v, noDur) {
    return ext(ytUrl(v.id), img('card__img--video', ytThumb(v.id)) + (v.duration && noDur !== true ? '<span class="badge">' + esc(v.duration) + '</span>' : '') +
      '</div><div class="card__body"><div class="card__title">' + esc(v.title) + '</div></div>', 'card');
  }
  function renderVideos(d, st) {
    st = st || {};
    var html = d.playlists.filter(function (p) { return !st.section || p.name === st.section; }).map(function (p) {
      var vids = p.videos.filter(function (v) { return matches(st.q, [v.title, p.name]); });
      if (!vids.length) return '';
      return '<h2 class="section-title">' + esc(p.name) + ' · ' + plural(vids.length, 'video') + '</h2><div class="grid">' + vids.map(function (v) { return videoCard(v, !!(st.cfg && st.cfg.durations === false)); }).join('') + '</div>';
    }).join('');
    return html || '<p class="empty">No video matches.</p>';
  }

  /* ── gallery ───────────────────────────────────────────────────────── */
  function renderGallery(d, st) {
    st = st || {};
    var boards = d.boards.filter(function (b) { return matches(st.q, [b.name]); });
    if (!boards.length) return '<p class="empty">No board matches.</p>';
    return '<div class="grid">' + boards.map(function (b) {
      return ext(b.url, img('', b.cover, b.name, hue(b.name)) + '</div><div class="card__body"><div class="card__title">' + esc(b.name) +
        '</div><div class="card__meta">' + plural(b.pins, 'pin') + '</div></div>', 'card');
    }).join('') + '</div>' + '<p>' + ext((st.cfg && st.cfg.profile_url) || d.profile_url, 'All boards on Pinterest ↗', 'btn btn--accent') + '</p>';
  }

  /* ── books ─────────────────────────────────────────────────────────── */
  function bookCover(isbn) { return isbn ? 'https://covers.openlibrary.org/b/isbn/' + isbn + '-M.jpg?default=false' : null; }
  function bookUrl(b) { return b.isbn ? 'https://openlibrary.org/isbn/' + b.isbn : 'https://openlibrary.org/search?q=' + encodeURIComponent(b.title + ' ' + b.author); }
  function renderBooks(d, st) {
    st = st || {};
    var books = d.books.filter(function (b) { return matches(st.q, [b.title, b.author]); });
    if (!books.length) return '<p class="empty">No book matches.</p>';
    return '<div class="grid">' + books.map(function (b) {
      return ext(bookUrl(b), '<div class="card__img card__img--poster" style="background:' + esc(b.spine) + '"><div class="fallback">' + esc(b.title) +
        '</div>' + (st.cfg && st.cfg.covers === false ? '' : '<img class="cover" loading="lazy" alt="" src="' + esc(bookCover(b.isbn)) + '" style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover">') + '</div>' +
        '<div class="card__body"><div class="card__title">' + esc(b.title) + '</div><div class="card__meta">' + esc(b.author) + '</div></div>', 'card');
    }).join('') + '</div>';
  }

  /* ── feed ──────────────────────────────────────────────────────────── */
  function md(src) {
    var out = [], code = false, buf = [];
    String(src || '').split('\n').forEach(function (line) {
      if (/^```/.test(line)) { if (code) { out.push('<pre>' + esc(buf.join('\n')) + '</pre>'); buf = []; } code = !code; return; }
      if (code) { buf.push(line); return; }
      var h = /^(#{1,4})\s+(.*)$/.exec(line);
      if (h) out.push('<h3>' + esc(h[2]) + '</h3>');
      else if (/^\s*([-*]|\d+\.)\s+/.test(line)) out.push('<p>• ' + esc(line.replace(/^\s*([-*]|\d+\.)\s+/, '')).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>') + '</p>');
      else if (/^>\s?/.test(line)) out.push('<p><i>' + esc(line.replace(/^>\s?/, '')) + '</i></p>');
      else if (line.trim()) out.push('<p>' + esc(line).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>') + '</p>');
    });
    return out.join('');
  }
  var KIND = { markdown: 'Article', article: 'Link', tweet: 'Post', rss: 'RSS' };
  function renderFeed(d, st) {
    st = st || {};
    var items = d.items.filter(function (it) { return (!st.section || KIND[it.type] === st.section) && matches(st.q, [it.title, it.text, it.source, (it.tags || []).join(' ')]); });
    if (!items.length) return '<p class="empty">Nothing in the feed matches.</p>';
    return items.map(function (it) {
      var meta = [it.source || it.author, it.date ? it.date.slice(0, 10) : '', it.read_min ? it.read_min + ' min read' : ''].filter(Boolean).join(' · ');
      return '<article class="post"><span class="post__kind">' + esc(KIND[it.type] || it.type) + '</span>' +
        (it.title ? '<h2>' + esc(it.title) + '</h2>' : '') + '<p>' + esc(it.text) + '</p><div class="post__meta">' + esc(meta) + '</div>' +
        ((it.tags && it.tags.length) ? '<div class="post__tags">' + it.tags.map(function (t) { return '<span>' + esc(t) + '</span>'; }).join('') + '</div>' : '') +
        (it.body ? '<button class="btn" data-toggle="' + esc(it.id) + '">' + (st.cfg && st.cfg.expand ? 'Close' : 'Read') + '</button><div class="post__body" id="body-' + esc(it.id) + '"' + (st.cfg && st.cfg.expand ? '' : ' hidden') + '>' + md(it.body) + '</div>' : '') +
        (it.url ? ext(it.url, 'Open ↗', 'btn btn--accent') : '') + '</article>';
    }).join('');
  }

  /* ── movies ────────────────────────────────────────────────────────── */
  function movieCard(m) {
    return '<div class="card" data-movie="' + esc(m.id) + '">' + img('card__img--poster', m.poster, m.title, hue(m.title)) +
      (m.rating && m.rating !== 'N/A' ? '<span class="badge">★ ' + esc(m.rating) + '</span>' : '') +
      '</div><div class="card__body"><div class="card__title">' + esc(m.title) + '</div><div class="card__meta">' + esc(m.year) + '</div></div></div>';
  }
  function renderMovies(d, st) {
    st = st || {};
    if (st.q) {
      var hits = Object.keys(d.movies).map(function (k) { return d.movies[k]; })
        .filter(function (m) { return matches(st.q, [m.title, m.year, m.genre, m.director]); });
      return hits.length ? '<h2 class="section-title">' + plural(hits.length, 'title') + '</h2><div class="grid">' + hits.map(movieCard).join('') + '</div>' : '<p class="empty">No title matches.</p>';
    }
    return d.shelves.filter(function (s) { return !st.section || s.id === st.section; }).map(function (s) {
      var cls = st.section ? 'grid' : 'shelf';
      return '<h2 class="section-title">' + esc(s.name) + '</h2><div class="' + cls + '">' + s.ids.map(function (id) { return movieCard(d.movies[id]); }).join('') + '</div>';
    }).join('');
  }
  function renderMovieDetail(d, m) {
    return '<div class="sheet__grab"></div><div class="detail">' + (m.poster ? '<img alt="" src="' + esc(m.poster) + '">' : '') +
      '<div><h2>' + esc(m.title) + '</h2><div class="post__meta">' + esc([m.year, m.runtime, m.type === 'series' ? 'Series' : '', m.rating && m.rating !== 'N/A' ? '★ ' + m.rating : ''].filter(Boolean).join(' · ')) +
      '</div><div class="post__meta">' + esc(m.genre) + '</div>' + (m.director && m.director !== 'N/A' ? '<div class="post__meta">' + esc(m.director) + '</div>' : '') + '</div></div>' +
      '<p>' + esc(m.plot) + '</p>' + '<button class="btn btn--accent" data-play="' + esc(m.id) + '">▶ Watch · sources</button> ' + ext((d.imdb || 'https://www.imdb.com/title/') + m.id + '/', 'IMDb ↗', 'btn') +
      ext('https://www.youtube.com/results?search_query=' + encodeURIComponent(m.title + ' ' + m.year + ' trailer'), 'Trailer ↗', 'btn');
  }

  /* ── documentaries ─────────────────────────────────────────────────── */
  function renderDocumentaries(d, st) {
    st = st || {};
    var vids = d.videos.filter(function (v) { return matches(st.q, [v.title]); });
    var films = d.films.filter(function (m) { return matches(st.q, [m.title, m.year, m.director]); });
    var html = '';
    if (vids.length && st.section !== 'Films') html += '<h2 class="section-title">On YouTube · ' + plural(vids.length, 'video') + '</h2><div class="grid">' + vids.map(videoCard).join('') + '</div>';
    if (films.length && st.section !== 'YouTube') html += '<h2 class="section-title">Films and series · ' + films.length + '</h2><div class="grid">' + films.map(movieCard).join('') + '</div>';
    return html || '<p class="empty">No documentary matches.</p>';
  }

  /* ── page table: what each page renders and which chips it offers ──── */
  var RENDER = {
    music: { title: 'Music', sub: function (d) { return plural(d.profile.tracks || 0, 'track') + ' on TIDAL'; }, chips: function (d) { return d.sections.map(function (s) { return s.name; }); }, render: renderMusic, hint: 'Search tracks or artists' },
    videos: { title: 'Videos', sub: function (d) { return plural(d.playlists.length, 'playlist') + ' on YouTube'; }, chips: function (d) { return d.playlists.map(function (p) { return p.name; }); }, render: renderVideos, hint: 'Search videos' },
    gallery: { title: 'Gallery', sub: function (d) { return plural(d.boards.length, 'board') + ' on Pinterest'; }, chips: function () { return []; }, render: renderGallery, hint: 'Search boards' },
    books: { title: 'Books', sub: function (d) { return plural(d.books.length, 'book') + ' on the shelf'; }, chips: function () { return []; }, render: renderBooks, hint: 'Search title or author' },
    feed: { title: 'Feed', sub: function (d) { return plural(d.items.length, 'item'); }, chips: function (d) { var k = []; d.items.forEach(function (i) { var n = KIND[i.type] || i.type; if (k.indexOf(n) < 0) k.push(n); }); return k; }, render: renderFeed, hint: 'Search the feed' },
    movies: { title: 'Movies', sub: function (d) { return plural(Object.keys(d.movies).length, 'title'); }, chips: function (d) { return d.shelves.map(function (s) { return { id: s.id, name: s.name }; }); }, render: renderMovies, hint: 'Search title, genre, director' },
    documentaries: { title: 'Documentaries', sub: function (d) { var n = d.videos.length + d.films.length; return n + (n === 1 ? ' documentary' : ' documentaries'); }, chips: function () { return ['YouTube', 'Films']; }, render: renderDocumentaries, hint: 'Search documentaries' }
  };

  function tabsHtml(current, prefix) {
    return PAGES.concat(EXTRA).map(function (p) {
      return '<a href="' + prefix + p.id + '/index.html"' + (p.id === current ? ' aria-current="page"' : '') + '><span>' + p.icon + '</span>' + esc(p.label) + '</a>';
    }).join('');
  }

  /* ── BOOT: the browser half ────────────────────────────────────────── */
  function openExternal(url) {
    var t = root.__TAURI__;
    if (t && t.opener && t.opener.openUrl) { t.opener.openUrl(url).catch(function () { root.open(url, '_blank'); }); return; }
    root.open(url, '_blank');
  }

  function boot() {
    var doc = root.document, page = doc.body.getAttribute('data-page');
    doc.addEventListener('click', function (e) {
      var a = e.target.closest && e.target.closest('a[data-ext]');
      if (a) { e.preventDefault(); openExternal(a.getAttribute('href')); }
    });
    var tabs = doc.querySelector('.tabs');
    if (tabs) tabs.innerHTML = tabsHtml(page, page === 'home' ? '' : '../');
    if (page === 'home') return;
    if (!RENDER[page]) return;  // configs / profile: assets/configs.js draws them
    var spec = RENDER[page], st = { q: '', section: '', cfg: root.CmcConfig ? root.CmcConfig.loadTab(page) : {} }, data = null;
    var main = doc.querySelector('main'), chips = doc.querySelector('.chips'), search = doc.querySelector('.search');
    var sub = doc.querySelector('.top h1 small'), sheet = doc.querySelector('.sheet');
    search.placeholder = spec.hint;
    function draw() { main.innerHTML = spec.render(data, st); root.scrollTo(0, 0); }
    fetch('../data/' + page + '.json').then(function (r) { return r.json(); }).then(function (d) {
      data = d;
      sub.textContent = spec.sub(d);
      var cs = spec.chips(d);
      chips.hidden = !cs.length;
      chips.innerHTML = '<button class="chip" aria-pressed="true" data-chip="">All</button>' + cs.map(function (c) {
        var id = typeof c === 'string' ? c : c.id, name = typeof c === 'string' ? c : c.name;
        return '<button class="chip" aria-pressed="false" data-chip="' + esc(id) + '">' + esc(name) + '</button>';
      }).join('');
      var start = st.cfg && st.cfg.start ? [].filter.call(chips.children, function (c) { return c.textContent.toLowerCase() === st.cfg.start.toLowerCase() || c.getAttribute('data-chip') === st.cfg.start; })[0] : null;
      if (start) { [].forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c === start)); }); st.section = start.getAttribute('data-chip'); }
      draw();
    }).catch(function (e) { main.innerHTML = '<p class="empty">Could not load ' + esc(page) + ': ' + esc(e) + '</p>'; });
    var timer;
    search.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(function () { st.q = search.value.trim(); draw(); }, 150); });
    chips.addEventListener('click', function (e) {
      var b = e.target.closest('.chip'); if (!b) return;
      [].forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c === b)); });
      st.section = b.getAttribute('data-chip'); st.playlist = ''; draw();
    });
    main.addEventListener('click', function (e) {
      var pl = e.target.closest('[data-playlist]');
      if (pl) { st.playlist = pl.getAttribute('data-playlist'); search.value = ''; st.q = ''; draw(); return; }
      if (e.target.closest('[data-back]')) { st.playlist = ''; draw(); return; }
      var tg = e.target.closest('[data-toggle]');
      if (tg) { var body = doc.getElementById('body-' + tg.getAttribute('data-toggle')); body.hidden = !body.hidden; tg.textContent = body.hidden ? 'Read' : 'Close'; return; }
      var mv = e.target.closest('[data-movie]');
      if (mv && sheet) {
        var m = (data.movies && data.movies[mv.getAttribute('data-movie')]) || (data.films || []).filter(function (f) { return f.id === mv.getAttribute('data-movie'); })[0];
        if (m) { sheet.__movie = m; sheet.querySelector('.sheet__panel').innerHTML = renderMovieDetail(data, m); sheet.hidden = false; }
      }
    });
    main.addEventListener('error', function (e) { if (e.target.classList && e.target.classList.contains('cover')) e.target.remove(); }, true);
    if (sheet) sheet.addEventListener('click', function (e) {
      if (e.target === sheet) { sheet.hidden = true; return; }
      // The legal multi-source player (assets/player.js + the shared sources-core.js).
      if (e.target.closest('[data-play]') && root.MoviePlayer && sheet.__movie) { sheet.hidden = true; root.MoviePlayer.open(sheet.__movie); }
    });
  }

  var api = { PAGES: PAGES, RENDER: RENDER, esc: esc, md: md, tabsHtml: tabsHtml, renderMovieDetail: renderMovieDetail };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else if (root.document) root.document.addEventListener('DOMContentLoaded', boot);
})(typeof window !== 'undefined' ? window : this);
