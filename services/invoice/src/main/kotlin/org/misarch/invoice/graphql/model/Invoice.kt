package org.misarch.invoice.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("Invoice of an order.")
@KeyDirective(fields = FieldSet("id"))
class Invoice(
    val id: UUID,
    val orderId: UUID,
    val issuedAt: OffsetDateTime,
    val content: String,
    private val userAddressId: UUID,
    private val vendorAddressId: UUID,
    val vatNumber: String?
) {

    fun userAddress(): UserAddress = UserAddress(userAddressId)

    fun vendorAddress(): VendorAddress = VendorAddress(vendorAddressId)

}
