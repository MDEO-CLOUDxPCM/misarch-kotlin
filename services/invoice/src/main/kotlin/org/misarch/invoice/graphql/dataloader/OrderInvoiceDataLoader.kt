package org.misarch.invoice.graphql.dataloader

import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.invoice.graphql.model.Invoice
import org.misarch.invoice.persistence.model.InvoiceEntity
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Data loader for the [Invoice]s of orders (by order id)
 */
@Component
class OrderInvoiceDataLoader(
    private val conditionQueries: ConditionQueries
) : BaseIdDataLoader<Invoice, InvoiceEntity>() {

    override suspend fun loadAll(ids: List<UUID>): List<InvoiceEntity> =
        conditionQueries.findAll(Condition.inArray("t.orderId", ids))

    override fun toDTO(entity: InvoiceEntity): Invoice = entity.toDTO()

    override fun key(entity: InvoiceEntity): UUID = entity.orderId

}
