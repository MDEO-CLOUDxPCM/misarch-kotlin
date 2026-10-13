package org.misarch.common.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A CloudEvent as delivered by Dapr pub/sub to a subscription route (`application/cloudevents+json`).
 * Only [data] is used by the services; the envelope fields are informational.
 *
 * ```
 * @Topic(name = USER_CREATED, pubsubName = PUBSUB_NAME)
 * @PostMapping("/subscription/$USER_CREATED")
 * suspend fun onUserCreated(@RequestBody cloudEvent: CloudEvent<UserDTO>) = userService.registerUser(cloudEvent.data)
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CloudEvent<T>(
    val data: T,
    val id: String? = null,
    val source: String? = null,
    val type: String? = null,
    val specversion: String? = null,
    val datacontenttype: String? = null,
    val topic: String? = null,
    val pubsubname: String? = null,
    val traceid: String? = null,
    val traceparent: String? = null,
    val tracestate: String? = null,
    val time: String? = null,
)
