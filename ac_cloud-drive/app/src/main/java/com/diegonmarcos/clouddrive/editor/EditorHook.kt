package com.diegonmarcos.clouddrive.editor

import com.diegonmarcos.cloudlib.editorcore.FileTypeRouter
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #1484 how a screen outside the Editor opens a file in it (Explorer's row menu): [open] queues the path and asks the shell
 * for the Editor tab, [pending] is what EditorScreen takes. Whether the Editor can show a file is editor-core's data table.
 */
object EditorHook {
    val pending = MutableStateFlow<String?>(null)

    /** Set by MainActivity: selects the Editor tab. */
    var jump: (() -> Unit)? = null

    val router: FileTypeRouter by lazy { FileTypeRouter.default() }

    fun canOpen(name: String): Boolean = router.route(name).supported

    fun open(path: String) {
        pending.value = path
        jump?.invoke()
    }
}
