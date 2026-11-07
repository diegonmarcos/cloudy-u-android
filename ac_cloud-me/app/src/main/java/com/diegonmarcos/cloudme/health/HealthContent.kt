package com.diegonmarcos.cloudme.health

import com.diegonmarcos.cloudme.CloudMeApiData
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The declared content of Cloud Me > Health, parsed from the data files under ac_cloud-me/data/health
 * (shipped as assets): the home clusters, the formulas, the plan and the workout programme. Nothing here is
 * written in Kotlin; the Kotlin only gives the declared shapes a type. User entries are NOT content: they live
 * in [HealthUserData].
 */
data class Cluster(val id: String, val label: String, val icon: String, val pages: List<String>)
data class HomeDecl(val title: String, val subtitle: String, val clusters: List<Cluster>)

data class Level(val id: String, val label: String, val multiplier: Double)
data class Band(val max: Double, val label: String)
data class Formulas(
    val bmrBase: Double, val bmrPerLbm: Double,
    val defaultActivity: String, val activity: List<Level>, val ffmiBands: List<Band>,
    val naclMgPerMin: Double, val naFractionOfNacl: Double, val saltGPerTeaspoon: Double, val fluidReplaceRatio: Double,
    val saunaMin: Int, val saunaMax: Int, val saunaStep: Int,
    val hemoCurve: List<Pair<Double, Double>>, val hemoMinGapMin: Int, val hemoFatGapMin: Int,
    val circadian: List<CurveSeries>,
)
data class CurveSeries(val id: String, val label: String, val unit: String, val points: List<Pair<Double, Double>>)

data class Pack(val size: Double, val unit: String, val label: String)
data class PlanItem(
    val id: String, val name: String, val qty: Double, val unit: String, val role: String,
    val tags: Set<String>, val category: String, val pack: Pack,
)
data class PlanEvent(
    val id: String, val time: String, val kind: String, val window: String, val title: String, val body: String,
    val rule: String, val fat: Boolean, val items: List<PlanItem>, val supplements: List<PlanItem>,
) {
    /** Minutes after midnight. */
    val minutes: Int get() = time.substringBefore(':').toInt() * 60 + time.substringAfter(':').toInt()
}
data class Objective(val id: String, val title: String, val body: String, val applies: List<String>)
data class Biomarker(val id: String, val label: String, val unit: String, val min: Double?, val max: Double?, val note: String)
data class Culinary(val id: String, val method: String, val nutrient: String, val effect: String, val how: String)
data class ClashSource(val id: String, val label: String, val tags: Set<String>)
data class ClashRule(val level: String, val title: String, val text: String, val proteinHasAny: Set<String>, val cofactorHasAny: Set<String>)
data class ClashDecl(val proteins: List<ClashSource>, val cofactors: List<ClashSource>, val rules: List<ClashRule>)
data class Plan(
    val daysPerWeek: Int, val timeline: List<PlanEvent>, val objectives: List<Objective>,
    val biomarkers: List<Biomarker>, val culinary: List<Culinary>, val clash: ClashDecl,
)
data class Exercise(val id: String, val name: String, val scheme: String, val note: String, val load: Boolean)
data class WorkoutDay(val id: String, val label: String, val exercises: List<Exercise>)

