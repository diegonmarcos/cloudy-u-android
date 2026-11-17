# Cloud Wiki

A small encyclopedia of short, neutral, educational Markdown pages (#1778). Tile `Wiki` leads Cloud > Apps > Projects (`Wiki | SuperApp, Omniverse | FinTerm, BioMed, CAD | Circusverse`).

* Package `com.diegonmarcos.cloudwiki`, image `cloud-wiki`, asset `Cloud-Wiki.apk`, ship workflow `ship-cloud-wiki.yml`.
* **No bottom nav** (declared in `1_cicd/src/data/nav-shape.json::no_bottom`). The shell is the fleet's: `libs:ui-nav-bars` (first row with Profile and Configs, the collapsed left side menu in `vertical_side_menu` mode, the top subpage tabs), `libs:theme-background`, `libs:ui-kit`. The side menu is `build.json::ui`: four groups (Sciences, Humanities, Arts, Explore) over thirteen topics and the Graph.
* **Topics**: Math, Computer Science, Physics, Astronomy, Engineering (one subpage per major field), Medicine, Anthropology, Human Belief Systems, Chronos, Art, Theater, Music, Movies. Each topic has an Overview tab and one tab per subpage.
* **Pages** are Markdown (headings, paragraphs, bullets, `**bold**`, `*italic*`, `[[topic.slug]]` links) with tag chips. Tapping a chip filters every page of the wiki by that tag (all selected tags must match).
* **Graph** (`libs:graph-view`, shared by reference so Cloud Notes can draw the same graph): pages are nodes coloured by topic, solid edges for `[[links]]`, faint edges for shared tags, force-directed layout, pan, pinch zoom, tap a node to open the page.
* **Content is a runtime dataset**, not an APK asset: `data/fleet/app/com.diegonmarcos.cloudwiki/wiki.json`, listed in `data/fleet/manifest.json::apps`, served by Cloud Store and read through `libs:fleet-data-client` (`WikiStore`). Edit the JSON to change the pages; topics added to it must also be declared in `build.json::ui` (the side menu).
* Deep links (string extras on the launch intent): `page` (a topic id or `graph`), `tab` (a page id), `tags` (comma list), e.g. `--es page eng --es tab eng.civil`.
* No network permission of its own.

## Tests

* `test/test-wiki-app.sh` (static, each rule with a planted mutation): navigation, dataset, shell, runtime read, no network.
* `app/src/test`: `WikiTest` (topic tree, tags and the tag filter, Markdown, graph edges from links and shared tags), `GraphLayoutTest` (the force layout and tap hit-testing).
* `test/emulator-wiki-screens.sh` + `.github/workflows/wiki-screens-emulator.yml` (dispatch only): screenshots of the Projects row, a topic page with tags, a tag filter and the graph.
