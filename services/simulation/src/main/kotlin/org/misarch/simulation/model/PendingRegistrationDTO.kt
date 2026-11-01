package org.misarch.simulation.model

/**
 * A registration whose queue message has not been consumed yet
 *
 * @property registration the registration as received
 * @property blocked true after a manual update: the queue message gets no automatic callback
 */
data class PendingRegistrationDTO<R : Registration>(
    val registration: R,
    val blocked: Boolean = false,
)
