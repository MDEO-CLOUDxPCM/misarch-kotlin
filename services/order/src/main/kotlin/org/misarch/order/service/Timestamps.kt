package org.misarch.order.service

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** The current time in UTC, truncated to milliseconds (the precision of the order events). */
fun currentTimestamp(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
