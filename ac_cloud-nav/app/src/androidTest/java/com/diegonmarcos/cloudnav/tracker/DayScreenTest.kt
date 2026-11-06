package com.diegonmarcos.cloudnav.tracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Timeline > Day (#1145): the basemap route map, icon glyphs on segments, the full address and the category of a visit. */
@RunWith(AndroidJUnit4::class)
class DayScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 10, 14)
    private fun at(h: Int) = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private val home = Place(47.0, 8.0, "Home", "Seestrasse 1", "8000", null, "Zurich", "Zurich", "Switzerland", PoiCategory.HOME.name)
    private val cafe = Place(47.01, 8.01, "Cafe Central", "Bahnhofstrasse 14", "8001", null, "Zurich", "Zurich", "Switzerland", PoiCategory.FOOD.name)
    private val ui = DayUi(date, date, listOf(
        DaySeg.Stay(at(0), at(8), StayKind.SLEEP, home),
        DaySeg.Move(at(8), at(9), 1500.0, 0, 0.4, 3_600_000, home, cafe, Mode.BIKE),
        DaySeg.Stay(at(9), at(18), StayKind.IDLE, cafe, confirmed = true),
    ), fixes = listOf(PlaceFix(at(8) + 1_800_000, 47.005, 8.005, false, 10.0)))

    @Test fun mapAddressCategoryAndIconsAreShown() {
        rule.setContent { CloudKitTheme(TrackerInk.palette()) { DayScreen(ui, DayTab.DAY, {}, DayActions(), zone) } }
        rule.onNodeWithTag(DayTags.MAP).assertIsDisplayed()
        rule.onNodeWithTag(DayTags.SUMMARY).assertIsDisplayed()
        rule.onNodeWithTag(DayTags.BAR).assertIsDisplayed()
        rule.onNodeWithTag(DayTags.MAP_DAY).assertIsDisplayed()
        rule.onNodeWithText("Seestrasse 1, 8000, Zurich, Zurich, Switzerland").assertIsDisplayed()
        rule.onNodeWithTag(DayTags.LIST).performScrollToNode(hasText("Cafe Central"))
        rule.onNodeWithText("At Cafe Central").assertExists()
        rule.onNodeWithText("Bahnhofstrasse 14, 8001, Zurich, Zurich, Switzerland").assertExists()
        rule.onNodeWithText("Food and drink", substring = true).assertExists()
    }
}
