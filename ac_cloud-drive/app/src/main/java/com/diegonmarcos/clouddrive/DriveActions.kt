package com.diegonmarcos.clouddrive

/**
 * #579 what a screen may ask the Activity to do. The screens are Compose functions
 * with no Activity in hand; MainActivity implements this over its result launchers,
 * the FileProvider and the system intents. One seam, so a screen never builds an
 * Intent and a tester can grep every hand-off in one place.
 */
interface DriveActions {
    fun requestStorageAccess()
    fun requestTreeGrant()
    /** One of EngineActivity's ids (git, editor, rclone, mounts); [url] only for a git clone. */
    fun openEngine(engine: String, target: String, url: String = "")
    fun openImage(path: String, siblings: List<String>)
    fun openPdf(path: String)
    /** ACTION_VIEW through the FileProvider — whatever app claims the type. */
    fun openWith(path: String)
    fun share(paths: List<String>)
    fun shareText(title: String, text: String)
    fun copyText(text: String)
    fun openUrl(url: String)
    /** Launch an installed package, or its fallback URL; false when neither is possible. */
    fun launchApp(packageName: String, fallbackUrl: String): Boolean
    /** #613 deep-link to the fleet's auth Profile (cloud-sa): launch [pkg] carrying [target] as the [extra]. False when it is not installed. */
    fun openAuthProfile(pkg: String, target: String, extra: String): Boolean
    /** #1289 reveal [path] in Explorer ▸ Local (Drive Files). Default no-op for a host without the Files tab. */
    fun openInFiles(path: String) {}
    /** #1607 copy each path (a file, or a folder tree) into the device's public Downloads via MediaStore. Default no-op. */
    fun copyToDownload(paths: List<String>) {}
}
