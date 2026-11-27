#!/usr/bin/env python3
"""data/repos_declared.json (every owner git repo as a Containers entity) is GENERATED from the fleet registry: shape, mapping
and drift. Drift needs the registry: set C3_REGISTRY (projects-repos.json) and C3_PAGES (front-html pages.json); without them
the drift test is skipped LOUDLY (never a silent pass)."""
import json, os, sys, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data")
sys.path.insert(0, DATA)
import gen_repos_slice as g

CP = json.load(open(os.path.join(DATA, "container_projects.json")))
SL = json.load(open(os.path.join(DATA, "repos_declared.json")))
IDS = [p["id"] for p in CP["projects"]]


class Slice(unittest.TestCase):
    def test_every_repo_has_its_facts_and_a_declared_project(self):
        self.assertGreater(len(SL["repos"]), 20)
        for r in SL["repos"]:
            self.assertEqual(set(r), {"name", "project", "group", "category", "code", "visibility", "default_branch", "pages_url", "github_url",
                                      "lfs", "role", "github", "attached"})
            self.assertIn(r["project"], IDS)
            self.assertIn(r["visibility"], ("pub", "pvt"))
            self.assertIn(r["role"], ("repo", "hub"))
            self.assertIsInstance(r["lfs"], bool)
            # #1953 finding 5: the URL is built from the registry `github` name (front-diegonmarcos is diegonmarcos.github.io)
            self.assertTrue(r["github_url"].endswith("/" + r["github"]))
            self.assertEqual(r["attached"][0]["project"], r["project"], "attached starts with the primary project")
            for a in r["attached"]:
                self.assertIn(a["project"], IDS)
                self.assertIn(a["via"], ("code", "hub", "declared"))
            self.assertFalse(r["name"].startswith("z-archived_"), "archived repos are skipped")

    def test_project_is_the_registry_code_prefix_not_the_name(self):
        for r in SL["repos"]:
            if r["role"] != "repo": continue
            self.assertEqual(g.project_of(r["code"], CP["projects"], CP["default"]), r["project"])

    def test_groups_are_registry_families_except_a_project_with_repo_group(self):
        for r in SL["repos"]:
            decl = next(p for p in CP["projects"] if p["id"] == r["project"])
            if r["role"] == "hub":
                self.assertEqual(r["group"], "Hub")
            elif decl.get("repo_group"):
                self.assertEqual(r["group"], decl["repo_group"])
            else:
                self.assertIn(r["group"], SL["projects"][r["project"]]["families"], r["name"])

    def test_container_family_values_are_registry_families(self):
        fams = {f for v in SL["projects"].values() for f in v["families"]}
        for n, f in CP.get("container_family", {}).items():
            self.assertIn(f, fams, n)

    def test_every_registry_hub_is_a_repo_and_the_root_goes_to_lib(self):
        hubs = {r["name"]: r for r in SL["repos"] if r["role"] == "hub"}
        for n in ("cloudy-___", "cloud-me-___", "cloud-me-pub-___", "cloud-me-pub-profiles-___", "cloud-me-pvt-___", "CG-___", "Capital-___",
                  "HardTech-___", "lecole-42-___", "Projects-___", "DuckPeng-___", "_git-repos-master"):
            self.assertIn(n, hubs)
        self.assertNotIn("z-archived-___", hubs, "the Archived area stays skipped")
        self.assertEqual(hubs["_git-repos-master"]["project"], "pj-wiki-projects")

    def test_a_hub_is_attached_to_every_project_under_its_code(self):
        for r in SL["repos"]:
            if r["role"] != "hub" or r["code"] == "0": continue
            x = r["code"][:-2]
            under = {p["id"] for p in CP["projects"] if p.get("code") and (p["code"] == x or p["code"].startswith(x + "."))}
            self.assertTrue(under <= {a["project"] for a in r["attached"]}, r["name"])

    def test_lfs_is_the_registry_flag(self):
        lfs = {r["name"] for r in SL["repos"] if r["lfs"]}
        self.assertIn("cloudy_db_LFS", lfs)

    def test_every_declared_project_repo_is_attached_with_its_path(self):
        by = {r["name"]: r for r in SL["repos"]}
        for pid, items in CP["project_repos"].items():
            self.assertIn(pid, IDS)
            for it in items:
                self.assertIn(it["repo"], by, "project_repos names no registry repo: %s" % it["repo"])
                got = [a for a in by[it["repo"]]["attached"] if a["project"] == pid]
                self.assertEqual(len(got), 1, (pid, it["repo"]))
                self.assertEqual(got[0]["path"], it.get("path", ""))

    def test_no_declared_project_is_left_without_a_repo(self):
        have = {a["project"] for r in SL["repos"] for a in r["attached"]}
        self.assertEqual([p for p in IDS if p not in have], [])

    def test_project_repos_agree_with_cloud_me(self):
        f = os.path.join(HERE, "..", "..", "ac_cloud-me", "test", "fixtures", "proj-repo-urls-1932.json")
        if not os.path.exists(f):
            print("  SKIP  drift vs Cloud Me proj-repo-urls-1932.json (not reachable)")
            return
        me = json.load(open(f))
        for pid, items in CP["project_repos"].items():
            it = items[0]
            want = "https://github.com/diegonmarcos/" + it["repo"] + ("/tree/main/" + it["path"] if it.get("path") and "lib-chronos" == it["repo"] else "")
            self.assertEqual(me.get(pid), want, pid)

    def test_matches_the_registry_when_given(self):
        reg, pages = os.environ.get("C3_REGISTRY"), os.environ.get("C3_PAGES")
        if not (reg and pages):
            print("  SKIP  drift vs registry (set C3_REGISTRY and C3_PAGES)")
            return
        want = g.derive(json.load(open(reg)), json.load(open(pages)), CP)
        self.assertEqual(SL, want, "repos_declared.json drifted from the registry: run data/gen_repos_slice.py")


if __name__ == "__main__":
    unittest.main()
