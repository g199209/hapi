package app.hapi.companion.feature.sessions

import app.hapi.protocol.wire.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Calendar dates are local dates, not fixed 24-hour durations (DST matters). */
data class SessionDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init { require(!endInclusive.isBefore(start)) }

    fun contains(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        timestamp >= start.atStartOfDay(zone).toInstant().toEpochMilli() &&
            timestamp < endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        // Material's picker encodes calendar dates at UTC midnight. Do not
        // interpret them in the phone zone or western zones shift a day back.
        fun fromPicker(startMillis: Long, endMillis: Long?): SessionDateRange = SessionDateRange(
            Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate(),
            Instant.ofEpochMilli(endMillis ?: startMillis).atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }
}

data class SessionListFilter(val query: String = "", val dates: SessionDateRange? = null) {
    val active: Boolean get() = query.isNotBlank() || dates != null
}

/** AND across words, matching across title/path/preview/agent/machine fields.
 * This is list metadata search; it does not fetch or scan chat histories. */
internal fun matchesSessionSearch(session: SessionSummary, query: String, machine: String): Boolean {
    val metadata = session.metadata
    val fields = listOfNotNull(
        SessionListViewModel.sessionTitle(session), metadata?.summary?.text,
        session.lastMessagePreview, metadata?.path, metadata?.worktree?.basePath,
        metadata?.worktree?.worktreePath, metadata?.worktree?.name,
        metadata?.worktree?.branch, metadata?.flavor, machine, session.id,
    ).map { it.lowercase(Locale.ROOT) }
    return query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.all { word -> fields.any { word in it } }
}

data class SessionDirectoryGroup(
    val key: String,
    val directory: String?,
    val label: String?,
    val machine: MachineFilterUi?,
    val rows: List<SessionRowUi>,
)

/** Global pins remain above groups; project pins stay inside their directory. */
internal fun groupSessionRows(rows: List<SessionRowUi>): List<SessionDirectoryGroup> = rows
    .filter { it.summary.globalPinned != true }
    .groupBy { row ->
        val metadata = row.summary.metadata
        (metadata?.machineId ?: UNKNOWN_MACHINE_ID) to (metadata?.worktree?.basePath ?: metadata?.path)?.takeIf { it.isNotBlank() }
    }
    .map { (identity, members) ->
        val (machine, directory) = identity
        SessionDirectoryGroup(
            key = "${machine.length}:$machine:${directory ?: ""}",
            directory = directory,
            label = SessionListViewModel.projectLabel(members.first().summary),
            machine = members.first().machine,
            // The store already sorts by pins, active, pending requests and
            // recency. Partitioning must preserve that order, including ties.
            rows = members,
        )
    }
    .sortedWith(compareByDescending<SessionDirectoryGroup> { it.rows.any { row -> row.summary.pinned == true } }
        .thenByDescending { it.rows.any { row -> row.summary.active } }
        .thenByDescending { it.rows.maxOf { row -> row.summary.updatedAt } }.thenBy { it.key })
