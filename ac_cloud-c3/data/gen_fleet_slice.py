#!/usr/bin/env python3
"""Derive data/fleet_slice.json (the Infra section's Providers / VMs / Serverless Cont / Storage / Electronics / Domains pages)
from the fleet's own declarations. NOTHING here is typed: every VM, IP, bucket, peer and DNS record is READ.

  python3 data/gen_fleet_slice.py [--consolidated F] [--zone F] [--assets F] [--users F] [--gcloud F]   (default: cloud-infra main, raw)

Sources, all in cloud-infra:
  consolidated  1_cloud-configs/dist/_cloud-data-consolidated.json  vms, vpss, storage, owner, native.wireguard(+public),
                                                                    services.caddy mta_sts  (VMs, regions, IPs, WG IPs, buckets)
  zone          c_vps/ba-clo_cloudflare/src/terraform.json          the zone's DNS records (A/CNAME/TXT/DKIM/SRV/CAA/SES)
  users         1_cloud-configs/src/inputs/superapp-users.json      users.*.peers: the devices that are mesh peers
  gcloud        c_vps/vps_gcloud/src/terraform.json                 cloud_run_services: the GCP Cloud Run fleet registry
  assets        1_cloud-configs/src/inputs/fleet-assets.json        the ONE file for what the others lack: provider links and
                                                                    cost source, peer OS and role, non-peer electronics,
                                                                    storage backends, the domain registration

Secrets: none is carried - no key, token, instance id or tfvars value; DKIM public keys are tfvars and stay out.
"""
import argparse, json, os, re, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "https://raw.githubusercontent.com/diegonmarcos/cloudy-t-infra/main/"
PATHS = {
    "consolidated": "1_cloud-configs/dist/_cloud-data-consolidated.json",
    "zone": "c_vps/ba-clo_cloudflare/src/terraform.json",
    "assets": "1_cloud-configs/src/inputs/fleet-assets.json",
    "users": "1_cloud-configs/src/inputs/superapp-users.json",
    "gcloud": "c_vps/vps_gcloud/src/terraform.json",
}


def load(arg, key):
    if arg:
        return json.load(open(arg, encoding="utf-8"))
    return json.load(urllib.request.urlopen(BASE + PATHS[key]))


# The self-hosted provider (owner request 2026-10-25): our own fleet on the WireGuard mesh, listed beside OCI/GCP/AWS/Hetzner/
# Cloudflare. DERIVED, never typed per VM: its VMs are every declared VM that holds a mesh (wg0) IP. It is flagged self_hosted so
# the compute view groups by the real vendor providers and does not list the same VM twice.
SELF_HOSTED = {
    "id": "cloud", "label": "Cloud", "account": "own fleet on the WireGuard mesh", "plan": "self-managed",
    "use": "Our own cloud: the mesh VMs and the containers on them, run by us on vendor capacity",
    "cost": None, "links": [], "self_hosted": True,
}


def with_self_hosted(providers, vms):
    return providers + [dict(SELF_HOSTED, vms=[v["id"] for v in vms if v.get("wg_ip")])]


def serverless(gcloud):
    """The Serverless Cont page: every Cloud Run service of the registry, as declared (no secret: names of secrets only
    are in the registry and none is carried here)."""
    out = []
    for s in gcloud.get("cloud_run_services") or []:
        out.append({"name": s["name"], "label": s.get("label") or s["name"], "provider": "gcloud", "region": s.get("region"),
                    "state": s.get("state"), "managed": bool(s.get("managed")), "image": s.get("image"), "port": s.get("port"),
                    "cpu": s.get("cpu"), "memory": s.get("memory"), "min_instances": s.get("min_instances", 0),
                    "max_instances": s.get("max_instances"), "from_host": s.get("from_host"),
                    "source": "%s/%s" % (s["source"]["repo"], s["source"]["path"]) if s.get("source") else None,
                    "persistence": s.get("persistence"), "volumes": [v["mount_path"] for v in s.get("volumes") or []],
                    "route": (s.get("route") or {}).get("edge_path") or (s.get("route") or {}).get("public_url"),
                    "blockers": s.get("blockers") or []})
    return out


