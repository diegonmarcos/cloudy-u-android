package com.termux.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.Nullable;

import com.diegonmarcos.cloud.terminal.ICloudSession;
import com.termux.cloud.CloudExecService;
import com.termux.cloud.SessionGate;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.JNI;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A login shell of THIS terminal, in a PTY, handed to another fleet app —
 * the zero-setup way Cloud MyTerminal reaches it.
 *
 * WHY NOT SSH OVER LOOPBACK
 * MyTerminal used to ssh into 127.0.0.1:8023, which needed openssh installed
 * here, host keys generated, sshd started bound to loopback and kept alive,
 * and MyTerminal's key in ~/.ssh/authorized_keys: five things a person did by
 * hand, any of which could be false on a given phone, and a listening TCP
 * socket that every app on the phone could knock on. This is a binder call:
 * nothing listens, there is no key to copy, and the system itself refuses
 * every caller not signed with the fleet key (the manifest's signature-level
 * {@link SessionGate#PERMISSION}); {@link SessionGate} refuses them again.
 *
 * THE SHELL IS THE ONE A TYPED SESSION GETS. Same executable search as
 * TermuxSession.execute ($PREFIX/bin/login first, as a login shell "-login"),
 * same TermuxShellEnvironmentClient environment, so ~/.termux/shell = enter.sh
 * enters the rootfs and login-exec sources login-init.sh: the fish greeting,
 * linux-store / linux-account and the prompt marks are all the app's own.
 *
 * HEADLESS. prepare() runs what the activity's first start would
 * (TerminalDebugApi.prepare: bootstrap, rootfs staging, foreground service,
 * DNS bridge), so a terminal installed and never opened still answers.
 *
 * LIFETIME. Each PTY's master fd stays open here (for resize) and a dup goes
 * to the caller. A waiter thread reaps the shell and closes our end, so the
 * caller reads EOF/EIO when the shell exits. When the last client unbinds —
 * including a client that died — every shell it opened is killed: nothing
 * keeps running for an app that is gone.
 */
public class CloudSessionService extends Service {

    private static final String LOG_TAG = "CloudSessionService";
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_TIMEOUT_MS = 120_000;

    private static final class Pty {
        final int fd;
        final int pid;
        Pty(int fd, int pid) { this.fd = fd; this.pid = pid; }
    }

    private final Map<String, Pty> mPtys = new HashMap<>();

    /** Throws SecurityException naming the refusal; the binder carries it to the caller. */
    private void gate() {
        int uid = Binder.getCallingUid();
        boolean perm = checkCallingPermission(SessionGate.PERMISSION) == PackageManager.PERMISSION_GRANTED;
        boolean sig = getPackageManager().checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH;
        String refusal = SessionGate.refusal(uid, Process.myUid(), perm, sig);
        if (refusal != null) throw new SecurityException(refusal);
    }

    private final ICloudSession.Stub mBinder = new ICloudSession.Stub() {
        @Override
        public int version() {
            gate();
            return SessionGate.VERSION;
        }

        @Override
        public Bundle prepare() {
            gate();
            Bundle b = new Bundle();
            String error = TerminalDebugApi.prepare(getApplicationContext());
            b.putBoolean("ok", error == null);
            if (error != null) b.putString("error", error);
            return b;
        }

        @Override
        public ParcelFileDescriptor openPty(String id, int cols, int rows, String cwd, Bundle info) {
            gate();
            String key = SessionGate.key(Binder.getCallingUid(), id);
            if (key == null) {
                info.putString("error", "invalid session id");
                return null;
            }
            String error = TerminalDebugApi.prepare(getApplicationContext());
            if (error != null) {
                info.putString("error", "the terminal could not bootstrap: " + error);
                return null;
            }
            try {
                Pty pty = spawn(getApplicationContext(), SessionGate.cols(cols), SessionGate.rows(rows), cwd);
                synchronized (mPtys) {
                    Pty old = mPtys.put(key, pty);
                    if (old != null) kill(old);
                }
                reap(key, pty);
                info.putInt("pid", pty.pid);
                // fromFd dups: the caller's copy is closed by the binder after it is written,
                // ours stays open for resize until the shell exits.
                return ParcelFileDescriptor.fromFd(pty.fd);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "openPty failed", e);
                info.putString("error", "could not start a login shell: " + e.getMessage());
                return null;
            }
        }

        @Override
        public void resize(String id, int cols, int rows) {
            gate();
            Pty pty = find(id);
            if (pty != null) JNI.setPtyWindowSize(pty.fd, SessionGate.rows(rows), SessionGate.cols(cols), 0, 0);
        }

        @Override
        public void close(String id) {
            gate();
            String key = SessionGate.key(Binder.getCallingUid(), id);
            if (key == null) return;
            Pty pty;
            synchronized (mPtys) { pty = mPtys.remove(key); }
            if (pty != null) kill(pty);
        }

        @Override
        public Bundle exec(String script, int timeoutMs) {
            gate();
            int timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
            String error = TerminalDebugApi.prepare(getApplicationContext());
            if (error != null) {
                Bundle b = new Bundle();
                b.putString("error", "the terminal could not bootstrap: " + error);
                b.putInt("exit", -1);
                return b;
            }
            // Through login, as a session: enter.sh runs `<login shell> -l -c "sh -s"` inside the
            // rootfs, and the script arrives on stdin, so its quoting is POSIX sh's whatever
            // the login shell is (fish would parse an inline script differently).
            return CloudExecService.run(getApplicationContext(),
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login",
                new String[]{"-c", "sh -s"}, script == null ? "" : script,
                TermuxConstants.TERMUX_HOME_DIR_PATH, timeout);
        }
    };

    @Nullable
    private Pty find(String id) {
        String key = SessionGate.key(Binder.getCallingUid(), id);
        if (key == null) return null;
        synchronized (mPtys) { return mPtys.get(key); }
    }

    /** What TermuxSession.execute does for a new non-failsafe session, minus the emulator. */
    private static Pty spawn(Context context, int cols, int rows, String cwd) {
        TermuxShellEnvironmentClient env = new TermuxShellEnvironmentClient();
        String workdir = (cwd != null && !cwd.isEmpty() && new File(cwd).isDirectory())
            ? cwd : env.getDefaultWorkingDirectoryPath();
        if (workdir.isEmpty()) workdir = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String[] environment = env.buildEnvironment(context, false, workdir);

        String binPath = env.getDefaultBinPath();
        if (binPath.isEmpty()) binPath = "/system/bin";
        String executable = null;
        for (String shell : new String[]{"login", "bash", "zsh"}) {
            File f = new File(binPath, shell);
            if (f.canExecute()) { executable = f.getAbsolutePath(); break; }
        }
        boolean loginShell = executable != null;
        if (executable == null) executable = "/system/bin/sh";

        String[] processArgs = env.setupProcessArgs(executable, new String[0]);
        String[] argv = processArgs.clone();
        // #1708 started through the system linker or an interpreter, the process gets the argv[0]
        // the kernel would have given it (that file's path), never a "-name".
        String program = com.termux.shared.shell.TermuxShellUtils.setupProcessArgsDirect(executable, null)[0];
        if (program.equals(processArgs[0]))
            argv[0] = (loginShell ? "-" : "") + ShellUtils.getExecutableBasename(program);

        int[] pid = new int[1];
        int fd = JNI.createSubprocess(processArgs[0], workdir, argv, environment, pid, rows, cols, 0, 0);
        return new Pty(fd, pid[0]);
    }

    private void reap(final String key, final Pty pty) {
        Thread waiter = new Thread(() -> {
            JNI.waitFor(pty.pid);
            synchronized (mPtys) {
                if (mPtys.get(key) == pty) mPtys.remove(key);
            }
            JNI.close(pty.fd);
        }, "CloudSessionReaper[pid=" + pty.pid + "]");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static void kill(Pty pty) {
        // The reaper closes the fd once the shell is gone.
        try {
            android.system.Os.kill(pty.pid, android.system.OsConstants.SIGKILL);
        } catch (Exception ignored) {
            // Already exited.
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // The last client is gone (unbound or dead): its shells go with it.
        synchronized (mPtys) {
            for (Iterator<Pty> it = mPtys.values().iterator(); it.hasNext(); ) {
                kill(it.next());
                it.remove();
            }
        }
        return false;
    }
}
