package com.termux.cloud;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stages the glibc root filesystem this terminal runs (#470) and makes it the
 * terminal's login shell.
 *
 * #628: the ~400 MB tree is no longer fetched over the network (#618's
 * runtime download is gone). It now ships inside a SIBLING APK — the fleet
 * library com.diegonmarcos.cloudlib.rootfstermux (cloud-lib-rootfs-termux),
 * installed and updated by the Store exactly like any other library. This app
 * carries only proot and enter.sh in its own assets/&lt;asset_dir&gt;/; the
 * tarball is read straight out of the lib APK — assets/rootfs-lib.json for its
 * manifest, assets/rootfs.tar.zst for the payload — via {@link ZipFile}, and
 * streamed into $PREFIX/var/lib/&lt;asset_dir&gt;/. Nothing here opens a socket.
 *
 * Trust chain: the lib must be signed with the SAME key as this app
 * ({@code checkSignatures == SIGNATURE_MATCH}) before a single byte is read
 * out of it — unpacking ~400 MB of executables out of an APK signed by
 * somebody else is exactly the "marking unread bytes executable" hazard #348
 * fought elsewhere in this app. The lib's own manifest sha256 is then the
 * digest the extracted tarball is gated on, exactly as #618's fetch used to be
 * gated on a baked digest — only the transport changed.
 *
 * Everything is extracted to $PREFIX/var/lib/&lt;asset_dir&gt;/ whenever the lib's
 * declared digest differs from the staged one, so a fresh install AND an
 * update of an existing install both get the new tree; existing installs
 * matter because the Termux bootstrap only ever runs once (#436).
 *
 * The first time anything is staged on a device, ~/.termux/shell is pointed at
 * enter.sh — that mechanism is unchanged by #628.
 */
public final class CloudRootfs {

    private static final String LOG_TAG = "CloudRootfs";
    private static final String DIGEST = "rootfs.sha256";
    private static final String TARBALL = "rootfs.tar.zst";
    /** The manifest {@link #stageRootfsPayload} (app/build.gradle) writes beside the payload inside :rootfs-lib. */
    private static final String MANIFEST_ENTRY = "assets/rootfs-lib.json";
    /** The small files this app still carries in its own assets/ — the tarball does not, any more. */
    private static final String[] FILES = {"proot", "enter.sh"};
    /**
     * #797 The directory of small files beside them, staged by rootfs/build-rootfs.sh: the #644 link
     * store (engine, rendered declaration, login wiring) and the pty selftest the phone runs
     * (ab_cloud-terminal-store/pty-check + pty-selftest.json). enter.sh binds it onto
     * /usr/lib/cloud-store and enters through its login-exec, which sources login-init.sh: the
     * store's PATH and #790's agent credentials. Enumerated from the APK rather than named here,
     * so a file build-rootfs.sh adds ships without a second list. Until #797 nothing copied it:
     * enter.sh found no cloud-store beside itself and, by design, left the store unwired -- so on
     * every termux phone login-init.sh never ran, while CI (verify-rootfs.sh) staged the directory
     * itself and stayed green.
     */
    private static final String STORE_DIR = "cloud-store";

    private CloudRootfs() {}

    /**
     * Thrown when com.diegonmarcos.cloudlib.rootfstermux is not installed, so the
     * caller can tell this apart from every other failure and offer the Store
     * deep link instead of a generic error (see TermuxInstaller#stageCloudRootfs).
     */
    public static final class LibMissing extends IOException {
        public LibMissing(String message) { super(message); }
    }

    private static File stageDir() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/" + BuildConfig.CLOUD_ROOTFS_ASSET_DIR);
    }

    /**
     * The directory enter.sh lives in and binds files relative to (where proot,
     * enter.sh and the #644 cloud-store are staged). RishBridge.export writes the
     * rish launcher + its env file into a {@code rish/} subdir here so enter.sh
     * can bind them into the rootfs.
     */
    public static File loginDir() {
        return stageDir();
    }

    /**
     * Cheap enough for the UI thread: opens the lib's ZipFile only to read its
     * small manifest entry (a few dozen bytes) — never the ~400 MB payload entry.
     */
    public static boolean isStaged(Context context) {
        try {
            File staged = new File(stageDir(), DIGEST);
            if (!staged.isFile()) return false;
            String want = field(libManifest(context), "sha256");
            return want.equals(read(new FileInputStream(staged))) && isDelivered(want);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * #1436 the digest file alone is not a staged rootfs. enter.sh deletes rootfs.tar.zst once it has unpacked it, so a
     * tree whose stamp then went stale or missing (an unpack killed mid-way, a wiped rootfs/) left rootfs.sha256 naming
     * the lib's digest with NOTHING to enter: isStaged() said yes, stage() returned early on every start, and every
     * login fell back to plain Termux ("rootfs.tar.zst is missing and no unpacked rootfs matches"). Delivered = the
     * tarball enter.sh would unpack is here, or the unpacked tree carries {@code want}. Two stats, UI-thread cheap.
     */
    static boolean isDelivered(String want) {
        if (new File(stageDir(), TARBALL).isFile()) return true;
        try {
            return want.equals(read(new FileInputStream(new File(stageDir(), "rootfs/.cloud-rootfs.sha256"))));
        } catch (IOException e) {
            return false;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REPAIRING = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * #1436 the automatic repair, no user involved: CloudExecService calls this before every command another app runs
     * here (Cloud Drive's git jobs never open this app's activity, the only other place {@link #stage} ran). When the
     * rootfs is not delivered it is re-extracted from the installed cloud-lib-rootfs-termux on a background thread
     * (single flight); the command itself runs at once, in plain Termux if need be, which the jobs handle
     * (cloud-exec-env.sh). The next login unpacks the re-staged tarball.
     */
    public static void repairInBackground(Context context) {
        if (isStaged(context) || !REPAIRING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        new Thread(() -> {
            try {
                Logger.logInfo(LOG_TAG, "The rootfs is not delivered: re-extracting it from cloud-lib-rootfs-termux");
                stage(app);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Rootfs repair failed: " + t);
            } finally {
                REPAIRING.set(false);
            }
        }, "cloud-rootfs-repair").start();
    }

    /**
     * #747: true once enter.sh has unpacked the staged tarball, i.e. its stamp inside the tree names
     * the digest staged beside it (the same comparison enter.sh makes before it unpacks). Until then
     * the next login spends minutes unpacking before it runs anything.
     */
    public static boolean isUnpacked() {
        try {
            String want = read(new FileInputStream(new File(stageDir(), DIGEST)));
            return want.equals(read(new FileInputStream(new File(stageDir(), "rootfs/.cloud-rootfs.sha256"))));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * #786: WHICH builds this terminal is running, in the units the release publishes, so a
     * selftest answer can be laid beside the release instead of trusted. #771's checks were
     * reported missing on a phone whose cld.termux said versionCode 1011, the same as the
     * published APK; that number never moves, and the APK on the phone predated #771. Here:
     * this APK's own sha256 (= the release's cloud-terminal*.apk.sha256), the installed lib's
     * versionCode and manifest (version = the fleet row's version_name), and the digest
     * staged out of it and unpacked by enter.sh. All three digests equal = adopted.
     */
    public static JSONObject installed(Context context) throws JSONException {
        JSONObject out = new JSONObject();
        try {
            out.put("app_apk_sha256", sha256(new File(context.getApplicationInfo().sourceDir)));
        } catch (IOException e) {
            out.put("app_apk_sha256", JSONObject.NULL).put("app_error", e.toString());
        }
        try {
            //noinspection deprecation — minSdk 26; getLongVersionCode is API 28
            int code = context.getPackageManager().getPackageInfo(BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE, 0).versionCode;
            JSONObject manifest = libManifest(context);
            out.put("lib_version_code", code)
                .put("lib_version", field(manifest, "version"))
                .put("lib_sha256", field(manifest, "sha256"));
        } catch (Exception e) {
            out.put("lib_error", e.getMessage());
        }
        out.put("staged_sha256", readOrNull(new File(stageDir(), DIGEST)))
            .put("unpacked_sha256", readOrNull(new File(stageDir(), "rootfs/.cloud-rootfs.sha256")));
        return out;
    }

    private static Object readOrNull(File file) {
        try {
            return read(new FileInputStream(file));
        } catch (IOException e) {
            return JSONObject.NULL;
        }
    }

    /**
     * Extracts ~400 MB out of the sibling lib APK the first time and after a rootfs change; call off the UI thread.
     *
     * #747: synchronized and re-checked, because the activity's first start and the debug API's
     * /api/terminal/exec can both arrive here on a fresh install. The second caller waits for the
     * first and finds the tree staged instead of extracting it a second time on top of the first.
     */
    public static synchronized void stage(Context context) throws IOException, ErrnoException {
        if (isStaged(context)) {
            refreshFiles(context);
            return;
        }
        File dir = stageDir();
        boolean firstEver = !new File(dir, DIGEST).exists();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        refreshFiles(context);

        String libSourceDir = trustedLibSourceDir(context);
        JSONObject manifest = readManifest(libSourceDir);
        String want = field(manifest, "sha256");
        String payload = field(manifest, "payload");

        extractPayload(libSourceDir, payload, want, dir);

        // LAST, and only now: enter.sh unpacks exactly when this file disagrees
        // with what it already unpacked, so writing it before a verified tarball
        // is in place would tell it to unpack bytes that are not there.
        try (OutputStream out = new FileOutputStream(new File(dir, DIGEST))) {
            out.write((want + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Logger.logInfo(LOG_TAG, "Staged the rootfs " + want + " into " + dir);

        if (firstEver) {
            File termuxDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux");
            if (!termuxDir.isDirectory() && !termuxDir.mkdirs()) throw new IOException("cannot create " + termuxDir);
            File shell = new File(termuxDir, "shell");
            if (shell.exists() || isSymlink(shell)) //noinspection ResultOfMethodCallIgnored
                shell.delete();
            Os.symlink(new File(dir, "enter.sh").getAbsolutePath(), shell.getAbsolutePath());
            Logger.logInfo(LOG_TAG, "Login shell is now " + shell + " -> enter.sh (delete it to return to bash)");
        }
    }

    /**
     * #736: copies this APK's own {@link #FILES} over the staged ones whenever they differ, and
     * is called on EVERY start, not only when the lib's rootfs digest moves. Gated on that digest
     * alone, an update that changed only enter.sh never reached a phone that had already staged
     * the rootfs: it kept logging in through the enter.sh of its first install. Each file is
     * written beside its target and renamed over it, so a session still running the old proot
     * keeps its inode instead of failing on a half-written binary.
     */
    public static synchronized void refreshFiles(Context context) throws IOException, ErrnoException {
        File dir = stageDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(FILES));
        String[] store = context.getAssets().list(BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + STORE_DIR);
        if (store == null || store.length == 0)
            throw new IOException("this APK carries no assets/" + BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + STORE_DIR
                + ": build-rootfs.sh did not stage the link store, and app/build.gradle should have refused the build");
        for (String name : store) names.add(STORE_DIR + "/" + name);
        for (String name : names) {
            byte[] want;
            try (InputStream in = context.getAssets().open(BuildConfig.CLOUD_ROOTFS_ASSET_DIR + "/" + name)) {
                want = bytes(in);
            }
            File target = new File(dir, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            if (target.isFile() && java.util.Arrays.equals(want, bytes(new FileInputStream(target)))) continue;
            File tmp = new File(dir, name + ".new");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(want);
            }
            //noinspection OctalInteger
            Os.chmod(tmp.getAbsolutePath(), 0700);
            Os.rename(tmp.getAbsolutePath(), target.getAbsolutePath());
            Logger.logInfo(LOG_TAG, "Refreshed " + target + " from this APK");
        }
        linkNative(context);
    }

    /** The links enter.sh follows to the proot and the loader this APK ships as native libraries. */
    static final String NATIVE_PROOT_LINK = "native-proot";
    static final String NATIVE_LOADER_LINK = "native-proot-loader";

    /**
     * #1708: points {@code native-proot} and {@code native-proot-loader} (beside enter.sh) at
     * libcloudproot.so and libcloudprootloader.so in this APK's native library directory.
     *
     * At targetSdk 29+ an app may not execve() a file in its data directory, and the staged
     * {@code proot} above is one, as is the loader it extracts into its temp directory for every
     * guest program. The native library directory is the one place an app may exec from, so the
     * same two binaries ship there (app/build.gradle stageNativeProot: the very proot
     * build-rootfs.sh staged, and the loader carved out of it). enter.sh runs them when both
     * links resolve and falls back to the staged proot otherwise.
     *
     * Only where the rule applies ({@link com.termux.shared.shell.SystemLinkerExec#required()}):
     * anywhere else (Android 9 and older, or a process still in a domain that may exec its
     * data) the links are removed and the terminal runs the staged proot exactly as it always
     * did.
     *
     * The directory moves with every app update, so this runs on every process start
     * (TermuxApplication) and whenever the files are refreshed. It never creates the stage
     * directory (an empty $PREFIX is how the installer knows the bootstrap is missing) and
     * never throws: a terminal whose links could not be written still opens.
     */
    public static synchronized void linkNative(Context context) {
        try {
            File dir = stageDir();
            if (!dir.isDirectory()) return;
            boolean on = com.termux.shared.shell.SystemLinkerExec.required();
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            File proot = new File(nativeDir, com.termux.shared.shell.TermuxShellUtils.NATIVE_PROOT);
            File loader = new File(nativeDir, com.termux.shared.shell.TermuxShellUtils.NATIVE_PROOT_LOADER);
            boolean both = on && proot.isFile() && loader.isFile();
            link(both ? proot : null, new File(dir, NATIVE_PROOT_LINK));
            link(both ? loader : null, new File(dir, NATIVE_LOADER_LINK));
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not link the native proot: " + t);
        }
    }

    /** {@code link -> target}, replaced atomically when it names something else; a null target removes the link. */
    private static void link(File target, File link) throws ErrnoException {
        String current = null;
        try {
            current = Os.readlink(link.getAbsolutePath());
        } catch (ErrnoException e) {
            // Not a link (or not there): replaced below.
        }
        if (target == null) {
            if (current != null) //noinspection ResultOfMethodCallIgnored
                link.delete();
            return;
        }
        if (target.getAbsolutePath().equals(current)) return;
        File tmp = new File(link.getParentFile(), link.getName() + ".new");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        Os.symlink(target.getAbsolutePath(), tmp.getAbsolutePath());
        Os.rename(tmp.getAbsolutePath(), link.getAbsolutePath());
        Logger.logInfo(LOG_TAG, link + " -> " + target);
    }

    private static byte[] bytes(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = src.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Resolves com.diegonmarcos.cloudlib.rootfstermux and refuses it unless it is
     * signed with this app's own key. #628's trust boundary: this app is about
     * to unpack and exec ~400 MB out of another APK's assets, and only a
     * matching signature makes that APK part of the same constellation build
     * rather than an unrelated package somebody else installed under the id
     * this app expects to trust.
     */
    private static String trustedLibSourceDir(Context context) throws IOException {
        PackageManager pm = context.getPackageManager();
        String libPackage = BuildConfig.CLOUD_ROOTFS_LIB_PACKAGE;
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(libPackage, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new LibMissing("cloud-lib-rootfs-termux (" + libPackage + ") is not installed — " +
                "install it from the Store's Cloud tab to enable the agent-coding shell");
        }
        //noinspection deprecation
        if (pm.checkSignatures(context.getPackageName(), libPackage) != PackageManager.SIGNATURE_MATCH) {
            throw new IOException("cloud-lib-rootfs-termux (" + libPackage + ") is signed with a different key " +
                "than this app — refusing to unpack it");
        }
        return info.sourceDir;
    }

    /** Reads and validates {@link #MANIFEST_ENTRY} out of the lib APK at {@code libSourceDir}. */
    private static JSONObject readManifest(String libSourceDir) throws IOException {
        try (ZipFile zip = new ZipFile(libSourceDir)) {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null)
                throw new IOException("cloud-lib-rootfs-termux carries no " + MANIFEST_ENTRY + " — reinstall it from the Store");
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = read(in);
            }
            JSONObject json = new JSONObject(text);
            if (!json.has("version") || !json.has("payload") || !json.has("sha256"))
                throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY + " is missing version/payload/sha256 — reinstall it from the Store");
            return json;
        } catch (JSONException e) {
            throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY + " is not valid JSON", e);
        }
    }

    /**
     * One manifest field, with org.json's CHECKED JSONException translated at the
     * boundary. Not a convenience: every caller here declares IOException, and a
     * bare getString would not compile.
     */
    private static String field(JSONObject manifest, String name) throws IOException {
        try {
            return manifest.getString(name);
        } catch (JSONException e) {
            throw new IOException("cloud-lib-rootfs-termux's " + MANIFEST_ENTRY
                + " has no usable " + name + " — reinstall it from the Store", e);
        }
    }

    /** {@link #isStaged} only ever reads this — never {@link #extractPayload}'s ~400 MB entry. */
    private static JSONObject libManifest(Context context) throws IOException {
        return readManifest(trustedLibSourceDir(context));
    }

    /**
     * #618's guard, moved: fetch became extract, but the property is the same —
     * REFUSE the payload unless it hashes to {@code want}. A mismatch throws, so
     * the old digest stays on disk, enter.sh keeps using the tree it already
     * unpacked, and the next start tries again.
     *
     * A tarball already on disk that hashes correctly is kept: an interrupted
     * update resumes without extracting another 400 MB.
     */
    private static void extractPayload(String libSourceDir, String payload, String want, File dir) throws IOException {
        File tarball = new File(dir, TARBALL);
        if (tarball.isFile() && want.equals(sha256(tarball))) {
            Logger.logInfo(LOG_TAG, "The extracted rootfs " + want + " is already here");
            return;
        }

        File part = new File(dir, TARBALL + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        Logger.logInfo(LOG_TAG, "Extracting the rootfs " + want + " from cloud-lib-rootfs-termux");

        try (ZipFile zip = new ZipFile(libSourceDir)) {
            ZipEntry entry = zip.getEntry("assets/" + payload);
            if (entry == null)
                throw new IOException("cloud-lib-rootfs-termux carries no assets/" + payload + " — reinstall it from the Store");
            MessageDigest digest = sha256();
            try (InputStream in = new DigestInputStream(zip.getInputStream(entry), digest);
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            String got = hex(digest.digest());
            if (!want.equals(got)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("the rootfs extracted from cloud-lib-rootfs-termux is sha256 " + got
                    + ", not the " + want + " this build was signed off against");
            }
        }
        //noinspection ResultOfMethodCallIgnored
        tarball.delete();
        if (!part.renameTo(tarball)) throw new IOException("cannot move " + part + " to " + tarball);
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(new FileInputStream(file), digest)) {
            byte[] buffer = new byte[1 << 16];
            //noinspection StatementWithEmptyBody
            while (in.read(buffer) != -1) { }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }

    private static boolean isSymlink(File file) {
        try {
            return android.system.OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
}
