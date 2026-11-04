# edge-md-scraper

Cloudflare Worker (Browser Rendering binding `BROWSER`, `nodejs_compat`): `GET /?url=<page>` renders the page in headless Chromium, strips boilerplate with Readability, converts to Markdown with Turndown and answers `text/markdown` with a frontmatter (title, source, excerpt, each a YAML double-quoted scalar). Every request needs `Authorization: Bearer <SCRAPER_TOKEN>`.

Used by the "Cloudflare Browser Run" scraper option of Cloud Browser, Cloud News, Cloud Media and Cloud Search, in its default "Worker URL" mode. The secret name is `SCRAPER_TOKEN`; its value is never in this repo.

Deploy is owner-only (never run by an agent), from this directory:

    npm install
    npx wrangler secret put SCRAPER_TOKEN
    npx wrangler deploy

Limits: 128 MB isolate memory (a huge DOM can exceed it with JSDOM), 15 s navigation timeout, no anti-bot evasion (WAF-protected sites are refused).
