package org.misarch.invoice.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.invoice.event.model.ArchivedUserAddressDTO
import org.misarch.invoice.event.model.UserAddressDTO
import org.misarch.invoice.event.model.UserDTO
import org.misarch.invoice.event.model.ValidationSucceededDTO
import org.misarch.invoice.event.model.VendorAddressDTO
import org.misarch.invoice.service.InvoiceService
import org.misarch.invoice.service.UserAddressService
import org.misarch.invoice.service.UserService
import org.misarch.invoice.service.VendorAddressService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @param invoiceService the invoice service
 * @param vendorAddressService the vendor address service
 * @param userService the user service
 * @param userAddressService the user address service
 */
@Controller
class EventController(
    private val invoiceService: InvoiceService,
    private val vendorAddressService: VendorAddressService,
    private val userService: UserService,
    private val userAddressService: UserAddressService
) {

    /**
     * Handles an order validation succeeded event
     *
     * @param cloudEvent the cloud event containing the validated order
     */
    @Topic(name = InvoiceEvents.ORDER_VALIDATION_SUCCEEDED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InvoiceEvents.ORDER_VALIDATION_SUCCEEDED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onOrderValidationSucceeded(
        @RequestBody
        cloudEvent: CloudEvent<ValidationSucceededDTO>
    ) {
        invoiceService.createInvoice(cloudEvent.data.order)
    }

    /**
     * Handles a vendor address created event
     *
     * @param cloudEvent the cloud event containing the created vendor address
     */
    @Topic(name = InvoiceEvents.VENDOR_ADDRESS_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InvoiceEvents.VENDOR_ADDRESS_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onVendorAddressCreated(
        @RequestBody
        cloudEvent: CloudEvent<VendorAddressDTO>
    ) {
        vendorAddressService.registerVendorAddress(cloudEvent.data)
    }

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the created user
     */
    @Topic(name = InvoiceEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InvoiceEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserDTO>
    ) {
        userService.registerUser(cloudEvent.data)
    }

    /**
     * Handles a user address created event
     *
     * @param cloudEvent the cloud event containing the created user address
     */
    @Topic(name = InvoiceEvents.USER_ADDRESS_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InvoiceEvents.USER_ADDRESS_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserAddressCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserAddressDTO>
    ) {
        userAddressService.registerUserAddress(cloudEvent.data)
    }

    /**
     * Handles a user address archived event
     *
     * @param cloudEvent the cloud event containing the archived user address
     */
    @Topic(name = InvoiceEvents.USER_ADDRESS_ARCHIVED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${InvoiceEvents.USER_ADDRESS_ARCHIVED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserAddressArchived(
        @RequestBody
        cloudEvent: CloudEvent<ArchivedUserAddressDTO>
    ) {
        userAddressService.archiveUserAddress(cloudEvent.data)
    }

}
