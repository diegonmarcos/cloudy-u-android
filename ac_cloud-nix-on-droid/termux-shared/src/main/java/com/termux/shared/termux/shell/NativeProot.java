package com.termux.shared.termux.shell;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.SystemLinkerExec;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Map;

/**
 * #1708 proot and its loader, run from this APK's native library directory.
 *
 * WHY. Every session of this terminal is {@code $PREFIX/bin/login} (a /system/bin/sh script)
 * exec'ing {@code $PREFIX/bin/proot-static}, which then starts every guest program through its
 * loader. Both are files in the app's data directory (the loader is written into
 * $PROOT_TMP_DIR by proot itself), and from targetSdk 29 Android forbids execve() of such a
 * file: the terminal would say "Permission denied" before its first prompt. Everything INSIDE
 * the guest is unaffected: proot's loader maps the nix store's programs instead of exec'ing
 * them, which Android still allows.
 *
 * WHAT. The same proot (ab_cloud-terminal-store/proot.json) and the loader carved out of it
 * (carve-proot-loader.py) ship in this APK as libcloudproot.so and libcloudprootloader.so,
 * in the one directory an app may exec from (app/build.gradle stageNativeProot). Where the
 * rule applies ({@link SystemLinkerExec#required()}):
 *  - {@code $PREFIX/bin/proot-static} is made a LINK to the native proot, so bin/login, its
 *    webserver launcher and anything else that names that path run it unchanged (the bootstrap's
 *    own copy is kept beside it as proot-static.data, never deleted);
 *  - PROOT_LOADER names the native loader in the environment of every command, so proot
 *    extracts nothing.
 * Anywhere else (Android 9 and older, or a process still in a domain that may exec its data)
 * nothing is touched and nothing is exported.
 *
 * WHEN. {@link #apply} runs while the environment of each session or command is built, so the
 * link is right after an app update (the native directory moves), after a bootstrap
 * re-extraction and after `nix-on-droid switch` installed a proot of its own. Never throws.
 */
public final class NativeProot {

    private static final String LOG_TAG = "NativeProot";

    /** proot itself as it ships under jniLibs. */
    public static final String NATIVE_PROOT = "libcloudproot.so";
    /** proot's loader as it ships under jniLibs. */
    public static final String NATIVE_PROOT_LOADER = "libcloudprootloader.so";
    /** The file bin/login execs. */
    public static final String PROOT_ENTRY = "proot-static";
    /** Where the bootstrap's own proot is kept once the entry is a link. */
    public static final String PROOT_KEPT = "proot-static.data";
    public static final String ENV_PROOT_LOADER = "PROOT_LOADER";

    private NativeProot() {}

    /** Links the entry and exports PROOT_LOADER into {@code environment}; a no-op where the rule does not apply. */
    public static synchronized void apply(@NonNull Context context, @NonNull Map<String, String> environment) {
        try {
            if (!SystemLinkerExec.required()) return;
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            File proot = new File(nativeDir, NATIVE_PROOT);
            File loader = new File(nativeDir, NATIVE_PROOT_LOADER);
            if (!proot.isFile() || !loader.isFile()) {
                Logger.logError(LOG_TAG, "This APK carries no " + NATIVE_PROOT + " / " + NATIVE_PROOT_LOADER
                    + " in " + nativeDir + ": proot runs from the data directory, which Android refuses here");
                return;
            }
            environment.put(ENV_PROOT_LOADER, loader.getAbsolutePath());
            link(proot);
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not set up the native proot: " + t);
        }
    }

    /** {@code $PREFIX/bin/proot-static -> target}; the file that was there is renamed, never deleted. */
    private static void link(File target) throws ErrnoException {
        File bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!bin.isDirectory()) return;     // no bootstrap yet: applied again when the next command starts
        File entry = new File(bin, PROOT_ENTRY);
        String path = entry.getAbsolutePath();
        boolean isLink = false;
        boolean exists = true;
        try {
            isLink = OsConstants.S_ISLNK(Os.lstat(path).st_mode);
        } catch (ErrnoException e) {
            exists = false;
        }
        if (isLink && target.getAbsolutePath().equals(Os.readlink(path))) return;
        if (exists && !isLink) {
            // The bootstrap's (or nix-on-droid's) own proot: kept under another name.
            Os.rename(path, new File(bin, PROOT_KEPT).getAbsolutePath());
        }
        File tmp = new File(bin, PROOT_ENTRY + ".link");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        Os.symlink(target.getAbsolutePath(), tmp.getAbsolutePath());
        Os.rename(tmp.getAbsolutePath(), path);
        Logger.logInfo(LOG_TAG, path + " -> " + target);
    }
}
