package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;

import com.diegonmarcos.superapp.devtools.AppDebugServer;
import com.diegonmarcos.superapp.fleetdata.FleetData;
import com.termux.BuildConfig;
import com.termux.cloud.CloudDnsBridge;
import com.termux.cloud.CloudExecService;
import com.termux.cloud.CloudRootfs;
import com.termux.cloud.CloudWakeLock;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * #747 /api/terminal/exec and /api/terminal/selftest on the fleet debug API (#744), so this
 * terminal's tooling and storage links can be tested from outside the UI.
 *
 * NO SECOND SERVER: a group on the shared AppDebugServer, behind its fleet-token gate like every
 * other route; nothing here authenticates anything.
 *
 * THE COMMAND RUNS THROUGH THE SESSION'S OWN ENTRY: $PREFIX/bin/login, with the environment
 * TermuxShellEnvironmentClient builds for a new non-failsafe session, from $HOME. login hands
 * `-c <cmd>` to ~/.termux/shell = enter.sh, which enters the rootfs under proot with the same binds,
 * storage links and `env -i` set a typed session gets, and runs the rootfs login shell as
 * `<shell> -l -c <cmd>`. A second, hand-built environment would answer for a terminal nobody uses.
 *
 * HEADLESS: no activity and no session are needed. The bootstrap and the rootfs staging the
 * activity's first start would do are run here, blocking (TermuxInstaller.ensureInstalled), and so
 * is enter.sh's one-time unpack, with its own long timeout so the first command is not the one
 * that pays minutes for it. TermuxService is started in the foreground, as opening the app does,
 * which keeps the process un-frozen with the screen locked and starts the DNS bridge the shell
 * resolves through; a partial wake lock holds the CPU for the length of the command.
 *
 * Output is never logged: a command's stdout is the caller's, and may carry anything.
 */
final class TerminalDebugApi {

    private static final String LOG_TAG = "TerminalDebugApi";

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    /** The debug server answers one request at a time, so this is also how long every other route can wait. */
    private static final int MAX_TIMEOUT_MS = 600_000;
    /** enter.sh's first start unpacks the ~400 MB rootfs before it runs anything. */
    private static final int UNPACK_TIMEOUT_MS = 900_000;
    /** The selftest's commands: the runtime dataset served by Cloud Store (#1547, repo data/fleet/app/cld.termux/terminal-selftest.json), not an APK asset. */
    private static final String SELFTEST_DATASET = "terminal-selftest";

    private TerminalDebugApi() {}

    static void register(Context context) {
        final Context app = context.getApplicationContext();
        // #787 the toggle's value before the service first starts; NONE, nothing is open yet.
        CloudWakeLock.STATE.update(TermuxService.wakeLockWanted(app), 0, 0);
        AppDebugServer.INSTANCE.route("terminal", Arrays.asList(
            new AppDebugServer.Op("exec", "cmd=<shell command>, timeout=<ms, default " + DEFAULT_TIMEOUT_MS
                + ", max " + MAX_TIMEOUT_MS + ">",
                "run cmd in a fresh login session's environment (rootfs, HOME, PATH); "
                    + "returns stdout, stderr, exit. Bootstraps the terminal first if it never was."),
            new AppDebugServer.Op("selftest", "",
                "run the declared tool and storage checks (" + SELFTEST_DATASET + ") and report each"),
            new AppDebugServer.Op("wakelock", "",
                "#787 the session wake lock (also at /api/terminal): held, since (epoch ms or null), "
                    + "sessions, wanted (the in-app toggle), wifi_lock")
        ), (op, query) -> {
            try {
                switch (op) {
                    case "":
                    case "wakelock": return CloudWakeLock.STATE.json(BuildConfig.CLOUD_WIFI_LOCK);
                    case "exec": return exec(app, query);
                    case "selftest": return selftest(app);
                    default: return null;
                }
            } catch (JSONException e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(e.toString()) + "}";
            }
        });
        // #794 the shell's DNS bridge as the SuperApp's DNS page shows it: listening or why not,
        // queries and how they ended, the last one's time and the last error. Its own group so a
        // client can ask any app for it and read a 404 as "no bridge here".
        AppDebugServer.INSTANCE.route("sysdns", Arrays.asList(
            new AppDebugServer.Op("state", "", "this terminal's 127.0.0.1 DNS bridge: listening (or why not), "
                + "port, queries, answered, servfail, errors, last_query_ms, last_error")
        ), (op, query) -> "state".equals(op) ? CloudDnsBridge.state() : null);
    }

