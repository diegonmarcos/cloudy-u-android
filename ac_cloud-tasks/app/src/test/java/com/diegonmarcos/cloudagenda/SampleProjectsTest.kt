package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.SampleProjects
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** #1757 the Traveling sample project: the data, and its idempotent, flagged, deletable seeding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleProjectsTest {
    private val samples = SampleProjects.parse(File("src/main/assets/projects.json").readText())
    private val today = LocalDate.of(2026, 11, 15)
    private fun repo() = TasksRepository(TasksDb(RuntimeEnvironment.getApplication(), null))

    @Test fun `Traveling is declared with lodging, transport and events across about two to three weeks, overlapping`() {
        val t = samples.single { it.name == "Traveling" }
        val byList = t.lists.associate { it.name to it.tasks }
        assertEquals(listOf("Lodging", "Transportation", "Events"), t.lists.map { it.name }.take(3))
        fun has(list: String, word: String) = byList.getValue(list).any { it.name.contains(word, ignoreCase = true) || it.details.contains(word, ignoreCase = true) }
        for (w in listOf("hotel", "airbnb", "hostel")) assertTrue("lodging: $w", has("Lodging", w))
        for (w in listOf("flight", "train", "bus", "transfer", "car rental")) assertTrue("transportation: $w", has("Transportation", w))
        for (w in listOf("concert", "tour", "meeting")) assertTrue("events: $w", has("Events", w))
        val all = byList.values.flatten()
        val first = all.minOf { it.start }
        val last = all.maxOf { it.start + it.days - 1 }
        assertTrue("the trip spans ${last - first + 1} days", last - first + 1 in 14..21)
        // overlapping: stays overlap each other, and transport and events overlap stays
        fun overlap(a: com.diegonmarcos.cloudagenda.data.SampleTask, b: com.diegonmarcos.cloudagenda.data.SampleTask) = a.start <= b.start + b.days - 1 && b.start <= a.start + a.days - 1
        val lodging = byList.getValue("Lodging")
        assertTrue(lodging.any { a -> lodging.any { b -> a !== b && overlap(a, b) } })
        assertTrue(byList.getValue("Transportation").any { a -> lodging.any { b -> overlap(a, b) } })
        assertTrue(byList.getValue("Events").any { a -> lodging.any { b -> overlap(a, b) } })
    }

    @Test fun `seeding creates the project flagged as a sample, with its lists and dated tasks`() {
        val r = repo()
        assertEquals(listOf("Traveling"), r.seedSamples(samples, today))
        val s = r.snapshot()
        val p = s.projects.single { it.name == "Traveling" }
        assertTrue(p.sample)
        assertEquals(listOf("Lodging", "Transportation", "Events", "Admin"), s.listsOf(p.id).map { it.name })
        val tasks = s.tasks.filter { t -> s.list(t.listId)?.projectId == p.id }
        assertEquals(samples.single().lists.sumOf { it.tasks.size }, tasks.size)
        assertTrue(tasks.all { it.dateTarget != null && it.dateDeadline != null && it.dateTarget!! <= it.dateDeadline!! })
        // the day offsets are from the seeding day
        assertEquals(today.plusDays(2).toString(), s.tasks.first { it.name == "Hotel Avenida Palace" }.dateTarget)
        assertEquals(TaskStatus.COMPLETED, s.tasks.first { it.name == "Flight NYC to Lisbon" }.status)
    }

    @Test fun `seeding twice changes nothing`() {
        val r = repo()
        r.seedSamples(samples, today)
        val first = r.snapshot()
        assertTrue(r.seedSamples(samples, today.plusDays(30)).isEmpty())
        assertEquals(first, r.snapshot())
    }

    @Test fun `a user who deleted the sample is not given it back, and the flag goes with it`() {
        val r = repo()
        r.seedSamples(samples, today)
        r.deleteProject(r.snapshot().projects.single().id)
        assertTrue(r.snapshot().projects.isEmpty())
        assertTrue(r.seedSamples(samples, today).isEmpty())
        assertTrue(r.snapshot().projects.isEmpty())
        assertTrue(r.snapshot().tasks.isEmpty())
    }

    @Test fun `it is added next to a user's own projects and never flags them`() {
        val r = repo()
        r.addProject("Mine")
        assertEquals(listOf("Traveling"), r.seedSamples(samples, today))
        val s = r.snapshot()
        assertEquals(listOf("Mine", "Traveling"), s.projects.map { it.name })
        assertFalse(s.projects.first { it.name == "Mine" }.sample)
        assertTrue(s.projects.first { it.name == "Traveling" }.sample)
    }

    @Test fun `the first-run seed still gives Project Zero and the sample comes after it`() {
        val r = repo()
        assertTrue(r.seedIfEmpty())
        r.seedSamples(samples, today)
        assertEquals(listOf("Project Zero", "Traveling"), r.snapshot().projects.map { it.name })
    }
}
