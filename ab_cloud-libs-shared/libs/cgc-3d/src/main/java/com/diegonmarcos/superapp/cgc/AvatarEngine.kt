package com.diegonmarcos.superapp.cgc

/**
 * An avatar creation engine: what a model source can be edited with. The editor asks the active engine which category kinds it
 * draws (a kind it lacks shows "coming soon") and which model formats it loads. Every engine is open source end to end: cgc.js
 * (three.js, @pixiv/three-vrm, GLTFLoader / FBXLoader, GLTFExporter) draws the scene, no proprietary SDK is involved.
 *
 * The pipeline (documented in the lib README and the ticket):
 *  1. [ModelFileEngine] ("model-file", shipping): the user's own GLB / glTF / FBX / VRM (for example a MetaPerson Creator export,
 *     converted offline by tools/ to a meshopt-compressed GLB). Edits: bundled clips and Mixamo-style retargeted clips, material
 *     colour swaps and tints, show/hide of named meshes (glasses, outfit parts), ARKit-style blendshapes. Body and head shape,
 *     hair and outfit swaps need parts the file does not carry.
 *  2. [VrmModularEngine] ("vrm-modular", the open-source creation path, not available yet): a CC0 VRM base body plus modular VRM
 *     parts (hair, outfits, accessories: VRoid Studio exports or CC0 parts) bound to the base skeleton, body shape via the base's
 *     blendshapes. When its parts library is bundled, [available] turns true and its extra kinds light up.
 *  3. AI on top of either: OpenRouterAvatar turns "describe your avatar" text into a configuration of declared options only.
 */
interface AvatarEngine {
    val id: String
    /** Model file kinds it loads (cgc.js loaders). */
    val formats: Set<String>
    /** Category kinds (CgcSpec.KIND_*) it draws. */
    val kinds: Set<String>
    /** False while its assets or code are not bundled: the editor then treats every category as coming soon. */
    val available: Boolean

    fun loads(url: String): Boolean = url.substringBefore('?').substringAfterLast('.', "").lowercase() in formats
}

object ModelFileEngine : AvatarEngine {
    override val id = "model-file"
    override val formats = setOf("glb", "gltf", "vrm", "fbx")
    override val kinds = setOf(CgcSpec.KIND_CLIP, CgcSpec.KIND_COLOR, CgcSpec.KIND_TINT, CgcSpec.KIND_VISIBLE, CgcSpec.KIND_MORPH,
        CgcSpec.KIND_BACKGROUND)
    override val available = true
}

object VrmModularEngine : AvatarEngine {
    override val id = "vrm-modular"
    override val formats = setOf("vrm", "glb")
    /** Adds part swaps and body shape to everything a model file can do. */
    override val kinds = ModelFileEngine.kinds + setOf("parts", "shape")
    override val available = false
}

object AvatarEngines {
    val all: List<AvatarEngine> = listOf(ModelFileEngine, VrmModularEngine)

    /** The engine [id] names when it is available, else the model-file engine (always there). */
    fun forId(id: String): AvatarEngine = all.firstOrNull { it.id == id && it.available } ?: ModelFileEngine
}