    private static String exec(Context app, Map<String, String> query) throws JSONException {
        String cmd = query.get("cmd");
        if (cmd == null || cmd.isEmpty())
            return new JSONObject().put("ok", false).put("error", "need ?cmd=<shell command>").toString();
        int timeout = DEFAULT_TIMEOUT_MS;
        try {
            if (query.get("timeout") != null) timeout = Integer.parseInt(query.get("timeout"));
        } catch (NumberFormatException ignored) {
            // Not a number: the default, rather than a 400 for a debug knob.
        }
        timeout = Math.max(1_000, Math.min(timeout, MAX_TIMEOUT_MS));

        JSONObject ready = ready(app);
        if (!ready.getBoolean("ok")) return ready.toString();
        return run(app, cmd, timeout).put("bootstrap", ready.get("bootstrap")).toString();
    }

    private static String selftest(Context app) throws JSONException {
        JSONObject ready = ready(app);
        if (!ready.getBoolean("ok")) return ready.toString();

        // Never throws: null = Cloud Store has not served the list yet (first run, provider absent); the caller retries later.
        JSONArray checks;
        try {
            String served = FleetData.INSTANCE.get(app, SELFTEST_DATASET, true, (String s) -> s);
            if (served == null) return new JSONObject().put("ok", false).put("error", SELFTEST_DATASET + " is not served yet by Cloud Store").toString();
            checks = new JSONObject(served).getJSONArray("checks");
        } catch (RuntimeException e) {
            return new JSONObject().put("ok", false).put("error", "cannot read " + SELFTEST_DATASET + ": " + e).toString();
        }

        JSONArray results = new JSONArray();
        int failed = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject r = run(app, checks.getString(i), DEFAULT_TIMEOUT_MS);
            if (!r.getBoolean("ok")) failed++;
            results.put(r);
        }
        return new JSONObject()
            .put("ok", failed == 0)
            .put("passed", checks.length() - failed)
            .put("failed", failed)
            .put("bootstrap", ready.get("bootstrap"))
            // #786: the builds that ran this list, comparable to the release's sidecars.
            .put("installed", CloudRootfs.installed(app))
            .put("results", results)
            .toString();
    }

    /**
     * Everything a typed session can count on before its first prompt: the bootstrap, the staged
     * rootfs, enter.sh's unpack, and the service with its DNS bridge. {"ok":true,"bootstrap":...}
     * or the reason it is not ready.
     */
    private static JSONObject ready(Context app) throws JSONException {
        String error = prepare(app);
        if (error != null)
            return new JSONObject().put("ok", false).put("bootstrap", "failed").put("error", error);

        if (CloudRootfs.isUnpacked()) return new JSONObject().put("ok", true).put("bootstrap", "ready");
        JSONObject unpack = run(app, "true", UNPACK_TIMEOUT_MS);
        if (CloudRootfs.isUnpacked()) return new JSONObject().put("ok", true).put("bootstrap", "unpacked now");
        // enter.sh fell back to bash (its stderr in `unpack` says why), or ~/.termux/shell no longer
        // points at it. A typed session lands in that same shell, so the command runs there too —
        // and the answer says so instead of passing for the rootfs.
        return new JSONObject().put("ok", true).put("bootstrap", "rootfs not unpacked").put("unpack", unpack);
    }

    /**
     * The headless half of {@link #ready}, shared with CloudSessionService: the bootstrap and the
     * staged rootfs, then the service with its DNS bridge. null, or why the terminal cannot start.
     * enter.sh's one-time unpack is NOT run here: a session shows it happening, a debug call pays
     * it in ready() with its own long timeout.
     */
    static String prepare(Context app) {
        try {
            TermuxInstaller.ensureInstalled(app);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Headless bootstrap failed", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
        try {
            app.startForegroundService(new Intent(app, TermuxService.class));
        } catch (RuntimeException e) {
            // The command still runs; only its protection from the freezer is missing, and the
            // log says why.
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not start TermuxService", e);
        }
        CloudDnsBridge.start(app);
        return null;
    }

    /** One command through login, as a JSON result. */
    private static JSONObject run(Context app, String cmd, int timeout) throws JSONException {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cld.termux:terminal-exec");
        lock.acquire(timeout + 10_000L);
        long started = System.currentTimeMillis();
        Bundle b;
        try {
            b = CloudExecService.run(app, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login",
                new String[]{"-c", cmd}, null, TermuxConstants.TERMUX_HOME_DIR_PATH, timeout);
        } finally {
            if (lock.isHeld()) lock.release();
        }
        boolean timedOut = b.getBoolean("timed_out", false);
        JSONObject r = new JSONObject()
            .put("cmd", cmd)
            .put("ok", !timedOut && !b.containsKey("error") && b.getInt("exit") == 0)
            .put("exit", b.getInt("exit"))
            .put("stdout", b.getString("stdout", ""))
            .put("stderr", b.getString("stderr", ""))
            .put("timed_out", timedOut)
            .put("truncated", b.getBoolean("truncated", false))
            .put("duration_ms", System.currentTimeMillis() - started);
        if (b.containsKey("error")) r.put("error", b.getString("error"));
        return r;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
