package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.Services
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The declared public and private services of the old Health page, read from the data files (the app reads the same files through BuildConfig and org.json, which a plain JVM test cannot use). */
object HealthFixtures {
    private fun arr(f: String): List<JsonObject> = (Json.parseToJsonElement(File(FleetFixtures.moduleDir(), "data/$f").readText()) as JsonArray).map { it as JsonObject }
    private fun JsonObject.s(k: String, d: String = "") = (this[k] as? JsonPrimitive)?.contentOrNull ?: d

    fun pub(): List<Services.PublicService> = arr("services_public.json").map {
        Services.PublicService(it.s("name"), it.s("service"), it.s("vm"), it.s("public_url"), it.s("auth"), it.s("private_dns"), it.s("category"))
    }.sortedBy { it.name }

    fun priv(): List<Services.PrivateService> = arr("services_private.json").map {
        Services.PrivateService(it.s("name"), it.s("service"), it.s("vm"), it.s("private_dns"), it.s("protocol", "tcp"), it.s("category"), it.s("db_engine"))
    }.sortedBy { it.name }
}
