package net.plumt.qr

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 일정의 시작·끝 시각. iCalendar 의 세 가지 표기를 잃지 않고 담는다.
 *
 * | 표기 | 타입 |
 * |---|---|
 * | `DTSTART;VALUE=DATE:20261105` (종일) | [AllDay] |
 * | `DTSTART:20261105T100000` (시간대 없음) · `DTSTART;TZID=Asia/Seoul:20261105T100000` | [Local] |
 * | `DTSTART:20261105T010000Z` (UTC) | [Utc] |
 *
 * 문자열 표기의 정밀도가 초라서 초 미만은 생성할 때 버린다. 같은 종류끼리만 크기를 비교할 수 있다.
 */
public sealed class EventTime {

    /** 이 시각이 가리키는 순간. 시간대가 정해지지 않은 값([AllDay]·시간대 없는 [Local])은 [defaultZone] 으로 해석한다. */
    public abstract fun toInstant(defaultZone: ZoneId): Instant

    /** 종일 일정의 날짜. iCalendar 관례상 끝 날짜는 그날을 포함하지 않는다(하루짜리 일정의 끝은 다음 날). */
    public class AllDay(public val date: LocalDate) : EventTime() {
        override fun toInstant(defaultZone: ZoneId): Instant = date.atStartOfDay(defaultZone).toInstant()
        override fun equals(other: Any?): Boolean = other is AllDay && other.date == date
        override fun hashCode(): Int = date.hashCode()
        override fun toString(): String = "AllDay($date)"
    }

    /**
     * 벽시계 시각. [zone] 이 null 이면 시간대 없는(floating) 시각이라 보는 사람의 시간대로 해석한다.
     * [zone] 이 있으면 `TZID` 로 쓴다.
     */
    public class Local(dateTime: LocalDateTime, public val zone: ZoneId? = null) : EventTime() {
        public val dateTime: LocalDateTime = dateTime.truncatedTo(ChronoUnit.SECONDS)

        /** [zone] 이 있으면 그 시간대의 시각, 없으면 null. */
        public fun toZonedDateTime(): ZonedDateTime? = zone?.let { dateTime.atZone(it) }

        override fun toInstant(defaultZone: ZoneId): Instant = dateTime.atZone(zone ?: defaultZone).toInstant()
        override fun equals(other: Any?): Boolean = other is Local && other.dateTime == dateTime && other.zone == zone
        override fun hashCode(): Int = dateTime.hashCode() * 31 + (zone?.hashCode() ?: 0)
        override fun toString(): String = if (zone == null) "Local($dateTime)" else "Local($dateTime, $zone)"
    }

    /** UTC 로 적힌 순간(`...Z`). */
    public class Utc(instant: Instant) : EventTime() {
        public val instant: Instant = instant.truncatedTo(ChronoUnit.SECONDS)

        override fun toInstant(defaultZone: ZoneId): Instant = instant
        override fun equals(other: Any?): Boolean = other is Utc && other.instant == instant
        override fun hashCode(): Int = instant.hashCode()
        override fun toString(): String = "Utc($instant)"
    }
}
