#!/usr/bin/env python3
"""#1708 fetch the terminal's native tools into jniLibs/<abi>/ (see tools/native-tools.json). Usage: fetch-native-tools.py APP_ROOT JNILIBS_DIR
Every file is refused unless its sha256 matches the pin. Exit 0 ok, 1 a download or hash failure."""
import hashlib, json, os, sys, urllib.request

def get(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()

def main(root, out):
    cfg = json.load(open(os.path.join(root, "tools", "native-tools.json")))
    for abi, a in cfg["abis"].items():
        d = os.path.join(out, abi)
        os.makedirs(d, exist_ok=True)
        for name, want in a["files"].items():
            url = (cfg["axs_base"] + "/" + a["axs"]) if name == "libaxs.so" else (cfg["libs_base"] + "/" + a["libs_dir"] + "/" + name)
            data = get(url)
            got = hashlib.sha256(data).hexdigest()
            if got != want:
                print("native tool %s/%s: sha256 %s != pinned %s (%s)" % (abi, name, got, want, url), file=sys.stderr)
                return 1
            with open(os.path.join(d, name), "wb") as f:
                f.write(data)
            os.chmod(os.path.join(d, name), 0o755)
            print("native tool %s/%s ok (%d bytes)" % (abi, name, len(data)))
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr); sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
