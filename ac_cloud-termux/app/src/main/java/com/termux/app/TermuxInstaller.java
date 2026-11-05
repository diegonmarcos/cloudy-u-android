package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.cloud.CloudRootfs;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.models.errors.Error;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        if (isPrefixInstalled()) {
            stageCloudRootfs(activity, whenDone);
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    synchronized (INSTALL_LOCK) {
                        // #747: the debug API may have installed it while this activity waited.
                        if (!isPrefixInstalled()) installBootstrap(activity);
                    }
                    activity.runOnUiThread(() -> stageCloudRootfs(activity, whenDone));

                } catch (final BootstrapFailure e) {
                    showBootstrapErrorDialog(activity, whenDone, e.getMessage());

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /** #747: held by every bootstrap install, the activity's and the debug API's alike, so two can never extract into one $PREFIX. */
    private static final Object INSTALL_LOCK = new Object();

    /** A bootstrap step that failed for a stated reason; the message is the markdown the error dialog shows. */
    static final class BootstrapFailure extends Exception {
        BootstrapFailure(String markdown) { super(markdown); }
    }

    /**
     * #747 the debug API's bootstrap: what the activity's first start does — the Termux bootstrap,
     * then the rootfs staged out of the companion lib — blocking and with no UI, so
     * /api/terminal/exec works on a phone where nobody has opened the terminal yet. Every failure
     * is thrown with its reason; nothing is shown.
     */
    static void ensureInstalled(Context context) throws Exception {
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, true, true);
        if (error != null) throw new BootstrapFailure(Error.getMinimalErrorString(error));
        if (!PackageUtils.isCurrentUserThePrimaryUser(context))
            throw new BootstrapFailure(context.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false)));
        synchronized (INSTALL_LOCK) {
            if (!isPrefixInstalled()) installBootstrap(context);
        }
        CloudRootfs.stage(context);
    }

    /** True when $PREFIX holds an installed bootstrap: a directory (or a link to one) with more in it than tmp/. */
    private static boolean isPrefixInstalled() {
        // A $PREFIX with no executable bin/login is NOT an installed bootstrap, whatever else is in it.
        // TermuxApplication.onCreate exports rish into $PREFIX/var/lib/<rootfs>/rish on EVERY launch,
        // the first one included, so on a fresh install $PREFIX held var/ before the bootstrap ran:
        // the old "more than tmp/" test said installed, the bootstrap was skipped, and every session
        // fell back to /system/bin/sh with PATH=$PREFIX/bin, where nothing (not even ls) is found.
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "login").canExecute()) {
            if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false))
                Logger.logInfo(LOG_TAG, "The termux prefix \"" + TERMUX_PREFIX_DIR_PATH + "\" has no executable bin/login: (re)installing the bootstrap.");
            return false;
        }
        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            File[] PREFIX_FILE_LIST =  TERMUX_PREFIX_DIR.listFiles();
            // If prefix directory is empty or only contains the tmp directory
            if(PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.length == 0 || (PREFIX_FILE_LIST.length == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath()))) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.");
            } else {
                return true;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }
        return false;
    }

    /** Extracts the bootstrap into $PREFIX and runs its second stage. Call with INSTALL_LOCK held, off the UI thread. */
    private static void installBootstrap(Context context) throws Exception {
        Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

        Error error;

        // Delete prefix staging directory or any file at its destination
        error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
        if (error != null) {
            throw new BootstrapFailure(Error.getErrorMarkdownString(error));
        }

        // Delete prefix directory or any file at its destination
        error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
        if (error != null) {
            throw new BootstrapFailure(Error.getErrorMarkdownString(error));
        }

        // Create prefix staging directory if it does not already exist and set required permissions
        error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
        if (error != null) {
            throw new BootstrapFailure(Error.getErrorMarkdownString(error));
        }

        // Create prefix directory if it does not already exist and set required permissions
        error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
        if (error != null) {
            throw new BootstrapFailure(Error.getErrorMarkdownString(error));
        }

        Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

        final byte[] buffer = new byte[8096];
        final List<Pair<String, String>> symlinks = new ArrayList<>(50);

        final byte[] zipBytes = loadZipBytes();
        try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String oldPath = parts[0];
                        String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                        symlinks.add(Pair.create(oldPath, newPath));

                        error = ensureDirectoryExists(new File(newPath).getParentFile());
                        if (error != null) {
                            throw new BootstrapFailure(Error.getErrorMarkdownString(error));
                        }
                    }
                } else {
                    String zipEntryName = zipEntry.getName();
                    File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                    boolean isDirectory = zipEntry.isDirectory();

                    error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                    if (error != null) {
                        throw new BootstrapFailure(Error.getErrorMarkdownString(error));
                    }

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") ||
                            zipEntryName.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")) {
                            //noinspection OctalInteger
                            Os.chmod(targetFile.getAbsolutePath(), 0700);
                        }
                    }
                }
            }
        }

        if (symlinks.isEmpty())
            throw new RuntimeException("No SYMLINKS.txt encountered");
        for (Pair<String, String> symlink : symlinks) {
            Os.symlink(symlink.first, symlink.second);
        }

        Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

        if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
            throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
        }

        // Run Termux bootstrap second stage.
        String termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh";
        if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
            Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since script not found at \"" + termuxBootstrapSecondStageFile + "\" path.");
        } else {
            if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since bash not found.");
            }
            Logger.logInfo(LOG_TAG, "Running Termux bootstrap second stage.");

            ExecutionCommand executionCommand = new ExecutionCommand(-1,
                termuxBootstrapSecondStageFile, null, null,
                null, true, false);
            executionCommand.commandLabel = "Termux Bootstrap Second Stage Command";
            executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
            TermuxTask termuxTask = TermuxTask.execute(context, executionCommand, null, new TermuxShellEnvironmentClient(), true);
            if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0) {
                // #747 the report is the command's own result, taken before the delete below.
                String report = MarkdownUtils.getMarkdownCodeForString(executionCommand.toString(), true);

                // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                if (error != null)
                    Logger.logErrorExtended(LOG_TAG, error.toString());
                throw new BootstrapFailure(report);
            }
        }

        Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
        // Installing replaced $PREFIX, and with it the rish export TermuxApplication made at launch.
        try {
            com.diegonmarcos.superapp.shellclient.ShellRish.export(context, CloudRootfs.loginDir());
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Re-exporting rish after the bootstrap failed", e);
        }
    }

    /**
     * #470: extract the glibc rootfs out of the cloud-lib-rootfs-termux companion
     * APK (#628) next to the prefix when its digest moved, on EVERY start and not
     * only on first bootstrap, so an update reaches phones that installed this app
     * before the rootfs existed. A failure is never swallowed silently: the lib
     * being absent shows a dialog with a deep link to the Store's Cloud tab
     * (CloudRootfs.LibMissing), and any other failure is logged AND shown, so
     * there is no case where the shell silently never got its agent-coding root.
     * Either way the terminal still opens: enter.sh falls back to bash when its
     * files are missing, and the failsafe session never touches them.
     */
    private static void stageCloudRootfs(final Activity activity, final Runnable whenDone) {
        if (CloudRootfs.isStaged(activity)) {
            // #736: the rootfs is current, but this APK's enter.sh/proot may be newer than
            // the staged copies; a few small file compares, so the UI thread is fine.
            try {
                CloudRootfs.refreshFiles(activity);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Refreshing enter.sh/proot from this APK failed", e);
            }
            whenDone.run();
            return;
        }
        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                Exception failure = null;
                try {
                    CloudRootfs.stage(activity);
                } catch (Exception e) {
                    failure = e;
                    Logger.logStackTraceWithMessage(LOG_TAG, "Staging the cloud-lib-rootfs-termux payload failed", e);
                } finally {
                    final Exception f = failure;
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                        if (f instanceof CloudRootfs.LibMissing) {
                            showCloudRootfsLibMissingDialog(activity, f.getMessage(), whenDone);
                        } else if (f != null) {
                            showCloudRootfsStageErrorDialog(activity, f.getMessage(), whenDone);
                        } else {
                            whenDone.run();
                        }
                    });
                }
            }
        }.start();
    }

    /**
     * #628: cloud-lib-rootfs-termux is not installed. Offers the Store's Cloud
     * tab via the superapp's launcher deep link (the same "shortcut_action"
     * vocabulary every constellation deep link uses) and, either way, a
     * "Continue" that opens the terminal into plain bash — the shell must never
     * be trapped behind this dialog.
     */
    private static void showCloudRootfsLibMissingDialog(Activity activity, String message, Runnable whenDone) {
        try {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.cloud_rootfs_lib_missing_title)
                .setMessage(activity.getString(R.string.cloud_rootfs_lib_missing_body) +
                    (message != null ? "\n\n" + message : ""))
                .setPositiveButton(R.string.cloud_rootfs_lib_missing_install, (dialog, which) -> {
                    dialog.dismiss();
                    openCloudStore(activity);
                    whenDone.run();
                })
                .setNegativeButton(R.string.cloud_rootfs_lib_missing_continue, (dialog, which) -> {
                    dialog.dismiss();
                    whenDone.run();
                })
                .setOnCancelListener(dialog -> whenDone.run())
                .show();
        } catch (WindowManager.BadTokenException e) {
            // Activity already dismissed - ignore, but never leave the terminal unopened.
            whenDone.run();
        }
    }

    /** #628: any OTHER staging failure — logged above already, and shown here so nothing fails silently. */
    private static void showCloudRootfsStageErrorDialog(Activity activity, String message, Runnable whenDone) {
        try {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.cloud_rootfs_stage_error_title)
                .setMessage(activity.getString(R.string.cloud_rootfs_stage_error_body, message))
                .setPositiveButton(R.string.cloud_rootfs_stage_error_continue, (dialog, which) -> {
                    dialog.dismiss();
                    whenDone.run();
                })
                .setOnCancelListener(dialog -> whenDone.run())
                .show();
        } catch (WindowManager.BadTokenException e) {
            whenDone.run();
        }
    }

    /**
     * The superapp's own deep-link vocabulary: an explicit Intent naming its
     * launcher activity and a "shortcut_action" extra, the same mechanism every
     * other constellation deep link into the Store uses.
     */
    private static void openCloudStore(Activity activity) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                .setClassName("com.diegonmarcos.superapp", "com.diegonmarcos.superapp.HomeActivity")
                .putExtra("shortcut_action", "page:config/store-cloud")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not open the SuperApp's Cloud store", e);
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            "## Bootstrap Error\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, true);
    }

    /**
     * #736: the upstream ~/storage tree (shared, downloads, dcim, ...) is no longer made. This
     * terminal's ONE entry for shared storage is ~/emulated, linked by the login at every session
     * start together with ~/cloud-shared-store; a second tree beside it was a duplicate view
     * of the same storage, and the login removes the one an earlier version left.
     */
    static void setupStorageSymlinks(final Context context) {
        Logger.logInfo("termux-storage", "Shared storage is ~/emulated, linked at session start; ~/storage is not created.");
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
