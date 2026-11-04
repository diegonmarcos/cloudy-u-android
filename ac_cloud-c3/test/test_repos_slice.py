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
            self.assertEqual(set(r), {"name", "project", "group", "category", "code", "visibility", "default_branch", "pages_url", "github_url"})
            self.assertIn(r["project"], IDS)
            self.assertIn(r["visibility"], ("pub", "pvt"))
            self.assertTrue(r["github_url"].endswith("/" + r["name"]))
            self.assertFalse(r["name"].startswith("z-archived_"), "archived repos are skipped")

    def test_project_is_the_registry_code_prefix_not_the_name(self):
        for r in SL["repos"]:
            self.assertEqual(g.project_of(r["code"], CP["projects"], CP["default"]), r["project"])

    def test_groups_are_registry_families_except_a_project_with_repo_group(self):
        for r in SL["repos"]:
            decl = next(p for p in CP["projects"] if p["id"] == r["project"])
            if decl.get("repo_group"):
                self.assertEqual(r["group"], decl["repo_group"])
            else:
                self.assertIn(r["group"], SL["projects"][r["project"]]["families"], r["name"])

    def test_container_family_values_are_registry_families(self):
        fams = {f for v in SL["projects"].values() for f in v["families"]}
        for n, f in CP.get("container_family", {}).items():
            self.assertIn(f, fams, n)

    def test_matches_the_registry_when_given(self):
        reg, pages = os.environ.get("C3_REGISTRY"), os.environ.get("C3_PAGES")
        if not (reg and pages):
            print("  SKIP  drift vs registry (set C3_REGISTRY and C3_PAGES)")
            return
        want = g.derive(json.load(open(reg)), json.load(open(pages)), CP)
        self.assertEqual(SL, want, "repos_declared.json drifted from the registry: run data/gen_repos_slice.py")


if __name__ == "__main__":
    unittest.main()