def build(cons, zone, assets, users, gcloud=None):
    vms, vpss = cons["vms"], cons["vpss"]
    provider_of_vpss = {p["vpss"]: p["id"] for p in assets["providers"] if p.get("vpss")}
    vpss_of_vm = {inst: key for key, v in vpss.items() for inst in v.get("instances") or []}
    named = {n: p["id"] for p in assets["providers"] for n in [p["id"]] + (p.get("vm_provider_names") or [])}
    wg0 = cons["native"]["wireguard"]
    wgp = cons["wireguard_public"]
    pub_ip = {p["name"]: p["wg_ip"] for p in wgp.get("peers") or []}

    out_vms = []
    for vid, v in vms.items():
        sp = v.get("specs") or {}
        key = vpss_of_vm.get(vid)
        pid = provider_of_vpss.get(key) if key else named.get(v.get("provider"))
        if pid is None:
            raise SystemExit("VM %s is in no declared provider (vpss.*.instances, or its provider field via fleet-assets providers[].vm_provider_names)" % vid)
        alias = v.get("ssh_alias")
        out_vms.append({
            "id": vid, "alias": alias, "provider": pid,
            "region": sp.get("cloud_zone"), "size": sp.get("shape") or sp.get("machine_type"),
            "gpu": sp.get("gpu"), "cpu": sp.get("cpu"), "ram_gb": sp.get("ram_gb"), "disk_gb": sp.get("disk_gb"),
            "arch": sp.get("arch"), "public_ip": v.get("ip"), "wg_ip": v.get("wg_ip"), "wg_public_ip": pub_ip.get(alias),
            "wg_role": v.get("wg_role") if v.get("wg_ip") else None,
            "public_ingress": bool(v.get("is_public_ingress")),
            "desc": v.get("description"),
        })

    gcp_project = None
    for v in vms.values():
        m = re.match(r"projects/([^/]+)/", (v.get("specs") or {}).get("instance_id") or "")
        if m:
            gcp_project = m.group(1)
            break
    account_from = {
        "storage_namespace": lambda: "tenancy namespace %s" % cons["storage"][0]["namespace"],
        "gcp_project": lambda: "project %s" % gcp_project,
        "cf_account_id": lambda: "account %s" % zone["account_id"],
        "owner_github": lambda: "@%s" % cons["owner"]["github"],
    }
    providers = []
    for p in assets["providers"]:
        vp = vpss.get(p["vpss"]) if p.get("vpss") else None
        if p.get("vpss") and vp is None:
            raise SystemExit("provider %s names vpss %r that config.json does not declare" % (p["id"], p["vpss"]))
        providers.append({
            "id": p["id"], "label": p["label"],
            "account": account_from[p["account_from"]]() if p.get("account_from") else p.get("account"),
            "plan": p.get("plan") or (vp or {}).get("tier"),
            "use": p.get("use") or (vp or {}).get("description"),
            "cost": p.get("cost"),
            "links": p.get("links") or [],
            "vms": [v["id"] for v in out_vms if v["provider"] == p["id"]],
        })

    buckets = [{"provider": b["provider"], "name": b["name"], "tier": b.get("tier"), "region": b.get("region"),
                "dns": b.get("dns")} for b in cons["storage"]]

    peers = {}
    for u in users["users"].values():
        peers.update(u.get("peers") or {})
    extra = {e["peer"]: e for e in assets["electronics"] if "peer" in e}
    for pid in extra:
        if pid not in peers:
            raise SystemExit("fleet-assets electronics names peer %r that superapp-users.json does not declare" % pid)
    electronics = []
    for pid, pr in peers.items():
        e = extra.get(pid, {})
        wc = pr.get("wg_client")
        w0 = (wg0.get("clients") or {}).get(wc) or {}
        w1 = (wgp.get("clients") or {}).get(wc) or {}
        electronics.append({"id": pid, "model": pr["label"], "kind": pr["kind"], "os": e.get("os"), "role": e.get("role"),
                            "wg_ip": w0.get("wg_ip"), "wg_public_ip": w1.get("wg_ip")})
    for e in assets["electronics"]:
        if "peer" not in e:
            electronics.append({"id": e["id"], "model": e["label"], "kind": e["kind"], "os": e.get("os"),
                                "role": e.get("role"), "wg_ip": None, "wg_public_ip": None})

    caddy = (cons["services"].get("caddy") or {}).get("caddy_config") or {}
    sts = caddy.get("mta_sts") or {}
    domains = []
    for d in assets["domains"]:
        if d.get("zone_from") != "terraform" or zone["domain"] != d["name"]:
            raise SystemExit("domain %s has no zone declaration in terraform.json" % d["name"])
        domains.append(dict({k: d[k] for k in ("name", "registrar", "admin", "renewal_paid", "expires", "next_renewal",
                                               "auto_renew", "nameservers", "verified")},
                            records=zone_records(d["name"], zone, sts),
                            mta_sts={k: sts.get(k) for k in ("domain", "mode", "max_age", "mx")}))

    return {
        "_meta": {"generated_by": "data/gen_fleet_slice.py", "sources": PATHS,
                  "note": "READ from the fleet declarations; never edit - run the generator. No key, token or instance id is carried."},
        "providers": with_self_hosted(providers, out_vms), "vms": out_vms, "serverless": serverless(gcloud or {}),
        "storage": {"buckets": buckets, "backends": assets["storage_backends"]},
        "electronics": electronics, "domains": domains,
    }


