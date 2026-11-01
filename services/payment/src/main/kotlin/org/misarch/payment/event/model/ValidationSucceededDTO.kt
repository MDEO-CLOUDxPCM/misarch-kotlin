package org.misarch.payment.event.model

import tools.jackson.databind.JsonNode

/**
 * The discounts of an order were validated
 *
 * @property order the order, kept and relayed unchanged in `payment-enabled` / `payment-failed`
 */
data class ValidationSucceededDTO(val order: JsonNode)
