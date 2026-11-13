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

