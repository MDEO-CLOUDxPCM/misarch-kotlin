package org.misarch.order.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Input for creating an order
 *
 * @property userId the id of the user owning the order
 * @property orderItemInputs the items of the order
 * @property shipmentAddressId the id of the address the order is shipped to
 * @property invoiceAddressId the id of the address of the invoice
 * @property paymentInformationId the id of the payment information
 * @property vatNumber the optional VAT number
 */
class CreateOrderInput(
    @property:GraphQLDescription("UUID of user owning the order.")
    val userId: UUID,
    @property:GraphQLDescription("OrderItems of order.")
    val orderItemInputs: List<OrderItemInput>,
    @property:GraphQLDescription("UUID of address to where the order should be shipped to.")
    val shipmentAddressId: UUID,
    @property:GraphQLDescription("UUID of address of invoice.")
    val invoiceAddressId: UUID,
    @property:GraphQLDescription("UUID of payment information that the order should be processed with.")
    val paymentInformationId: UUID,
    @property:GraphQLDescription("Optional VAT number.")
    val vatNumber: String? = null
)
