package com.termux.shared.shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * #1708 Starting a program that lives in this app's data directory when the app targets SDK 29+.
 *
 * THE RULE. From Android 10 an app whose targetSdkVersion is 29 or higher runs in an SELinux
 * domain that may still READ and MAP (execute) its own data files but may no longer execve()
 * them (no execute_no_trans on app_data_file). Everything this terminal runs lives there: the
 * Termux bootstrap ($PREFIX/bin/*, hundreds of ELF binaries and #! scripts), ~/.termux/shell,
 * the user's own scripts. A plain exec of any of them is "Permission denied".
 *
 * THE METHOD is upstream Termux's own (termux-exec "system_linker_exec"): the dynamic linker is a
 * system file, so it may be exec'd, and since Android 10 it loads the program named by its first
 * argument: {@code /system/bin/linker64 $PREFIX/bin/bash -l} maps bash exactly as the kernel would
 * have. A #! script is started the way the kernel's binfmt_script would: its interpreter, the one
 * optional argument, then the script, and the interpreter in turn goes through the linker.
 *
 * WHO DOES WHAT. Inside a session the bootstrap's own libtermux-exec-ld-preload.so (termux-exec
 * 2.x, LD_PRELOAD) rewrites every execve() a program makes, so a shell, apt, dpkg, git and every
 * script keep working unchanged. This class covers the one exec that library cannot see: the
 * FIRST one, made by this app's own code (the JNI pty, Runtime.exec in TermuxTask and
 * CloudExecService), and tells the caller which library to put in LD_PRELOAD so the chain starts
 * intercepted.
 *
 * WHEN. Only when the rule applies: Android 10+ AND the process is not in a domain that still
 * may exec its data (untrusted_app_25 / untrusted_app_27, i.e. targetSdk <= 28, or a shared uid
 * whose lowest targetSdk is). The same test termux-exec makes, so both ends agree. Otherwise
 * every method here returns its input untouched and nothing changes.
 *
 * LIMITS, same as upstream's: a statically linked ELF in the data directory cannot be loaded by
 * the linker (it is left as is and fails as the kernel says; ship such a tool in the APK's native
 * library directory, which may be exec'd, as proot is), and the program sees the linker as
 * /proc/self/exe.
 *
 * No android.* import on purpose: test/test-system-linker-exec.sh compiles and runs this file
 * with a plain JDK.
 */
public final class SystemLinkerExec {

    private SystemLinkerExec() {}

    /** The primary LD_PRELOAD library of termux-exec 2.x, relative to $PREFIX, then the 1.x name. */
    private static final String[] PRELOAD_LIBS = {"lib/libtermux-exec-ld-preload.so", "lib/libtermux-exec.so"};

    private static final int ELF_HEADER_READ = 4096;
    /** The kernel reads at most this much of a #! line (BINPRM_BUF_SIZE). */
    private static final int SHEBANG_MAX = 256;
    private static final int MAX_DEPTH = 4;

    private static volatile Boolean sRequired;

    /** /system/bin/linker64 for a 64-bit process, /system/bin/linker for a 32-bit one. */
    public static String linker() {
        String arch = System.getProperty("os.arch", "");
        return arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker";
    }

    /** True when this process may not execve() its own data files and the linker can load them instead. */
    public static boolean required() {
        Boolean cached = sRequired;
        if (cached != null) return cached;
        boolean value = required(sdkInt(), readProcessContext());
        sRequired = value;
        return value;
    }

    /**
     * The decision, as a function of its two inputs. {@code seContext} null or empty = unknown
     * (SELinux absent or unreadable): the data files are then not restricted, as termux-exec assumes.
     */
    public static boolean required(int sdkInt, String seContext) {
        if (sdkInt < 29) return false;
        if (seContext == null || seContext.isEmpty()) return false;
        return !(seContext.startsWith("u:r:untrusted_app_25:") || seContext.startsWith("u:r:untrusted_app_27:"));
    }

    /**
     * The argv to exec for {@code argv} (argv[0] = the program's path). Unchanged unless
     * {@link #required()} and argv[0] resolves into one of {@code dataRoots}.
     *
     * @param dataRoots the app's data directory under every name it is reached by.
     * @param binDir    $PREFIX/bin: where a #! naming /bin/x or /usr/bin/x is looked up, and whose
     *                  sh runs a file that is neither ELF nor #!.
     */
    public static String[] wrap(String[] argv, String[] dataRoots, String binDir) {
        return wrap(argv, dataRoots, binDir, required(), linker());
    }

    /** {@link #wrap(String[], String[], String)} with the decision and the linker given (the tester's entry). */
    public static String[] wrap(String[] argv, String[] dataRoots, String binDir, boolean required, String linker) {
        if (!required || argv == null || argv.length == 0 || argv[0] == null) return argv;
        List<String> cur = new ArrayList<>(Arrays.asList(argv));
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            String program = cur.get(0);
            if (!isUnder(program, dataRoots)) break;
            Header header = Header.read(program);
            if (header == null) break;          // unreadable or empty: exec reports the real error
            if (header.elf) {
                if (header.dynamic) cur.add(0, linker);
                break;
            }
            if (depth == MAX_DEPTH) break;      // a #! chain this deep is a loop
            List<String> next = new ArrayList<>();
            if (header.interpreter != null) {
                next.add(mapInterpreter(header.interpreter, binDir));
                if (header.argument != null) next.add(header.argument);
            } else {
                next.add(binDir + "/sh");       // no #!: the bootstrap's sh, as before
            }
            next.addAll(cur);
            cur = next;
        }
        return cur.toArray(new String[0]);
    }

    /** /bin/x and /usr/.../x do not exist on Android: they mean $PREFIX/bin/x (the rule the app always had). */
    static String mapInterpreter(String interpreter, String binDir) {
        if (interpreter.startsWith("/usr/") || interpreter.startsWith("/bin/")) {
            return binDir + "/" + interpreter.substring(interpreter.lastIndexOf('/') + 1);
        }
        return interpreter;
    }

    /** True when {@code path}, with its symlinks resolved, lies inside one of {@code roots}. */
    static boolean isUnder(String path, String[] roots) {
        if (path == null || roots == null || !path.startsWith("/")) return false;
        String real;
        try {
            real = new File(path).getCanonicalPath();
        } catch (IOException e) {
            real = path;
        }
        for (String root : roots) {
            if (root == null || root.isEmpty()) continue;
            String r = root.endsWith("/") ? root : root + "/";
            String canonicalRoot;
            try {
                canonicalRoot = new File(root).getCanonicalPath() + "/";
            } catch (IOException e) {
                canonicalRoot = r;
            }
            if (real.startsWith(r) || real.startsWith(canonicalRoot)) return true;
        }
        return false;
    }

    /**
     * The library a session must carry in LD_PRELOAD so every exec inside it is rewritten, or
     * null when the bootstrap has none (not installed yet, or removed): a session then still
     * starts, and says "Permission denied" for what it cannot run.
     */
    public static String preloadLibrary(String prefixDir) {
        for (String rel : PRELOAD_LIBS) {
            File lib = new File(prefixDir, rel);
            if (lib.isFile() && lib.length() > 0 && Header.isElf(lib)) return lib.getAbsolutePath();
        }
        return null;
    }

    /** The data directory of {@code packageName} under every path Android reaches it by, for user {@code userId}. */
    public static String[] dataRoots(String packageName, String dataDir, int userId) {
        List<String> roots = new ArrayList<>();
        roots.add("/data/data/" + packageName);
        roots.add("/data/user/" + userId + "/" + packageName);
        if (dataDir != null && !dataDir.isEmpty() && !roots.contains(dataDir)) roots.add(dataDir);
        return roots.toArray(new String[0]);
    }

    private static int sdkInt() {
        try {
            return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        } catch (Throwable t) {
            return 0;   // not Android (the tester's JVM): nothing to work around
        }
    }

    /** /proc/self/attr/current, the process's SELinux context; null when it cannot be read. */
    private static String readProcessContext() {
        try (InputStream in = new FileInputStream("/proc/self/attr/current")) {
            byte[] buffer = new byte[256];
            int n = in.read(buffer);
            if (n <= 0) return null;
            while (n > 0 && (buffer[n - 1] == 0 || buffer[n - 1] == '\n')) n--;
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** What the first bytes of an executable say: a dynamic or static ELF, or a script and its #! line. */
    static final class Header {
        boolean elf;
        boolean dynamic;
        String interpreter;
        String argument;

        static boolean isElf(File file) {
            try (InputStream in = new FileInputStream(file)) {
                byte[] magic = new byte[4];
                return in.read(magic) == 4 && magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        static Header read(String path) {
            byte[] buffer = new byte[ELF_HEADER_READ];
            int n = 0;
            try (InputStream in = new FileInputStream(path)) {
                while (n < buffer.length) {
                    int r = in.read(buffer, n, buffer.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
            if (n < 2) return null;
            Header h = new Header();
            if (n >= 4 && buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                h.elf = true;
                h.dynamic = hasInterpSegment(buffer, n);
                return h;
            }
            if (buffer[0] == '#' && buffer[1] == '!') {
                int end = 2;
                int limit = Math.min(n, SHEBANG_MAX);
                while (end < limit && buffer[end] != '\n') end++;
                String line = new String(buffer, 2, end - 2, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    int space = indexOfBlank(line);
                    if (space < 0) {
                        h.interpreter = line;
                    } else {
                        h.interpreter = line.substring(0, space);
                        String rest = line.substring(space).trim();
                        if (!rest.isEmpty()) h.argument = rest;
                    }
                }
            }
            return h;
        }

        private static int indexOfBlank(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t') return i;
            }
            return -1;
        }

        /** PT_INTERP present = the kernel would have started a dynamic linker for it, so ours can load it. */
        private static boolean hasInterpSegment(byte[] b, int n) {
            if (n < 0x40) return false;
            boolean is64 = b[4] == 2;
            boolean little = b[5] != 2;
            long phoff = is64 ? num(b, 0x20, 8, little) : num(b, 0x1C, 4, little);
            int phentsize = (int) num(b, is64 ? 0x36 : 0x2A, 2, little);
            int phnum = (int) num(b, is64 ? 0x38 : 0x2C, 2, little);
            if (phentsize <= 0 || phnum <= 0) return false;
            for (int i = 0; i < phnum; i++) {
                long at = phoff + (long) i * phentsize;
                if (at < 0 || at + 4 > n) return true;     // headers beyond what was read: assume dynamic
                if (num(b, (int) at, 4, little) == 3) return true;   // PT_INTERP
            }
            return false;
        }

        private static long num(byte[] b, int off, int len, boolean little) {
            long v = 0;
            for (int i = 0; i < len; i++) {
                int idx = little ? off + len - 1 - i : off + i;
                v = (v << 8) | (b[idx] & 0xFF);
            }
            return v;
        }
    }
}
