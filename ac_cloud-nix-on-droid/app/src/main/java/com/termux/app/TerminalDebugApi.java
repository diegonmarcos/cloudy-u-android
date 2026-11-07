package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.diegonmarcos.superapp.devtools.AppDebugServer;
import com.diegonmarcos.superapp.fleetdata.FleetData;
import com.termux.BuildConfig;
import com.termux.cloud.CloudWakeLock;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * #747 /api/terminal/exec and /api/terminal/selftest on the fleet debug API (#744), so this
 * terminal's tooling and storage links can be tested from outside the UI.
 *
 * NO SECOND SERVER: a group on the shared AppDebugServer, behind its fleet-token gate like every
 * other route; nothing here authenticates anything.
 *
 * THE COMMAND RUNS THROUGH THE SESSION'S OWN ENTRY: $PREFIX/bin/login, with the environment
 * TermuxService.createTermuxSession gives a new non-failsafe session (TermuxShellEnvironment with
 * the shell-command variables), from $HOME. bin/login sets up the storage links and enters proot;
 * login-inner sources the same session init a typed session gets and, given arguments, execs them
 * instead of the login shell. The arguments are that login shell itself —
 * build.json::default_packages.login_shell_attr, via BuildConfig.CLOUD_LOGIN_SHELL — as
 * `<shell> -l -c <cmd>`, so its own login config is read too. A second, hand-built environment
 * would answer for a terminal nobody uses.
 *
 * HEADLESS: no activity and no session are needed. The bootstrap the activity's first start would
 * extract out of the rootfs lib is extracted here, blocking (TermuxInstaller.ensureInstalled).
 * TermuxService is started in the foreground, as opening the app does, which keeps the process
 * un-frozen with the screen locked; a partial wake lock holds the CPU for the length of the command.
 *
 * Output is never logged: a command's stdout is the caller's, and may carry anything.
 */
final class TerminalDebugApi {

    private static final String LOG_TAG = "TerminalDebugApi";

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    /** The debug server answers one request at a time, so this is also how long every other route can wait. */
    private static final int MAX_TIMEOUT_MS = 600_000;
    /** Per stream; past it the rest is drained and dropped, and `truncated` says so. */
    private static final int MAX_OUTPUT = 512 * 1024;
    /** The selftest's commands: the runtime dataset served by Cloud Store (#1547, repo data/fleet/app/cld.termux.nix/terminal-selftest.json), not an APK asset. */
    private static final String SELFTEST_DATASET = "terminal-selftest";

    private TerminalDebugApi() {}

