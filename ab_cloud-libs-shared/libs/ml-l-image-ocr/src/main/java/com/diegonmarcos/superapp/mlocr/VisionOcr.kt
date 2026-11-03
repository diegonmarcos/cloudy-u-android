package com.diegonmarcos.superapp.mlocr

/**
 * #1418 the OpenRouter alternative: the image goes to a vision model as a data: URL with a
 * transcribe-only instruction. The wire (body, token, HTTP, reply parsing) is the consuming app's
 * existing OpenRouter path, handed in as [send], so this lib holds no key and opens no socket.
 */
class VisionOcr(private val send: (model: String, prompt: String, jpeg: ByteArray) -> Result<String>) {
    fun read(jpeg: ByteArray, model: String, prompt: String): Result<String> =
        if (model.isBlank()) Result.failure(IllegalStateException("no vision model chosen")) else send(model, prompt, jpeg)
}
