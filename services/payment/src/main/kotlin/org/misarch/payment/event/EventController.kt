package org.misarch.payment.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.payment.event.model.UserDTO
import org.misarch.payment.event.model.ValidationSucceededDTO
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentRegistrationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @property paymentRegistrationService creates the payments of validated orders
 * @property paymentInformationService creates the default payment informations of new users
 */
@Controller
class EventController(
    private val paymentRegistrationService: PaymentRegistrationService,
    private val paymentInformationService: PaymentInformationService,
) {

    @Topic(name = PaymentEvents.ORDER_VALIDATION_SUCCEEDED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${PaymentEvents.ORDER_VALIDATION_SUCCEEDED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onOrderValidationSucceeded(@RequestBody cloudEvent: CloudEvent<ValidationSucceededDTO>) {
        paymentRegistrationService.createPaymentForOrder(cloudEvent.data.order)
    }

    @Topic(name = PaymentEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${PaymentEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(@RequestBody cloudEvent: CloudEvent<UserDTO>) {
        paymentInformationService.registerUser(cloudEvent.data)
    }

}
