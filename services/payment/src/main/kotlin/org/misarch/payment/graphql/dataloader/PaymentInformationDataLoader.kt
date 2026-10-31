package org.misarch.payment.graphql.dataloader

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.generator.extensions.get
import graphql.GraphQLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.misarch.payment.persistence.PaymentInformationRow
import org.misarch.payment.persistence.PaymentStore
import org.springframework.stereotype.Component
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Batches the payment informations referenced by the payments of a request (`Payment.paymentInformation`): one
 * `SELECT ... WHERE id = ANY(:ids)` per batch, like the id data loaders of libs/common (which are keyed by UUID; payment
 * ids are strings). A missing information resolves to null (the referenced document was deleted).
 */
@Component
class PaymentInformationDataLoader(private val store: PaymentStore) : KotlinDataLoader<String, PaymentInformationRow> {

    override val dataLoaderName: String = PaymentInformationDataLoader::class.simpleName!!

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<String, PaymentInformationRow> =
        DataLoaderFactory.newDataLoader<String, PaymentInformationRow>({ ids, environment ->
            val scope = environment.getContext<GraphQLContext>()?.get<CoroutineScope>() ?: CoroutineScope(EmptyCoroutineContext)
            scope.future {
                val rows = store.findInformations(ids).associateBy { it.id }
                // a null value is a valid DataLoader result (missing information)
                @Suppress("UNCHECKED_CAST")
                (ids.map { rows[it] } as List<PaymentInformationRow>)
            }
        }, DataLoaderOptions.newOptions().setBatchLoaderContextProvider { graphQLContext }.build())
}
