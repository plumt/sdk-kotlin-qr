package net.plumt.qr.internal

import net.plumt.qr.EventTime
import net.plumt.qr.QrPayload
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 일정 ↔ iCalendar `VEVENT`. */
internal object EventCodec {

    private val EVENT_BOUNDARY = ContentLines.Boundary(
        setOf("BEGIN", "END", "VERSION", "PRODID", "UID", "DTSTAMP", "SUMMARY", "DTSTART", "DTEND", "LOCATION", "DESCRIPTION"),
        allowGroup = false,
    )
    // iCalendar 날짜·시각은 ASCII 숫자만이다. \d 는 Android 에서 유니코드 숫자까지 맞춰 JVM 과 결과가 달라진다
    private val ICAL_TIME = Regex("^([0-9]{4})([0-9]{2})([0-9]{2})(?:[Tt]([0-9]{2})([0-9]{2})([0-9]{2})?([Zz])?)?$")
    private val DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
    private val YEARS = 1..9999

    fun encode(event: QrPayload.Event): String {
        require(event.title.isNotBlank()) { "title 이 비어 있다" }
        event.start?.let { checkYear(it, "start") }
        event.end?.let { checkYear(it, "end") }
        val start = event.start
        val end = event.end
        if (start != null && end != null) {
            require(!endsBeforeStart(start, end)) { "end($end) 가 start($start) 보다 앞선다" }
        }
        return buildList {
            add("BEGIN:VEVENT")
            add("SUMMARY:${TextCodec.escapeText(event.title.trim())}")
            start?.let { add("DTSTART" + format(it)) }
            end?.let { add("DTEND" + format(it)) }
            if (event.location.isNotBlank()) add("LOCATION:${TextCodec.escapeText(event.location.trim())}")
            if (event.description.isNotBlank()) add("DESCRIPTION:${TextCodec.escapeText(event.description.trim())}")
            add("END:VEVENT")
        }.joinToString("\n")
    }

    /** 첫 `VEVENT` 만 읽는다. `VCALENDAR` 의 `VTIMEZONE`·`VEVENT` 안의 `VALARM` 은 건너뛴다. */
    fun parse(content: String): QrPayload.Event? {
        val lines = ContentLines.parse(content, EVENT_BOUNDARY)
        val begin = lines.indexOfFirst { it.isComponent("BEGIN", "VEVENT") }
        if (begin < 0) return null
        val fields = mutableListOf<ContentLine>()
        var depth = 0
        for (line in lines.drop(begin + 1)) {
            when {
                line.isComponent("END", "VEVENT") && depth == 0 -> break
                line.name == "BEGIN" -> depth++
                line.name == "END" -> depth--
                depth == 0 -> fields += line
            }
        }
        val first = { name: String -> fields.firstOrNull { it.name == name } }
        return QrPayload.Event(
            title = first("SUMMARY")?.text().orEmpty(),
            start = first("DTSTART")?.let(::parseTime),
            end = first("DTEND")?.let(::parseTime),
            location = first("LOCATION")?.text().orEmpty(),
            description = first("DESCRIPTION")?.text().orEmpty(),
        )
    }

    private fun ContentLine.isComponent(name: String, component: String) =
        this.name == name && rawValue.trim().equals(component, ignoreCase = true)

    private fun parseTime(line: ContentLine): EventTime? = runCatching {
        val value = line.rawValue.trim().filterNot { it == '-' || it == ':' }
        val m = ICAL_TIME.matchEntire(value) ?: return null
        val (y, mo, d, h, mi, s, z) = m.destructured
        val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        when {
            h.isEmpty() || line.param("VALUE").equals("DATE", ignoreCase = true) -> EventTime.AllDay(date)
            else -> {
                val dateTime = date.atTime(h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt())
                if (z.isNotEmpty()) {
                    EventTime.Utc(dateTime.toInstant(ZoneOffset.UTC))
                } else {
                    EventTime.Local(dateTime, line.param("TZID")?.let(::zoneOrNull))
                }
            }
        }
    }.getOrNull()

    /** 모르는 시간대 이름(Windows 이름 등)은 시간대 없는 시각으로 읽는다. */
    private fun zoneOrNull(tzid: String): ZoneId? =
        runCatching { ZoneId.of(tzid.trim().trim('"').removePrefix("/")) }.getOrNull()

    private fun format(time: EventTime): String = when (time) {
        is EventTime.AllDay -> ";VALUE=DATE:" + time.date.format(DATE)
        is EventTime.Local -> (time.zone?.let { ";TZID=${it.id}" } ?: "") + ":" + time.dateTime.format(DATE_TIME)
        is EventTime.Utc -> ":" + LocalDateTime.ofInstant(time.instant, ZoneOffset.UTC).format(DATE_TIME) + "Z"
    }

    private fun checkYear(time: EventTime, name: String) {
        val year = when (time) {
            is EventTime.AllDay -> time.date.year
            is EventTime.Local -> time.dateTime.year
            is EventTime.Utc -> LocalDateTime.ofInstant(time.instant, ZoneOffset.UTC).year
        }
        require(year in YEARS) { "$name 의 연도($year) 는 $YEARS 범위여야 한다" }
    }

    private fun endsBeforeStart(start: EventTime, end: EventTime): Boolean = when {
        start is EventTime.AllDay && end is EventTime.AllDay -> end.date.isBefore(start.date)
        start is EventTime.Local && end is EventTime.Local && start.zone == end.zone -> end.dateTime.isBefore(start.dateTime)
        start is EventTime.Utc && end is EventTime.Utc -> end.instant.isBefore(start.instant)
        else -> false
    }
}
