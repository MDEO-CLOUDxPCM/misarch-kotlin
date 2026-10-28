package org.misarch.common.timing.counters

import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Readable
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.mapping.OutboundRow
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Operators
import java.time.Duration
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate

/**
 * Wraps the application's R2DBC [ConnectionFactory] beans in [CountingConnectionFactory] and proxies Spring Data's
 * [MappingR2dbcConverter] so that its entity -> parameters conversion (`write(entity, OutboundRow)`) is timed; that
 * CPU goes to the next write statement created on the same thread (only registered with the counters on).
 */
class CountingR2dbcPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any = when {
        bean is ConnectionFactory && bean !is CountingConnectionFactory -> CountingConnectionFactory(bean)
        bean is MappingR2dbcConverter && !AopUtils.isAopProxy(bean) -> ProxyFactory(bean).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val args = invocation.arguments
                if (!CpuCounters.enabled || invocation.method.name != "write" || args.size != 2 || args[1] !is OutboundRow) {
                    invocation.proceed()
                } else {
                    val t0 = CpuCounters.now()
                    try {
                        invocation.proceed()
                    } finally {
                        CpuCounters.addPendingWrite(CpuCounters.now() - t0, args[0]?.javaClass?.simpleName ?: "?")
                    }
                }
            })
        }.proxy
        else -> bean
    }
}

class CountingConnectionFactory(private val delegate: ConnectionFactory) : ConnectionFactory, Wrapped<ConnectionFactory> {
    override fun create(): Publisher<out Connection> = Mono.from(delegate.create()).map { CountingConnection(it) }
    override fun getMetadata(): ConnectionFactoryMetadata = delegate.metadata
    override fun unwrap(): ConnectionFactory = delegate
    override fun toString(): String = "CountingConnectionFactory[$delegate]"
}

class CountingConnection(private val delegate: Connection) : Connection, Wrapped<Connection> {

    override fun createStatement(sql: String): Statement {
        if (!CpuCounters.enabled) return delegate.createStatement(sql)
        // the lookup/normalisation is the counters' own overhead: charged to nobody
        val counter = CpuCounters.explicitStatement(null) { CpuCounters.statement(sql) }
        if (counter.mode == "write") CpuCounters.takePendingWrite()?.let { w ->
            counter.mappingCpuNs.add(w.cpuNs)
            if (counter.entity == null) counter.entity = w.entity
        }
        return CountingStatement(CpuCounters.explicitStatement(counter.driver) { delegate.createStatement(sql) }, counter)
    }

    override fun beginTransaction(): Publisher<Void> = delegate.beginTransaction()
    override fun beginTransaction(definition: TransactionDefinition): Publisher<Void> = delegate.beginTransaction(definition)
    override fun close(): Publisher<Void> = delegate.close()
    override fun commitTransaction(): Publisher<Void> = delegate.commitTransaction()
    override fun createBatch(): Batch = delegate.createBatch()
    override fun createSavepoint(name: String): Publisher<Void> = delegate.createSavepoint(name)
    override fun isAutoCommit(): Boolean = delegate.isAutoCommit
    override fun getMetadata(): ConnectionMetadata = delegate.metadata
    override fun getTransactionIsolationLevel(): IsolationLevel = delegate.transactionIsolationLevel
    override fun releaseSavepoint(name: String): Publisher<Void> = delegate.releaseSavepoint(name)
    override fun rollbackTransaction(): Publisher<Void> = delegate.rollbackTransaction()
    override fun rollbackTransactionToSavepoint(name: String): Publisher<Void> = delegate.rollbackTransactionToSavepoint(name)
    override fun setAutoCommit(autoCommit: Boolean): Publisher<Void> = delegate.setAutoCommit(autoCommit)
    override fun setLockWaitTimeout(timeout: Duration): Publisher<Void> = delegate.setLockWaitTimeout(timeout)
    override fun setStatementTimeout(timeout: Duration): Publisher<Void> = delegate.setStatementTimeout(timeout)
    override fun setTransactionIsolationLevel(level: IsolationLevel): Publisher<Void> =
        delegate.setTransactionIsolationLevel(level)
    override fun validate(depth: ValidationDepth): Publisher<Boolean> = delegate.validate(depth)
    override fun unwrap(): Connection = delegate
    override fun toString(): String = "CountingConnection[$delegate]"
}

/** Driver CPU: binding and `execute()` including the subscription of its publisher, on the calling thread. */
class CountingStatement(private val delegate: Statement, private val counter: StatementCounter) : Statement {
    private inline fun timed(block: () -> Unit): Statement {
        CpuCounters.explicitStatement(counter.driver, block)
        return this
    }

    override fun add(): Statement = timed { delegate.add() }
    override fun bind(index: Int, value: Any): Statement = timed { delegate.bind(index, value) }
    override fun bind(name: String, value: Any): Statement = timed { delegate.bind(name, value) }
    override fun bindNull(index: Int, type: Class<*>): Statement = timed { delegate.bindNull(index, type) }
    override fun bindNull(name: String, type: Class<*>): Statement = timed { delegate.bindNull(name, type) }
    override fun returnGeneratedValues(vararg columns: String): Statement = timed { delegate.returnGeneratedValues(*columns) }
    override fun fetchSize(rows: Int): Statement = timed { delegate.fetchSize(rows) }

    override fun execute(): Publisher<out Result> {
        counter.calls.increment()
        val publisher = CpuCounters.explicitStatement(counter.driver) { delegate.execute() }
        return ExecutePublisher(publisher, counter)
    }
}

private class ExecutePublisher(private val delegate: Publisher<out Result>, private val counter: StatementCounter) :
    Publisher<Result> {
    override fun subscribe(s: Subscriber<in Result>) {
        val actual: CoreSubscriber<in Result> = Operators.toCoreSubscriber(s)
        CpuCounters.explicitStatement(counter.driver) {
            Flux.from(delegate).map<Result> { CountingResult(it, counter) }.subscribe(actual)
        }
    }
}

/** Mapping CPU: the mapping function per row; rows: mapped rows or the update count. */
class CountingResult(private val delegate: Result, private val counter: StatementCounter) : Result {

    override fun getRowsUpdated(): Publisher<Long> =
        Mono.from(delegate.rowsUpdated).doOnNext { counter.rows.add(it) }

    override fun <T> map(mappingFunction: BiFunction<Row, RowMetadata, out T>): Publisher<T> =
        delegate.map { row, metadata -> mapped { mappingFunction.apply(row, metadata) } }

    override fun <T> map(mappingFunction: Function<in Readable, out T>): Publisher<T> =
        delegate.map(Function<Readable, T> { readable -> mapped { mappingFunction.apply(readable) } })

    override fun filter(filter: Predicate<Result.Segment>): Result = CountingResult(delegate.filter(filter), counter)

    override fun <T> flatMap(mappingFunction: Function<Result.Segment, out Publisher<out T>>): Publisher<T> =
        delegate.flatMap(Function<Result.Segment, Publisher<out T>> { segment ->
            if (segment is Result.RowSegment) mapped { mappingFunction.apply(segment) } else mappingFunction.apply(segment)
        })

    private inline fun <T> mapped(block: () -> T): T {
        val value = CpuCounters.explicitStatement(counter.mapping, block)
        counter.rows.increment()
        if (counter.entity == null && value != null) counter.entity = entityName(value)
        return value
    }

    private fun entityName(value: Any): String {
        val type = value.javaClass
        val name = type.name
        return if (name.startsWith("java.") || name.startsWith("kotlin.") || value is Map<*, *>) counter.table
        else type.simpleName.ifEmpty { counter.table }
    }
}
