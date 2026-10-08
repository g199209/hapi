package app.hapi.companion.feature.sessions

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionDateRangeTest {
    @Test fun `local day includes both boundaries correctly in positive and negative zones`() {
        val day = LocalDate.of(2026, 10, 7)
        for (id in listOf("Asia/Shanghai", "America/Los_Angeles")) {
            val zone = ZoneId.of(id)
            val range = SessionDateRange(day, day)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            assertFalse(range.contains(start - 1, zone))
            assertTrue(range.contains(start, zone))
            assertTrue(range.contains(end - 1, zone))
            assertFalse(range.contains(end, zone))
        }
    }

    @Test fun `DST days use next local midnight instead of 24 hours`() {
        val zone = ZoneId.of("America/New_York")
        for ((date, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25)) {
            val day = LocalDate.parse(date)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            assertEquals(hours * 3_600_000L, end - start)
            assertTrue(SessionDateRange(day, day).contains(end - 1, zone))
            assertFalse(SessionDateRange(day, day).contains(end, zone))
        }
    }

    @Test fun `picker UTC calendar values allow a single day and a multi-day range`() {
        val start = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        assertEquals(SessionDateRange(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7)), SessionDateRange.fromPicker(start, null))
        assertEquals(LocalDate.of(2026, 10, 9), SessionDateRange.fromPicker(start, end).endInclusive)
        assertFailsWith<IllegalArgumentException> { SessionDateRange.fromPicker(end, start) }
    }
}
