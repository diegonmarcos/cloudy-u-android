# Capital-Stack-FinTerminal

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.capitalfinterm`, folder `ad_Capital-Stack-FinTerminal` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

* The pages are `build.json::ui` (`bottom_nav`, `sections`, `stacks`) and are drawn by declared kinds (`Pages.kt`: `entry`,
  `section_title`, `holdings`, `about`). The shell is the fleet's: `libs:ui-nav-bars` (first row, bottom nav), `libs:bottomnav`
  (NavDecl), `libs:ui-kit` (tokens and kit), `libs:ui-icons` (the nav icons), `libs:theme-background`.
* An entry's target is read from a declared source (a linktree label, a registry code or a repo of `ui.app_urls`) by `app/build.gradle`
  at build time; no address is typed here. Every entry is a dashboard (`dashboard` kind); no figure is declared, a dashboard shows what its source returns.
* Tester: `test/test-lnktree-app.sh` (the shared template `1_cicd/src/templates/test-lnktree-app.sh`), JVM: `PagesTest`, `HoldingsTest`.
* Sources: `data/sources.json` (the catalog, Cloud-Fincept / fin_api first, upstream FinceptTerminal AGPL-3.0 attributed), `data/dashboards.json` (one dashboard per entry), `data/control.json` (the mesh-only container control); Config > Sources keeps your own API keys encrypted (SafeEncryptedPrefs). Tester: `test/test-finterm-sources.sh`.
* Ship: `1_cicd/src/cicd/ship-capital-stack-finterminal.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation).
* Terminal-style UI (#1722): a black canvas, amber function codes, IBM Plex Sans Condensed with tabular figures (`res/font`, SIL OFL 1.1,
  `licenses/IBM-Plex-Sans-Condensed-OFL.txt`); the tokens are `res/values/colors.xml` + `ui/fin/FinTheme.kt`, the chrome and panels `ui/fin/FinTerminal.kt`
  (command line with GO, red function-menu buttons, market monitor, status line, panel header strip, tabs, subhead). Every page has a function mnemonic
  (`build.json::ui.stacks[].fn`): type it in the command line, or open the list with `96) Functions`. The chrome's words and the monitor's tiles are
  `data/terminal.json`.
* Sections (#1722): CB = central banks plus every rates, macro and credit page (Yield Curves, Yields Matrix, Forward Rates, World Interest Rate
  Probabilities, World Bonds, Bonds Market, Macro, Credit); FI = the bond groups only (All | Gov Bonds DM | Gov Bonds EM | AAA | AA-A | BBB | Junk);
  EQ = Performance (ten factor portfolios, then World Indices Equity) and Data (every other page). `build.json::ui.moved` is the old -> new map;
  `MovedPagesTest` holds it to the frozen baseline `app/src/test/resources/nav-parity-baseline-1722.json` (nothing removed).
