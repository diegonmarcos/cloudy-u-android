// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.AiEngine
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskModels
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.EngineResolution.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-task model selection persistence and the ML vs OpenRouter routing rule with its fallback. */
class AiTaskRoutingTest {
    @Test fun eachTaskKeepsItsOwnModel() {
        val store = MapStore()
        AiTaskModels.set(store, AiTask.TRANSLATE, "a/translate")
        AiTaskModels.set(store, AiTask.GRAMMAR, "b/grammar")
        AiTaskModels.set(store, AiTask.ENHANCE, "c/enhance")
        assertEquals("a/translate", AiTaskModels.get(store, AiTask.TRANSLATE))
        assertEquals("b/grammar", AiTaskModels.get(store, AiTask.GRAMMAR))
        assertEquals("c/enhance", AiTaskModels.get(store, AiTask.ENHANCE))
        AiTaskModels.set(store, AiTask.GRAMMAR, "d/other")
        assertEquals("a/translate", AiTaskModels.get(store, AiTask.TRANSLATE))
        assertEquals("d/other", AiTaskModels.get(store, AiTask.GRAMMAR))
    }

    @Test fun selectionPersistsInTheStoreAndBlankClears() {
        val store = MapStore()
        AiTaskModels.set(store, AiTask.ENHANCE, "x/y")
        assertEquals("x/y", store.map["ai_task_model_enhance"])
        AiTaskModels.set(store, AiTask.ENHANCE, " ")
        assertNull(AiTaskModels.get(store, AiTask.ENHANCE))
        assertNull(store.map["ai_task_model_enhance"])
    }

    @Test fun switchDefaultsToMlAndPersists() {
        val store = MapStore()
        assertEquals(AiEngine.ML, AiTaskRouting.chosen(store, AiTask.TRANSLATE))
        AiTaskRouting.choose(store, AiTask.TRANSLATE, AiEngine.OPENROUTER)
        assertEquals(AiEngine.OPENROUTER, AiTaskRouting.chosen(store, AiTask.TRANSLATE))
        assertEquals(AiEngine.ML, AiTaskRouting.chosen(store, AiTask.GRAMMAR))
        assertEquals(AiEngine.OPENROUTER, AiTaskRouting.chosen(store, AiTask.ENHANCE)) // no on-device enhancer
    }

    @Test fun openRouterChosenOnlineWithKey() {
        val r = AiTaskRouting.resolve(AiEngine.OPENROUTER, mlAvailable = true, online = true, hasKey = true)
        assertEquals(AiEngine.OPENROUTER, r.active); assertEquals(AiEngine.ML, r.fallback); assertEquals(Reason.CHOSEN, r.reason)
    }

    @Test fun openRouterChosenOfflineFallsBackToMl() {
        val r = AiTaskRouting.resolve(AiEngine.OPENROUTER, mlAvailable = true, online = false, hasKey = true)
        assertEquals(AiEngine.ML, r.active); assertEquals(Reason.OFFLINE, r.reason)
    }

    @Test fun openRouterChosenWithoutKeyFallsBackToMl() {
        val r = AiTaskRouting.resolve(AiEngine.OPENROUTER, mlAvailable = true, online = true, hasKey = false)
        assertEquals(AiEngine.ML, r.active); assertEquals(Reason.NO_KEY, r.reason)
    }

    @Test fun openRouterChosenOfflineAndNoMlRunsNothing() {
        val r = AiTaskRouting.resolve(AiEngine.OPENROUTER, mlAvailable = false, online = false, hasKey = true)
        assertNull(r.active); assertNull(r.fallback); assertEquals(Reason.NOTHING_AVAILABLE, r.reason)
    }

    @Test fun mlChosenRunsOnDeviceEvenOffline() {
        val r = AiTaskRouting.resolve(AiEngine.ML, mlAvailable = true, online = false, hasKey = true)
        assertEquals(AiEngine.ML, r.active); assertEquals(AiEngine.OPENROUTER, r.fallback); assertEquals(Reason.CHOSEN, r.reason)
    }

    @Test fun mlChosenButUnavailableUsesOpenRouter() {
        val r = AiTaskRouting.resolve(AiEngine.ML, mlAvailable = false, online = true, hasKey = true)
        assertEquals(AiEngine.OPENROUTER, r.active); assertEquals(Reason.ML_UNAVAILABLE, r.reason)
    }

    @Test fun mlChosenWithoutKeyNamesNoFallback() {
        val r = AiTaskRouting.resolve(AiEngine.ML, mlAvailable = true, online = true, hasKey = false)
        assertEquals(AiEngine.ML, r.active); assertNull(r.fallback)
        val none = AiTaskRouting.resolve(AiEngine.ML, mlAvailable = false, online = true, hasKey = false)
        assertNull(none.active); assertEquals(Reason.NOTHING_AVAILABLE, none.reason)
    }

    @Test fun grammarEngineFollowsTheSwitch() {
        assertEquals("ai", AiTaskRouting.grammarEngine("local", AiEngine.OPENROUTER))
        assertEquals("ai", AiTaskRouting.grammarEngine("remote", AiEngine.OPENROUTER))
        assertEquals("local", AiTaskRouting.grammarEngine("ai", AiEngine.ML))
        assertEquals("remote", AiTaskRouting.grammarEngine("remote", AiEngine.ML))
        assertEquals("local", AiTaskRouting.grammarEngine("remote", null))
        assertEquals("off", AiTaskRouting.grammarEngine("off", AiEngine.OPENROUTER))
    }
}
