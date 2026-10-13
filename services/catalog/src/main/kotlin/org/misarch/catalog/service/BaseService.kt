package org.misarch.catalog.service

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Base service for all services
 *
 * @param T entity type
 * @param R repository type
 * @property repository the provided repository
 */
abstract class BaseService<T : Any, R : CoroutineCrudRepository<T, UUID>>(protected val repository: R)
