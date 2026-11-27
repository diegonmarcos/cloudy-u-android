#!/usr/bin/env python3
"""#1971 the hosts of Containers > VM, DERIVED (never typed) from data/fleet_slice.json and data/containers_declared.json, the same rule as
ContainerHosts.kt: every fleet VM holding a declared container (host = its alias) + every mesh-client device (electronics with a wg_ip).
`python3 data/gen_vm_hosts.py` prints the build.json containers pages (vm-all, vm-<host>..., vm-unknown) the app must declare; the tests import derive()."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))


def _entries(o, out):
    if isinstance(o, dict):
        if "host" in o and "name" in o: out.append(o)
        for v in o.values(): _entries(v, out)
    elif isinstance(o, list):
        for v in o: _entries(v, out)
    return out


def derive(data_dir=HERE):
    """[(id, label)] in the DECLARED order (container_projects.json::vm_view.host_order); a derived host not declared there follows in fleet order."""
    fleet = json.load(open(os.path.join(data_dir, "fleet_slice.json"), encoding="utf-8"))
    order = json.load(open(os.path.join(data_dir, "container_projects.json"), encoding="utf-8"))["vm_view"].get("host_order") or []
    used = {e["host"] for e in _entries(json.load(open(os.path.join(data_dir, "containers_declared.json"), encoding="utf-8")), []) if e.get("role") != "repo"}
    hosts = [(v["alias"] or v["id"], v["alias"] or v["id"]) for v in fleet["vms"] if v["alias"] in used or v["id"] in used]
    hosts += [(d["id"], d.get("short") or d["model"].replace(" ", "")) for d in fleet["electronics"] if d.get("wg_ip")]
    return sorted(hosts, key=lambda h: order.index(h[0]) if h[0] in order else len(order))  # stable: undeclared hosts keep the fleet order


def page_ids(vm_prefix, data_dir=HERE):
    return [vm_prefix + "all"] + [vm_prefix + h for h, _ in derive(data_dir)] + [vm_prefix + "unknown"]


if __name__ == "__main__":
    print(page_ids("vm-"))
