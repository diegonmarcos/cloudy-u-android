import puppeteer from "@cloudflare/puppeteer";
import { Readability } from "@mozilla/readability";
import TurndownService from "turndown";
import { JSDOM } from "jsdom";

export interface Env {
  BROWSER: puppeteer.BrowserWorker;
  /** Secret: the bearer token every request must carry (set with `wrangler secret put SCRAPER_TOKEN`). */
  SCRAPER_TOKEN: string;
}

/** Constant-time comparison of the presented bearer with the secret. */
function authorised(request: Request, secret: string | undefined): boolean {
  if (!secret) return false;
  const given = new TextEncoder().encode((request.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, ""));
  const want = new TextEncoder().encode(secret);
  let diff = given.length ^ want.length;
  for (let i = 0; i < want.length; i++) diff |= (given[i] ?? 0) ^ want[i];
  return diff === 0;
}

/** A YAML double-quoted scalar: JSON string escaping is valid YAML, so quotes, backslashes and newlines cannot break the frontmatter. */
function yamlString(value: string | null | undefined): string {
  return JSON.stringify((value ?? "").replace(/\r?\n/g, " ").trim());
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!authorised(request, env.SCRAPER_TOKEN)) {
      return new Response("Unauthorized", { status: 401, headers: { "WWW-Authenticate": "Bearer" } });
    }
    const { searchParams } = new URL(request.url);
    const targetUrl = searchParams.get("url");

    if (!targetUrl) {
      return new Response("Missing ?url= parameter", { status: 400 });
    }

    let browser;
    try {
      // 1. Launch Headless Browser via Cloudflare Binding
      browser = await puppeteer.launch(env.BROWSER);
      const page = await browser.newPage();

      // Optimize page load: Abort images, CSS, and media
      await page.setRequestInterception(true);
      page.on("request", (req) => {
        const type = req.resourceType();
        if (["image", "stylesheet", "font", "media"].includes(type)) {
          req.abort();
        } else {
          req.continue();
        }
      });

      // 2. Navigate and Render
      // networkidle2 ensures JS frameworks have finished hydration
      await page.goto(targetUrl, { waitUntil: "networkidle2", timeout: 15000 });

      // Extract full rendered HTML
      const html = await page.content();
      await browser.close();

      // 3. Parse and Strip Boilerplate (Nav, Footers, Ads)
      const doc = new JSDOM(html, { url: targetUrl });
      const reader = new Readability(doc.window.document);
      const article = reader.parse();

      if (!article || !article.content) {
        throw new Error("Failed to extract article content");
      }

      // 4. Convert Cleaned HTML to Markdown
      const turndownService = new TurndownService({
        headingStyle: "atx",
        codeBlockStyle: "fenced"
      });
      const markdown = turndownService.turndown(article.content);

      // Append Metadata Frontmatter (every value escaped as a YAML double-quoted string)
      const finalPayload = `---
title: ${yamlString(article.title)}
source: ${yamlString(targetUrl)}
excerpt: ${yamlString(article.excerpt)}
---

${markdown}`;

      // 5. Return LLM-ready Payload
      return new Response(finalPayload, {
        headers: {
          "Content-Type": "text/markdown; charset=utf-8",
          "Cache-Control": "private, max-age=3600"
        },
      });

    } catch (error: any) {
      if (browser) await browser.close();
      return new Response(`Extraction Error: ${error.message}`, { status: 500 });
    }
  },
};
