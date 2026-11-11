package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #882 writer_route: THE FALLBACK TEST. Without advice (no engine, no consent, a closed gate, unsure) the route is
 * exactly what it was before the decision model existed: the saved one, else the default. Advice never overrides a
 * saved choice, not even an unknown one.
 */
class RoutingJevTest {

    @Test fun noAdviceIsTheOldRuleForEverySavedValue() {
        for (default in Route.values()) for (saved in listOf(null, "model", "ml", "junk", "")) {
            assertEquals("saved=$saved default=$default", Route.of(saved, default), Routing.chosen(saved, null, default))
        }
    }

    @Test fun adviceOnlyFillsAnUnchosenRoute() {
        assertEquals(Route.ML, Routing.chosen(null, Route.ML, Route.MODEL))
        assertEquals(Route.MODEL, Routing.chosen(null, Route.MODEL, Route.ML))
        assertEquals(Route.MODEL, Routing.chosen("model", Route.ML, Route.ML))
        assertEquals(Route.ML, Routing.chosen("ml", Route.MODEL, Route.MODEL))
        assertEquals("an unknown saved id is still the default, not the advice", Route.MODEL, Routing.chosen("junk", Route.ML, Route.MODEL))
    }
}