data class HealthContent(val home: HomeDecl, val formulas: Formulas, val plan: Plan, val workout: List<WorkoutDay>) {
    companion object {
        const val HOME = "health_home.json"
        const val FORMULAS = "health_formulas.json"
        const val PLAN = "health_plan.json"
        const val WORKOUT = "health_workout.json"

        /** Reads the four data files from assets. A file missing from the build is a bug and throws, never a blank page. */
        fun load(ctx: Context): HealthContent = parse(read(ctx, HOME), read(ctx, FORMULAS), read(ctx, PLAN), read(ctx, WORKOUT))

        private fun read(ctx: Context, name: String): String = CloudMeApiData.text(ctx, name)

        fun parse(home: String, formulas: String, plan: String, workout: String): HealthContent =
            HealthContent(parseHome(JSONObject(home)), parseFormulas(JSONObject(formulas)), parsePlan(JSONObject(plan)), parseWorkout(JSONObject(workout)))

        private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
        private fun JSONArray.pair(): Pair<Double, Double> = getDouble(0) to getDouble(1)
        private fun JSONArray?.pairs(): List<Pair<Double, Double>> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it)?.pair() }
        private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

        private fun parseHome(o: JSONObject) = HomeDecl(
            o.optString("title"), o.optString("subtitle"),
            o.optJSONArray("clusters").objs().map { Cluster(it.getString("id"), it.getString("label"), it.optString("icon"), it.optJSONArray("pages").strs()) },
        )

        private fun parseFormulas(o: JSONObject): Formulas {
            val a = o.getJSONObject("anthropometrics")
            val bmr = a.getJSONObject("bmr")
            val t = o.getJSONObject("thermal")
            val h = o.getJSONObject("hemodynamics")
            return Formulas(
                bmr.getDouble("base"), bmr.getDouble("per_lbm"), a.getString("default_activity"),
                a.getJSONArray("activity").objs().map { Level(it.getString("id"), it.getString("label"), it.getDouble("multiplier")) },
                a.getJSONArray("ffmi_bands").objs().map { Band(it.getDouble("max"), it.getString("label")) },
                t.getDouble("nacl_mg_per_min"), t.getDouble("na_fraction_of_nacl"), t.getDouble("salt_g_per_teaspoon"), t.getDouble("fluid_replace_ratio"),
                t.getInt("min_minutes"), t.getInt("max_minutes"), t.getInt("step_minutes"),
                h.getJSONArray("curve").pairs(), h.getInt("min_gap_min"), h.getInt("fat_gap_min"),
                o.getJSONObject("circadian").getJSONArray("series").objs().map {
                    CurveSeries(it.getString("id"), it.getString("label"), it.optString("unit"), it.getJSONArray("points").pairs())
                },
            )
        }

        private fun parseItem(o: JSONObject): PlanItem {
            val p = o.getJSONObject("pack")
            return PlanItem(
                o.getString("id"), o.getString("name"), o.getDouble("qty"), o.getString("unit"), o.optString("role", "side"),
                o.optJSONArray("tags").strs().toSet(), o.optString("category"), Pack(p.getDouble("size"), p.getString("unit"), p.getString("label")),
            )
        }

        private fun parsePlan(o: JSONObject): Plan {
            val c = o.getJSONObject("clash")
            fun src(a: JSONArray) = a.objs().map { ClashSource(it.getString("id"), it.getString("label"), it.optJSONArray("tags").strs().toSet()) }
            return Plan(
                o.getInt("days_per_week"),
                o.getJSONArray("timeline").objs().map {
                    PlanEvent(
                        it.getString("id"), it.getString("time"), it.getString("kind"), it.getString("window"), it.getString("title"),
                        it.optString("body"), it.optString("rule"), it.optBoolean("fat"),
                        it.optJSONArray("items").objs().map(::parseItem), it.optJSONArray("supplements").objs().map(::parseItem),
                    )
                },
                o.getJSONArray("objectives").objs().map { Objective(it.getString("id"), it.getString("title"), it.optString("body"), it.optJSONArray("applies").strs()) },
                o.getJSONArray("biomarkers").objs().map {
                    Biomarker(it.getString("id"), it.getString("label"), it.optString("unit"), it.optDoubleOrNull("min"), it.optDoubleOrNull("max"), it.optString("note"))
                },
                o.getJSONArray("culinary").objs().map { Culinary(it.getString("id"), it.getString("method"), it.optString("nutrient"), it.optString("effect"), it.optString("how")) },
                ClashDecl(
                    src(c.getJSONArray("proteins")), src(c.getJSONArray("cofactors")),
                    c.getJSONArray("rules").objs().map {
                        ClashRule(it.getString("level"), it.getString("title"), it.optString("text"), it.optJSONArray("protein_has_any").strs().toSet(), it.optJSONArray("cofactor_has_any").strs().toSet())
                    },
                ),
            )
        }

        private fun parseWorkout(o: JSONObject): List<WorkoutDay> = o.getJSONArray("days").objs().map { d ->
            WorkoutDay(d.getString("id"), d.getString("label"), d.getJSONArray("exercises").objs().map {
                Exercise(it.getString("id"), it.getString("name"), it.optString("scheme"), it.optString("note"), it.optBoolean("load"))
            })
        }
    }
}