    static void register(Context context) {
        final Context app = context.getApplicationContext();
        // #787 the toggle's value before the service first starts; NONE, nothing is open yet.
        CloudWakeLock.STATE.update(TermuxService.wakeLockWanted(app), 0, 0);
        AppDebugServer.INSTANCE.route("terminal", Arrays.asList(
            new AppDebugServer.Op("exec", "cmd=<shell command>, timeout=<ms, default " + DEFAULT_TIMEOUT_MS
                + ", max " + MAX_TIMEOUT_MS + ">",
                "run cmd in a fresh login session's environment (proot, HOME, PATH, login shell); "
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
        ), (op, query) -> "state".equals(op) ? TermuxApplication.dnsBridgeState() : null);
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
            .put("results", results)
            .toString();
    }

    /** The bootstrap and the running service a typed session can count on: {"ok":true,...} or why not. */
    private static JSONObject ready(Context app) throws JSONException {
        String error = prepare(app);
        if (error != null)
            return new JSONObject().put("ok", false).put("bootstrap", "failed").put("error", error);
        String notice = TermuxInstaller.rootfsVersionNotice();
        // #748: an extracted $PREFIX is not a working terminal. The phone reported "ready" over a
        // bin/login that died at exit 127 before proot, so every check failed with the real cause
        // buried in each stderr. "ready" now means a login ran `true` to the end.
        // ponytail: one extra login per call; cache per rootfs version if that ever matters.
        JSONObject login = run(app, "true", DEFAULT_TIMEOUT_MS);
        if (!login.getBoolean("ok")) {
            JSONObject failed = new JSONObject().put("ok", false).put("bootstrap", "login-failed")
                .put("error", "the extracted rootfs cannot log in (`true` through bin/login exited "
                    + login.get("exit") + "); see login.stderr")
                .put("login", login);
            if (notice != null) failed.put("rootfs", notice);
            return failed;
        }
        return new JSONObject().put("ok", true).put("bootstrap", notice == null ? "ready" : notice);
    }

    /**
     * The headless half of {@link #ready}, shared with CloudSessionService: the bootstrap out of
     * the companion lib, then the service. null, or why the terminal cannot start. The proving
     * login ready() adds is left to the caller: a session shows its own failure.
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
        return null;
    }

    /** One command through login, as a JSON result. */
    private static JSONObject run(Context app, String cmd, int timeout) throws JSONException {
        return run(app, cmd, null, timeout);
    }

    /** {@link #run(Context, String, int)} with [stdin] fed to the command (then closed). */
    static JSONObject run(Context app, String cmd, String stdin, int timeout) throws JSONException {
        JSONObject r = new JSONObject().put("cmd", cmd);
        String cwd = TermuxConstants.TERMUX_HOME_DIR_PATH;

        // The command a new session is made of (TermuxService.createTermuxSession), with login's
        // arguments added: the same id source, runner and shell-command environment.
        TermuxShellEnvironment shellEnvironment = new TermuxShellEnvironment();
        ExecutionCommand command = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login",
            new String[]{BuildConfig.CLOUD_LOGIN_SHELL, "-l", "-c", cmd}, null, cwd,
            Runner.TERMINAL_SESSION.getName(), false);
        command.setShellCommandShellEnvironment = true;
        HashMap<String, String> environment = shellEnvironment.setupShellCommandEnvironment(app, command);
        String[] argv = shellEnvironment.setupShellCommandArguments(command.executable, command.arguments);

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cld.termux.nix:terminal-exec");
        lock.acquire(timeout + 10_000L);
        long started = System.currentTimeMillis();
        try {
            Process process;
            try {
                process = Runtime.getRuntime().exec(argv,
                    ShellEnvironmentUtils.convertEnvironmentToEnviron(environment).toArray(new String[0]), new File(cwd));
            } catch (IOException e) {
                return r.put("ok", false).put("exit", -1).put("error", "could not start login: " + e.getMessage());
            }
            // stdin written and closed: a command that reads it gets EOF instead of waiting out the
            // timeout.
            try (java.io.OutputStream os = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Already gone: the command exited first.
            }
            // Both streams on their own threads: one reader deadlocks once the other pipe fills.
            Drain out = new Drain(process.getInputStream());
            Drain err = new Drain(process.getErrorStream());
            out.start();
            err.start();

            boolean finished;
            try {
                finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            // destroy() ends proot, and proot's tracees with it; the drains then see EOF.
            if (!finished) process.destroy();
            out.finish();
            err.finish();

            r.put("ok", finished && process.exitValue() == 0)
                .put("exit", finished ? process.exitValue() : -1)
                .put("stdout", out.text())
                .put("stderr", err.text())
                .put("timed_out", !finished)
                .put("truncated", out.truncated || err.truncated)
                .put("duration_ms", System.currentTimeMillis() - started);
            if (!finished) r.put("error", "did not finish within " + timeout + "ms");
            return r;
        } finally {
            if (lock.isHeld()) lock.release();
        }
    }

    /** One stream, read to EOF on its own thread, capped at MAX_OUTPUT and drained past it. */
    private static final class Drain extends Thread {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();
        volatile boolean truncated;

        Drain(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            char[] buf = new char[8192];
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = reader.read(buf)) != -1) {
                    // Keep reading past the cap: a full pipe blocks the command forever.
                    if (sb.length() >= MAX_OUTPUT) truncated = true;
                    else sb.append(buf, 0, n);
                }
            } catch (IOException ignored) {
                // A killed process closes its pipes mid-read; what arrived is still the answer.
            }
        }

        void finish() {
            try {
                join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return sb.length() > MAX_OUTPUT ? sb.substring(0, MAX_OUTPUT) : sb.toString();
        }
    }
}