def zone_records(domain, z, sts):
    dr = z["dns_records"]
    rows = []

    def fq(n):
        return domain if n in ("", "@", domain) else n + "." + domain

    def add(group, name, typ, content, ttl=None, proxied=None, comment=None, priority=None):
        rows.append({"group": group, "name": fq(name), "type": typ, "content": content, "ttl": ttl,
                     "proxied": proxied, "priority": priority, "comment": comment})

    for r in dr.get("a_records") or []:
        add("Web", r["name"], "A", r.get("ip") or z["proxy_ip"], r.get("ttl"), r.get("proxied"), r.get("comment"))
        add("Web", r["name"], "AAAA", r.get("ip6") or z["proxy_ip6"], r.get("ttl"), r.get("proxied"), r.get("comment"))
    for mx in sts.get("mx") or []:
        add("MX", domain, "MX", mx, None, None, "Cloudflare Email Routing (managed by Cloudflare; targets from the MTA-STS policy)")
    m = dr.get("ses_mail_from_mx")
    if m:
        add("MX", m["name"], "MX", m["content"], m.get("ttl"), None, m.get("comment"), m.get("priority"))
    groups = {"spf": "SPF", "ses_mail_from_spf": "SPF", "dmarc": "DMARC", "mta_sts": "MTA-STS", "tls_rpt": "TLS-RPT"}
    for r in dr.get("txt_records") or []:
        add(groups.get(r["key"], "Other"), r["name"], "TXT", r["content"], r.get("ttl"), None, r.get("comment"))
    for r in dr.get("cname_records") or []:
        grp = "MTA-STS" if r["key"] == "mta_sts" else "DKIM" if "dkim" in r["key"] else "Web"
        add(grp, r["name"], "CNAME", r["content"], r.get("ttl"), r.get("proxied"), r.get("comment"))
    for r in dr.get("dkim_records") or []:
        add("DKIM", r["name"], "TXT", "public key (tfvars: %s)" % r["var"], r.get("ttl"), None, r.get("comment"))
    for r in dr.get("ses_dkim_cnames") or []:
        add("DKIM", "<%s>._domainkey" % r["var"], "CNAME", "<token>.dkim.amazonses.com", r.get("ttl"), None, r.get("comment"))
    v = dr.get("ses_verification")
    if v:
        add("Other", v["name"], "TXT", "verification token (tfvars: %s)" % v["var"], v.get("ttl"), None, v.get("comment"))
    for r in dr.get("caa_records") or []:
        add("Other", r["name"], "CAA", "%s %s \"%s\"" % (r["flags"], r["tag"], r["value"]), r.get("ttl"), None, r.get("comment"))
    for r in dr.get("srv_records") or []:
        add("Other", r["name"], "SRV", "%s %s %s %s" % (r["priority"], r["weight"], r["port"], r["target"]), r.get("ttl"), None, r.get("comment"))
    return rows


def main():
    ap = argparse.ArgumentParser()
    for k in PATHS:
        ap.add_argument("--" + k)
    a = ap.parse_args()
    out = build(load(a.consolidated, "consolidated"), load(a.zone, "zone"), load(a.assets, "assets"), load(a.users, "users"), load(a.gcloud, "gcloud"))
    with open(os.path.join(HERE, "fleet_slice.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote fleet_slice.json: %d providers, %d vms, %d serverless, %d buckets, %d electronics, %d dns records" % (
        len(out["providers"]), len(out["vms"]), len(out["serverless"]), len(out["storage"]["buckets"]), len(out["electronics"]),
        sum(len(d["records"]) for d in out["domains"])))


if __name__ == "__main__":
    sys.exit(main())
