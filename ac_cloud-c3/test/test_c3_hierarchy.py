"""#1495 hierarchy guard (app side): the committed data/c3_hierarchy.json IS the app tree of build.json ui.sections plus the declared
route/tool mapping; no route or tool pattern has two owners; every new category has its fragment arm and catalog map."""
import json, os, re, subprocess, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "..")
D = os.path.join(APP, "data")
def J(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def leaves(nodes, pre=""):
    for n in nodes:
        p = pre + n["id"]
        if n.get("children"):
            yield from leaves(n["children"], p + "/")
        else:
            yield p, n


def api_score(pat):
    segs = pat.strip("/").split("/")
    return (sum(1 for s in segs if not s.startswith(":") and s != "*"), not pat.endswith("*"))


def api_match(pat, path):
    a, b = pat.strip("/").split("/"), path.strip("/").split("/")
    if a[-1] == "*":
        return len(b) >= len(a) and all(x.startswith(":") or x == y for x, y in zip(a[:-1], b))
    return len(a) == len(b) and all(x.startswith(":") or x == y for x, y in zip(a, b))


class Hierarchy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.h = J(os.path.join(D, "c3_hierarchy.json"))
        cls.ui = J(os.path.join(APP, "build.json"))["ui"]
        cls.leaves = dict(leaves([x for s in cls.h["sections"] for x in [s]]))

    def test_generated_files_are_fresh(self):
        r = subprocess.run([sys.executable, "-I", os.path.join(D, "gen_c3_hierarchy.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_the_tree_is_the_app_tree(self):
        self.assertEqual([s["id"] for s in self.h["sections"]], [s["id"] for s in self.ui["sections"]])
        for s in self.ui["sections"]:
            ids = set()
            for p in s.get("pages", []):
                ids |= {q["id"][len(p["id"]) + 1:] if q["id"].startswith(p["id"] + "_") else q["id"] for q in p["pages"]} if p.get("pages") else {p["id"]}
            hs = next(x for x in self.h["sections"] if x["id"] == s["id"])
            got = {p.split("/")[-1] for p, _ in leaves(hs.get("children", []))}
            self.assertEqual(ids, got, s["id"])
            for p in s.get("pages", []):
                label = next((c["label"] for c in (hs.get("children") or []) if c["id"] == p["id"]), None)
                if hs.get("children") and label is not None:
                    self.assertEqual(label, p["label"])

    def test_ops_and_infra_rows_of_the_owner(self):
        by = {s["id"]: s for s in self.h["sections"]}
        self.assertEqual([g["id"] for g in by["infra"]["children"]], ["bare", "apis", "personal", "domains_row"])
        self.assertEqual([g["id"] for g in by["ops"]["children"]], ["net", "mail", "orch", "ops_health", "obs"])
        self.assertEqual([g["id"] for g in by["containers"]["children"]], ["all", "cloudy", "cloud_me", "cg", "capital", "hardtech", "others"])
        self.assertEqual([f["id"] for f in self.h["containers"]["filters"]], ["mcp", "api", "apps", "db", "repo"])

    def test_every_leaf_owns_or_inherits(self):
        for p, n in self.leaves.items():
            if "same_as" in n:
                self.assertIn(n["same_as"], self.leaves, p)
                self.assertNotIn("api", n)
            else:
                self.assertIn("api", n, p)
                self.assertIn("mcp", n, p)

    def test_no_pattern_has_two_owners(self):
        seen = {}
        for p, n in self.leaves.items():
            for pat in n.get("api", []):
                self.assertFalse(("api", pat) in seen, f"{pat} owned by {p} and {seen.get(('api', pat))}")
                seen[("api", pat)] = p
            for pat in n.get("mcp", []):
                self.assertFalse(("mcp", pat) in seen, f"{pat} owned by {p} and {seen.get(('mcp', pat))}")
                seen[("mcp", pat)] = p
        # two different API patterns that match the same concrete path with the same specificity are ambiguous
        pats = [(pat, p) for p, n in self.leaves.items() for pat in n.get("api", [])]
        for pat, p in pats:
            probe = "/" + "/".join("x" if s in (":", "*") or s.startswith(":") else s for s in pat.strip("/").split("/"))
            if pat.endswith("*"):
                probe += "/x"
            hits = [(api_score(q), q) for q, _ in pats if api_match(q, probe)]
            top = max(hits)[0]
            self.assertEqual(len([h for h in hits if h[0] == top]), 1, f"{pat}: ambiguous with {hits}")

    def test_every_new_category_has_a_fragment_arm_and_a_catalog_map(self):
        mp = J(os.path.join(D, "c3_hierarchy_map.json"))
        ops = J(os.path.join(D, "ops_maps.json"))["maps"]
        with open(os.path.join(APP, "app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt"), encoding="utf-8") as f:
            tabs = f.read()
        for path in mp["catalog_pages"]:
            leaf = path.split("/")[-1]
            self.assertIn(path, self.leaves)
            self.assertTrue(any(k.startswith("hier_") and k.endswith(leaf) for k in ops), path)
            self.assertRegex(tabs, r'"%s" -> (OpsMapFragment\.newInstance\("hier_|InfraSectionFragment\.newInstance\(InfraSections\.)' % re.escape(leaf), path)


if __name__ == "__main__":
    unittest.main()
