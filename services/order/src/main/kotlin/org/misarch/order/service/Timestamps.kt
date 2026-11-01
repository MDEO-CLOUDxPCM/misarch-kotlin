package org.misarch.order.service

import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** The current time of [clock], truncated to milliseconds (the precision of the order events). */
fun currentTimestamp(clock: Clock): OffsetDateTime = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)
