package org.misarch.address.graphql.federation

import org.misarch.address.graphql.dataloader.AddressDataLoader
import org.misarch.address.graphql.model.VendorAddress
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [VendorAddress]es.
 */
@Component
class VendorAddressResolver : DataLoaderEntityResolver<VendorAddress>(VendorAddress::class, AddressDataLoader::class)
