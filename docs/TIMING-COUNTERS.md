# Timing counters: precise CPU per statement and per operation (branch `timing-instrumentation`)

Thread CPU time, attributed to SQL statements and to operations (GraphQL fields, `_entities` per type, data loader
batches, Dapr event handlers, HTTP handlers), served cumulatively at `GET /timing/counters`. Code:
`libs/common/src/main/kotlin/org/misarch/common/timing/counters/`. Complements the timing spans (`TimingSpans`,
wall time, sampled) and the async-profiler buckets (CPU, not keyed by statement or operation).

## Switch

| Env / property | Effect |
| --- | --- |
| `MISARCH_TIMING_COUNTERS` / `misarch.timing.counters` = `true` | counters on |
| = `false` | counters off, even with the spans on |
| unset | follows `MISARCH_TIMING_SPANS` / `misarch.timing.spans` (so `--mode on` stacks have them) |

Off (the default): nothing is registered, `GET /timing/counters` answers 404 as on `main`, the only remaining hook
is one volatile read per data loader batch. The counters need no OpenTelemetry agent. They are read at start.

## `GET /timing/counters` (app port, no tracing)

```json
{"enabled":true,"t":1793182008215,
 "statements":[{"key":"read productentity 1a2b3c4d","sql":"SELECT t.* FROM \"productentity\" t WHERE ...","entity":"ProductEntity",
                "mode":"read","calls":12,"rows":120,"driverCpuNs":1840000,"mappingCpuNs":5230000}],
 "operations":[{"key":"Query.product","kind":"graphql","calls":12,"cpuNs":4410000}],
 "threadCpuNsTotal":91234000000}
```

All values are cumulative since the JVM started; read before and after a segment and subtract.

- **statements**: one entry per normalised SQL text (whitespace collapsed; literals, `$n` and named markers as `?`;
  `IN (...)` lists and repeated `VALUES` groups collapsed, so `LIMIT 10 OFFSET 200` and `OFFSET 210` share an
  entry, like pg_stat_statements).
  - `mode`: `write` if the statement contains INSERT/UPDATE/DELETE/MERGE/TRUNCATE (CTEs included), else `read`
    (`SELECT ... FOR UPDATE` is a read).
  - `entity`: the Spring Data entity class (reads: the class of the mapped rows; writes: the entity converted to
    parameters), else the statement's main table (for counts, existence checks and scalar row mappings).
  - `key`: `<mode> <table> <hash of sql>`, fixed when the counter is created (unlike `entity`, which can change
    once, from the table to the entity class, when the first row is mapped).
  - `calls`: executions (`Statement.execute()`); `rows`: rows mapped plus update counts read.
  - `driverCpuNs`: CPU on the calling thread inside the driver: `createStatement`, binds, `execute()` and the
    subscription to its publisher (encoding and, when the connection's event loop is the calling thread, the write).
  - `mappingCpuNs`: CPU inside the row mapping function of `Result.map` (the driver's column decoding, which
    r2dbc-postgresql does lazily in `row.get`, plus Spring Data's row -> entity or the store's own mapping), plus
    Spring Data's entity -> parameters conversion (`MappingR2dbcConverter.write`), which is charged to the next write
    statement created on the same thread.
- **operations**: `kind` and `key`:
  - `graphql`: every non-trivial data fetcher, `ParentType.field` (e.g. `Query.product`, `Product.variants`,
    `Mutation.createOrder`). Property fetchers are not wrapped; their CPU goes to the enclosing operation.
  - `entities`: `Query._entities`, as `_entities.<Type>` of the representations' `__typename`s (several types:
    joined with `+`).
  - `dataloader`: one call per batch of a `BaseIdDataLoader` (every data loader of the services, `IdDataLoader`s
    included) or of a data loader of its own that launches its batch with `DataLoaderTiming.launch` (none at the
    moment); key: the loader name, e.g. `ProductDataLoader`.
  - `event`: Dapr subscription deliveries, keyed by topic (several topics on one route: joined with `|`). The
    route -> topic map is learned from the app's own `GET /dapr/subscribe` answer, which the sidecar fetches when it
    starts; `@Topic` controller methods are recognised without it. Every service's event routes are `@Topic`
    methods of its `EventController` (`/subscription/<topic>`), so they are `event` operations from the first
    delivery, also after an app restart while its sidecar keeps running. order's shipment-failed route has no
    `@Topic` (it is not subscribed) and would appear as `http POST <route>`.
  - `http`: every other request, `METHOD route-pattern`. For example, `POST /graphql` holds whatever the request's
    coroutines and the filter's synchronous part do outside fetchers and statements:
    - the GraphQL engine's own work: request parsing, document parsing and validation, the execution strategy, and
      the trivial fields;
    - with the agent on, the agent's GraphQL instrumentation;
    - the response's serialisation and the write calls, which run synchronously when the handler coroutine
      completes.
  - `calls`: invocations; `cpuNs`: CPU of the operation, exclusive of nested operations and of the statement CPU
    measured inside it ("to the innermost"). Statements and operations are therefore disjoint and can be summed.
