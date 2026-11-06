package com.termux.cloud;

/**
 * The mesh sshd: the door cloud-superapp-mcp (and a human's `ssh phoneN`) uses to reach this
 * phone over WireGuard. build.json::mesh_sshd is the one declaration (port, bind, start_on_boot).
 *
 * Why this exists: on a freshly installed phone (the A37, #573) the mesh peer came up and answered
 * ping, but every TCP port was REFUSED. Nothing in the app ever started sshd; it was started by
 * hand inside a session on the old phone, so a reinstall or a reboot left the phone with no door.
 * Now the boot receiver starts TermuxService (a foreground service) and runs {@link #script} once.
 *
 * Bind: the wildcard (0.0.0.0 and ::), never a mesh address. The tun's address belongs to the WG
 * profile (10.0.0.9 on one phone, 10.0.0.10 on the other, and it changes with the profile), so a
 * specific address would refuse to bind, or bind the wrong phone's, the moment a profile changes.
 * Exposure stays mesh-only by key auth plus the absence of any public route to the phone.
 *
 * Pure Java on purpose: test/test-mesh-sshd.sh compiles this file on a JVM and runs the script
 * it produces against a stub sshd.
 */
public final class MeshSshd {
    private MeshSshd() {}

    /**
     * The one POSIX sh script that ends the listener {@link #script} started: the pid in its own pidfile and nothing else
     * (never a pattern, never another sshd on the phone). Idempotent: no pidfile, or a dead or non-numeric one, is "not running".
     */
    public static String stopScript() {
        return "set -u\n"
            + "d=\"$HOME/.ssh/mesh-sshd\"; p=$(cat \"$d/pid\" 2>/dev/null) || p=\n"
            + "case \"$p\" in ''|*[!0-9]*) rm -f \"$d/pid\"; echo \"mesh-sshd: not running\"; exit 0;; esac\n"
            + "kill \"$p\" 2>/dev/null; rm -f \"$d/pid\"; echo \"mesh-sshd: stopped\"\n";
    }

    /** The one POSIX sh script that brings the listener up, idempotently. */
    public static String script(int port, String[] binds) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("mesh_sshd.port out of range: " + port);
        if (binds == null || binds.length == 0) throw new IllegalArgumentException("mesh_sshd.bind is empty");
        StringBuilder listen = new StringBuilder();
        for (String b : binds) {
            if (!b.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("mesh_sshd.bind is not an address: " + b);
            listen.append("ListenAddress ").append(b.contains(":") ? "[" + b + "]" : b).append(':').append(port).append('\n');
        }
        return "set -u\n"
            + "d=\"$HOME/.ssh/mesh-sshd\"; mkdir -p \"$d\"; chmod 700 \"$HOME/.ssh\" \"$d\" 2>/dev/null\n"
            // The config this start wants is written beside the live one. An sshd that is up with the SAME config is left alone; one
            // up with a DIFFERENT config (the wildcard one from before mesh-only, an old mesh address, a flag change) is ended and
            // started again, so a change always takes effect and an exposed listener is never kept.
            + "cat > \"$d/sshd_config.new\" <<'CFG'\n"
            + "Port " + port + "\n"
            + listen
            + "PasswordAuthentication no\n"
            + "KbdInteractiveAuthentication no\n"
            + "PubkeyAuthentication yes\n"
            + "AuthorizedKeysFile .ssh/authorized_keys\n"
            + "CFG\n"
            + "printf 'HostKey %s\\nPidFile %s\\n' \"$d/ssh_host_ed25519_key\" \"$d/pid\" >> \"$d/sshd_config.new\"\n"
            + "if [ -f \"$d/pid\" ] && kill -0 \"$(cat \"$d/pid\")\" 2>/dev/null; then\n"
            + "  if [ -f \"$d/sshd_config\" ] && [ \"$(cat \"$d/sshd_config\")\" = \"$(cat \"$d/sshd_config.new\")\" ]; then echo \"mesh-sshd: already running\"; exit 0; fi\n"
            + "  kill \"$(cat \"$d/pid\")\" 2>/dev/null; sleep 1; rm -f \"$d/pid\"; echo \"mesh-sshd: config changed, restarting\"\n"
            + "fi\n"
            + "S=$(command -v sshd 2>/dev/null) || S=$(nix-shell -p openssh --run 'command -v sshd' 2>/dev/null) || S=\n"
            + "[ -n \"$S\" ] || { echo \"mesh-sshd: no sshd in this env\" >&2; exit 3; }\n"
            + "K=\"$(dirname \"$S\")/ssh-keygen\"\n"
            + "[ -f \"$d/ssh_host_ed25519_key\" ] || \"$K\" -q -t ed25519 -N '' -f \"$d/ssh_host_ed25519_key\" || exit 4\n"
            + "cat \"$d/sshd_config.new\" > \"$d/sshd_config\"\n"
            + "\"$S\" -f \"$d/sshd_config\" && echo \"mesh-sshd: listening on :" + port + "\"\n";
    }
}