* Performance portfolios (#1722): declared in `data/portfolios.json` (which fund or weighting scheme, the benchmarks, the words); constituents and weights
  are the RUNTIME dataset `data/fleet/app/com.diegonmarcos.capitalfinterm/perf-portfolios.json` (repo root), written by `scripts/gen-perf-portfolios.py`
  from the publishers' own files (`--check` validates it offline) and read through libs:fleet-data-client; every figure comes from the quote source of
  `data/quotes.json`. Each page has a Table mode (sortable constituents, contribution bars, totals) and a Graphic mode (heat map, attribution waterfall,
  bubble chart): `PerfPortfolios.kt` (pure rules), `ui/PerfPage.kt`. Gov Bonds DM is GDP-weighted by default with USD index and liquidity weights as
  options; Gov Bonds EM uses the country weights of EMB (default) or EBND; the scheme in use and its source are printed on the page.
* Emulator walk of the terminal UI: `test/emulator-terminal.sh` (`.github/workflows/finterm-terminal-emulator.yml`, dispatch with a Ship run id).
* Follow-ups (#1727): Gov Bonds EM shows each country's government bond return in dollars with its two legs (Bond %, FX %): a listed fund of the
  country's bonds where the quote source has one (`data/portfolios.json::countries.<cc>.bond_proxy`), else the yield and duration approximation of the
  sovereign-returns source (`sovereign`: the container's `/v1/bonds/sovereign-returns` on the mesh, declared in `data/universe.json::sovereign_returns`,
  else the same arithmetic on the OECD yields from DBnomics; `SovereignReturns.kt`); Sweden has its listed fund. The Graphic mode has a flow panel
  (portfolio to buckets to largest constituents, `PerfPortfolios.flow`). The dataset is refreshed by rerunning `scripts/gen-perf-portfolios.py`
  (scheduled: `.github/workflows/finterm-perf-portfolios-refresh.yml`, weekly, commits the dataset when it changed). `groups[].intended` names the iShares fund a factor portfolio is meant to follow; its holdings CSV (`.../ishares.com/.../<id>.ajax?fileType=csv`) answers an HTML site-entry page to an automated reader (checked 2026-11-13) and
  VanEck's holdings download redirects in a loop, so neither can be read; the State Street fund of the same index or factor is read. The container's
  `/v1/bonds/sovereign-returns` (`store.rs`, `sovereign_windows` test) serves the yield and duration bond returns with the local, currency and USD legs.

* MARKZ (#1832, formerly MY-PM): the last tab is the owner's page "Portfolio Returns After Inflation & Taxes (€ MoM)" drawn natively from
  `data/markz.json` (every tab, input, output, table, chart and word of the page) by `ui/MarkzPage.kt`, with the page's arithmetic in `Markz.kt`:
  Overview (the page's Home: AuM in USD / EUR / BRL, the annual return waterfall, the FX crosses), Markovitz (the five steps, the efficient-frontier
  diagram, the frontier portfolios that set the matrix's target), Sensy Matrix (the Homepage, as the page opens on it: the 7 x 7 matrix of return
  targets by EUR/BRL shocks with its heatmap, the KPIs, the inspector, sheet-step or exact rounding, Copy CSV, Reset and the 14 parameters plus the
  3 steps, each field solving back like the page's) and the five Markovitz substeps the page only described, implemented on the daily closes of the
  quote source (`MarkzStats.kt`: historical SD, EWMA, GARCH(1,1) and implied volatility, Beta, CAPM, the correlation and covariance matrices, the
  long-only minimum-variance / maximum-Sharpe optimiser and its frontier, each return sendable to the Sensy Matrix). Personal portfolio (the
  holdings list, `MYPM`) is kept, and the former MY-PM Homepage is MARKZ > Index. Rule 16: `ui.renamed` maps the old ids, `ui.redirects` sends the
  old deep links and the command word MY-PM to MARKZ; `Rename1832ParityTest` holds every MY-PM element to `nav-parity-baseline-1832.json`,
  `MarkzParityTest` every element of the owner's page to `markz-owner-page-1832.json`; `MarkzTest` and `MarkzStatsTest` cover the math. `GarchFit.kt` fits GARCH(1,1) by maximum likelihood to each asset's own history (bad prints dropped, persistence capped at 0.995, long-run vol gated against the realised vol; cached by data hash; fewer than 500 returns: the declared defaults, marked "defaults (short history)"); `GarchFitTest` and `MarkzGarchParityTest` cover it.

* EM yield curves (#1832): CB > Yield Curves adds the curves of the major emerging markets, each read the way the US Treasury and ECB curves are
  (a declared `points` panel in `data/dashboards.json`, its publisher in `data/sources.json`, a parser in `Feeds.kt`): India from CCIL's tenor-wise
  indicative G-Sec yields (T-bills 91D-364D, G-Sec buckets 1Y-2Y to 28Y-30Y; state loans left out), Brazil from ANBIMA's ETTJ (the prefixed zero curve
  at the Circular 3.361 vertices, 1M-10Y), Indonesia from PHEI's IGSYC (the Indonesia Bond Pricing Agency, 0.1Y-30Y with yesterday's curve), South Africa
  from the SARB Web API (T-bill tenders, R2030, R209 and the daily average bond-yield bands) and Mexico from Banxico's SIE chart service (the auction
  yields of Cetes 28D-2Y and Bonos M 3Y-30Y; the SIE REST API needs a token and is not used). None of them needs a key; the container's sovereign-curves
  route is asked first and the OECD monthly rates on DBnomics (call money, 3M, 10Y) are the fallback. Tests: `EmCurvesTest` on the publishers' own
  answers (`app/src/test/resources/cb/em`). Of the "Other sovereign curves" the placeholder panel names, Japan is read the same way from the
  Ministry of Finance JGB interest rates CSV (1Y-40Y); the placeholder stays for a premium feed of your own, and the UK and China curves stay on the
  container's sovereign-curves route (the CB Homepage), the BoE's being a zipped workbook and ChinaBond's a POST session.

* Periods, boxes and overview (#1786): every EQ and FI page shows 1D, 1W, 1M, YTD and 12M, then the annualised averages over 1, 12, 24 and 36
  months as CAGR, (end / start)^(1 / years) - 1 with years = months / 12 (`Cagr.kt`, `data/portfolios.json::periods`); a window the history does not
  reach is n/a, never a shorter-history value. The quote source asks five years of daily closes (`data/quotes.json` `range=5y`;
  `scripts/gen-quotes-snapshot.py` holds the same math). The EQ and FI Homepages carry a period summary (every portfolio over the whole period set) and
  their boxes show the 1M and the 12M change in place of YTD (`card_periods`). `data/home-boxes.json` declares the Homepage boxes that list instruments
  (`HomeBoxes.kt`, `ui/HomeBoxCard.kt`): EQ World Indices Equity (the major economies first, then all 32 indices) and every HG page (Last and 12M %),
  each the top 5 with Show all; the instruments are read from where the page declares them. The CB Homepage opens with an overview of the major
  economies (US, EA, UK, JP, CN first, Show all for every country): yield curves 2Y / 5Y / 10Y / 30Y, CPI inflation, real GDP growth and the policy rate,
  from `data/cb-snapshot.json` (the euro area 2Y / 5Y / 30Y come from the ECB yield curve, source `ecb_yc`, refreshed with
  `scripts/gen-cb-snapshot.py --only yield_2y,yield_5y,yield_10y,yield_30y`). Commodities (CMDT), Commodity Prices (CPX) and Exchange Rates (FXR) moved
  from EQ > Data to HG > Markets with all their data (`ui.moved`, ticket 1786). PM opens on Performance (a `home_page` entry); the former card grid is
  PM > Index (`home_grid`). The US Cyclicals group adds the S&P 500 and the Nasdaq. Rule 16: `Reorg1786ParityTest` holds every page, entry field,
  redirect, dashboard panel, column and instrument to the frozen baseline `app/src/test/resources/nav-parity-baseline-1786.json`; `CagrTest`,
  `HomeBoxesTest` cover the math and the boxes.