- **threadCpuNsTotal**: sum of the CPU time of all application threads (ThreadMXBean). Threads that ended keep their
  last sampled value, so the sum never decreases; a thread that lives and dies between two reads is missing. For
  coverage: `(Σ operations.cpuNs + Σ statements.(driver + mapping)) / ΔthreadCpuNsTotal`.

## How it is measured

- **Clock:** `ThreadMXBean.getCurrentThreadCpuTime()` = `clock_gettime(CLOCK_THREAD_CPUTIME_ID)` (123 ns per read
  on the dev VM).
- **Frames:** every thread keeps a stack of frames. Entering a frame charges the CPU since the last switch to the
  frame below; leaving charges the frame itself.
- **Coroutines:** `TimingCountersWebFilter` (highest precedence) puts a `RequestCpuElement` into the exchange
  attribute `CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE` (with `Dispatchers.Unconfined`, which is what WebFlux uses
  without the attribute). WebFlux uses it for `suspend` controller methods and `coRouter` handlers, and
  graphql-kotlin's execution scope inherits it, so every coroutine of a request carries it. The element is a
  `ThreadContextElement`: on every resume of a coroutine on a thread it pushes a frame for the coroutine's
  operation, on every suspension it pops it. A coroutine is bound to an operation at its first resume:
  1. a coroutine whose parent job is bound inherits its operation (`async`, `withContext`, `coroutineScope`);
  2. otherwise one that starts inside an explicit frame gets that frame's operation. The data fetcher wrapper (a
     graphql-java `Instrumentation`) and the data loader launch push explicit frames, and because the dispatcher
     is Unconfined, `scope.future {}` starts the fetcher's coroutine right there;
  3. otherwise the nearest operation frame, otherwise the request (the `http`/`event` operation).
- **Statements:** a `BeanPostProcessor` wraps the `ConnectionFactory` beans (Connection, Statement, Result
  delegates; `Wrapped` is implemented) and proxies Spring Data's `MappingR2dbcConverter`.

## Unified services (all 17 on the common paths)

Since the unification (branch `unify`, rebased onto it 2026-11-01) every service has the Kotlin originals' layering:
Spring Data repositories, `BaseConnection`s, `IdDataLoader`s, one `EventController` with `@Topic` methods,
`EventPublisher` and `DaprServiceInvoker`, and the common GraphQL context factory. The hooks the earlier ports needed
in their own context factories and data loaders went away with that code; nothing bypasses the generic hooks:

