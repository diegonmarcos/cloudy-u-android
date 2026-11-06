package com.diegonmarcos.superapp.fleetdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** schemaVersion validation, shared by the client (before it parses) and the providers (before they replace a copy). */
object SchemaCheck {
    sealed interface Result {
        data class Ok(val schemaVersion: Int) : Result
        data class Bad(val why: String) : Result
    }

    /** A dataset is a JSON object with an integer `schemaVersion` in 1..[supported]; a newer or missing one is refused, never guessed at. */
    fun check(text: String, supported: Int = Int.MAX_VALUE): Result {
        val root = try { Json.parseToJsonElement(text) } catch (e: Exception) { return Result.Bad("not JSON") }
        val obj = root as? JsonObject ?: return Result.Bad("not an object")
        val v = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: return Result.Bad("no schemaVersion")
        if (v < 1) return Result.Bad("schemaVersion $v < 1")
        if (v > supported) return Result.Bad("schemaVersion $v is newer than the $supported this app reads")
        return Result.Ok(v)
    }
}