- **Context:** `AuthorizedUserGraphQLContextFactory` carries `TimingSpans.withTraceContext` for every service.
- **Repositories:** every repository is a Spring Data repository and has `repo <Entity>.<method>` spans (the
  ported services' own stores, which had none, are gone).
- **Condition queries:** `ConditionQueries` (the services' `Condition` queries outside connections: discount,
  invoice, order, payment, shoppingcart, wishlist) has the span `query <Entity>.<method>`
  (`findAll`/`count`/`exists` read, `delete`/`insert` write; `misarch.rows`).
- **Connections:** `ConnectionQuery` (`connectionQuery`, which `BaseConnection` and `NullableNodesConnection`
  delegate to) carries the timing span `connection <entity>` (`misarch.query` = `nodes` / `count` / `hasNextPage`);
  `<entity>` is the entity class, also for connection types that use `connectionQuery` directly (catalog's distinct
  values). Their `nodes`/`totalCount`/`hasNextPage` fields are `suspend` functions, so each is a `graphql` operation
  (`OrderItemConnection.nodes`, `ShoppingCartItemConnection.totalCount`, ...), and the page, COUNT and probe
  statements are three statement entries charged to them.
- **Data loaders:** all are `BaseIdDataLoader`s (`IdDataLoader` or their own `loadAll`, e.g. invoice's
  `OrderInvoiceDataLoader`): the batch is the `dataloader` operation and the span `dataloader <Entity>`.
- **Statements:** every statement goes through the wrapped `ConnectionFactory`, including link-table statements
  and the transactional aggregate writes. The `entity` of a page statement is the entity class.
- **Events and calls:** `publish <topic>` spans for every service (order, invoice and media used their own
  WebClient before), `invoke <app>.<method>` for order's checkout calls (`CheckoutClient` on `DaprServiceInvoker`).
- **Not spanned:** payment's and shipment's calls to their provider simulation (own WebClient, as before), and
  payment's scheduled jobs (no operation; their statements are counted).

## Coverage: what is not attributed (and not guessed)

- **Netty I/O and Postgres protocol decoding:** socket reads, and the decoding of backend messages before a row
  reaches the mapping function, run in the event loop outside any frame. So do the HTTP request bytes and the
  WebClient responses from the sidecar. Writes are counted where they run synchronously inside a frame: the HTTP
  response goes to the `http` operation, and a statement written from its connection's own event loop goes to
  `driverCpuNs`.
- **Reactor chains outside coroutine resumes:** Jackson's decoding of a request body before the handler resumes,
  response encoding after it ends, Spring Data/DatabaseClient operators between the mapping and the resume, and
  connection acquire/release.
- **Transaction statements** (`BEGIN`/`COMMIT`/`ROLLBACK`: `Connection.beginTransaction()` etc.) are not statements
  here. The aggregate writes of order (`createOrder`, compensation, user addresses), wishlist, shoppingcart and
  payment run in transactions: their row-wise INSERT/DELETE/UPDATE statements are counted one by one
  (one entry per statement text, `calls` = rows written), only the transaction boundaries are not.
- **Scheduled jobs:** payment's `@Scheduled` overdue-payment jobs run outside any request, so their CPU is not an
  operation (their statements are counted).
- **Other threads:** GC, JIT compiler threads (not in ThreadMXBean at all), the OpenTelemetry agent's exporter and
  logging.
- **Write conversions:** a conversion whose statement is created on another thread (asynchronous connection
  acquisition) is charged to the next write statement on the converting thread.

- **Driver CPU with the OpenTelemetry agent:** with the agent on (`--mode on`), `driverCpuNs` also contains the
  agent's R2DBC instrumentation: the client span and SQL sanitising, which run inside `execute()`'s subscription.

`threadCpuNsTotal` lets the harness report the attributed share honestly.

**Stack smoke (2026-10-28):**
- Setup: `--mode on`, images `:timing-20261029`, full baseline. Warm loop: 200 each of product, products,
  wishlistView and orderTracking.
- Coverage (attributed / thread CPU): catalog 58 %, order 49 %, wishlist 48 %, shipment 47 %, review 43 %.
- In the 36-type smoke right after start (cold JVMs) it was 27-60 %, 45 % overall.
- Catalog per request:
  - `POST /graphql` 3.4 ms;
  - `Query.products` 0.92 ms, `Product.categories` 0.70, `Query.product` 0.44, `Product.variants` 0.44;
  - per read statement 0.3-0.6 ms driver (agent included) and 0.004-0.09 ms mapping.

## Overhead

- **Microbenchmark** (`CpuCountersTest.cost of a frame`): one clock read 123 ns, one frame (enter + exit, two
  clock reads, thread-local stack) about 0.4 µs. Launching a coroutine that carries the element costs about 1.7 µs
  more than one without it (two frames plus the binding).
- **Per request** (`TimingCountersIntegrationTest`, catalog product query and `_entities`): 36 frames, i.e. about
  15 µs. That is ≈ 0.3 % of the measured service CPU of a request (RESULTS v2: 2-10 ms per request and service),
  plus a few small allocations per statement (wrappers, one `Flux.map`).
- This is well below the 2 % budget.

## Tests

- `libs/common` `CpuCountersTest`:
  - exclusive nesting;
  - coroutine binding across threads (a fetcher coroutine resumed on another thread, an inheriting child, a
    coroutine without an explicit frame going to the request);
  - SQL normalisation, mode and table;
  - the snapshot's field order and the thread total;
  - the cost microbenchmark.
- `libs/common` `TimingCountersR2dbcTest` (Postgres):
  - with the switch off, no wrapping;
  - with it on, the same results, including a transaction rollback;
  - per-statement keys, modes, entities, rows, driver and mapping CPU (including the converter);
  - the data loader operation.
- catalog `TimingCountersIntegrationTest`:
  - one operation of every kind: `graphql`, `entities`, `dataloader`, `event` by topic (learned from
    `/dapr/subscribe`), `http`;
  - statements;
  - the exact JSON fields of `GET /timing/counters`;
  - attributed CPU below the thread total.
- All service suites also pass with `MISARCH_TIMING_COUNTERS=true`.
