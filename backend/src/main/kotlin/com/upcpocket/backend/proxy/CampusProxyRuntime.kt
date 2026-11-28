package com.upcpocket.backend.proxy

import com.upcpocket.backend.contract.CampusModuleAdapter
import com.upcpocket.backend.contract.CampusModuleKey
import com.upcpocket.backend.contract.ModuleCircuitState
import com.upcpocket.backend.service.ApiErrorCode
import com.upcpocket.backend.service.ApiException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@ConfigurationProperties("pocket.proxy")
data class CampusProxyProperties(
    val modules: Map<String, ModuleRuntimeProperties> = defaultModuleRuntimeProperties(),
    val singleFlight: SingleFlightProperties = SingleFlightProperties(),
    val accountRateLimit: AccountRateLimitProperties = AccountRateLimitProperties(),
) {
    fun module(key: CampusModuleKey): ModuleRuntimeProperties = modules[key.wireName] ?: ModuleRuntimeProperties()

    companion object {
        private fun defaultModuleRuntimeProperties() = CampusModuleKey.entries.associate {
            it.wireName to ModuleRuntimeProperties()
        }
    }
}

data class ModuleRuntimeProperties(
    val timeoutMs: Long = 15_000,
    val maxConcurrency: Int = 4,
    val queueCapacity: Int = 8,
    val circuitFailureThreshold: Int = 3,
    val circuitCooldownMs: Long = 30_000,
) {
    init {
        require(timeoutMs in 100..120_000)
        require(maxConcurrency in 1..64)
        require(queueCapacity in 1..512)
        require(circuitFailureThreshold in 1..20)
        require(circuitCooldownMs in 100..600_000)
    }
}

data class SingleFlightProperties(
    val maxEntries: Int = 512,
    val maxWaitersPerEntry: Int = 32,
    val waitTimeoutMs: Long = 15_000,
) {
    init {
        require(maxEntries in 1..10_000)
        require(maxWaitersPerEntry in 1..1_000)
        require(waitTimeoutMs in 100..120_000)
    }
}

data class AccountRateLimitProperties(
    val windowMs: Long = 60_000,
    val maxRequests: Int = 60,
    val maxTrackedAccounts: Int = 100_000,
) {
    init {
        require(windowMs in 1_000..3_600_000)
        require(maxRequests in 1..10_000)
        require(maxTrackedAccounts in 1..1_000_000)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CampusProxyProperties::class)
class CampusProxyRuntimeConfiguration {
    @Bean
    fun campusModuleAdapterRegistry(adapters: List<CampusModuleAdapter>) = CampusModuleAdapterRegistry(adapters)

    @Bean
    fun moduleCallExecutor(properties: CampusProxyProperties) = ModuleCallExecutor(properties)

    @Bean
    fun moduleCircuitBreakers(properties: CampusProxyProperties, clock: Clock) = ModuleCircuitBreakers(properties, clock)

    @Bean
    fun accountBusinessRateLimiter(properties: CampusProxyProperties, clock: Clock) =
        AccountBusinessRateLimiter(properties.accountRateLimit, clock)

    @Bean
    fun boundedSingleFlight(properties: CampusProxyProperties) = BoundedSingleFlight(properties.singleFlight)
}

class CampusModuleAdapterRegistry(adapters: List<CampusModuleAdapter>) {
    private val byModule = adapters.associateBy { it.moduleKey }

    init {
        require(CampusModuleKey.entries.all(byModule::containsKey)) { "A module adapter is missing" }
    }

    operator fun get(module: CampusModuleKey): CampusModuleAdapter =
        byModule[module] ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)

    fun ready(module: CampusModuleKey): Boolean = this[module].adapterReady
}

class ModuleCallExecutor(properties: CampusProxyProperties) : AutoCloseable {
    private val executors: Map<CampusModuleKey, ThreadPoolExecutor> = CampusModuleKey.entries.associateWith { key ->
        val config = properties.module(key)
        ThreadPoolExecutor(
            config.maxConcurrency,
            config.maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(config.queueCapacity),
            NamedDaemonThreadFactory("pocket-${key.wireName}"),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun <T> call(module: CampusModuleKey, timeoutMs: Long, operation: () -> T): T {
        val executor = executors[module] ?: throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        val physicalTask = PhysicalTask()
        val future = try {
            executor.submit<T> {
                if (!physicalTask.enter()) throw CancellationException()
                try {
                    operation()
                } finally {
                    physicalTask.complete()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
        }
        try {
            return future.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            physicalTask.cancelIfNotStarted()
            (future as? Runnable)?.let(executor::remove)
            throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT, completionSignal = physicalTask.completed)
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is ApiException -> throw cause
                is com.upcpocket.backend.contract.CampusAdapterException -> throw ApiException(cause.errorCode)
                else -> throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        } catch (_: CancellationException) {
            throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT)
        }
    }

    override fun close() {
        executors.values.forEach { it.shutdownNow() }
    }

    private class PhysicalTask {
        val completed = CompletableFuture<Unit>()
        private var entered = false
        private var cancelledBeforeStart = false

        @Synchronized
        fun enter(): Boolean {
            if (cancelledBeforeStart) {
                completed.complete(Unit)
                return false
            }
            entered = true
            return true
        }

        fun complete() {
            completed.complete(Unit)
        }

        @Synchronized
        fun cancelIfNotStarted() {
            if (!entered) {
                cancelledBeforeStart = true
                completed.complete(Unit)
            }
        }
    }

    private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val sequence = AtomicInteger()
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}

class ModuleCircuitBreakers(
    private val properties: CampusProxyProperties,
    private val clock: Clock,
) {
    private val breakers = CampusModuleKey.entries.associateWith { key ->
        ModuleCircuitBreaker(properties.module(key), clock)
    }

    fun acquire(module: CampusModuleKey): ModuleCircuitPermit? = breakers.getValue(module).acquire()
    fun recordSuccess(module: CampusModuleKey, permit: ModuleCircuitPermit) = breakers.getValue(module).success(permit)
    fun recordFailure(module: CampusModuleKey, permit: ModuleCircuitPermit, counted: Boolean) =
        breakers.getValue(module).failure(permit, counted)
    fun state(module: CampusModuleKey): ModuleCircuitState = breakers.getValue(module).state()
}

class ModuleCircuitBreaker(
    private val config: ModuleRuntimeProperties,
    private val clock: Clock,
) {
    private var state = ModuleCircuitState.CLOSED
    private var failures = 0
    private var openedAtMillis = 0L
    private var generation = 0L
    private var probeInFlight = false

    @Synchronized
    fun acquire(): ModuleCircuitPermit? {
        if (state == ModuleCircuitState.OPEN) {
            if (clock.millis() - openedAtMillis < config.circuitCooldownMs) return null
            state = ModuleCircuitState.HALF_OPEN
            probeInFlight = true
            generation = Math.addExact(generation, 1L)
            return ModuleCircuitPermit(generation, halfOpenProbe = true)
        }
        if (state == ModuleCircuitState.HALF_OPEN) {
            if (probeInFlight) return null
            probeInFlight = true
            generation = Math.addExact(generation, 1L)
            return ModuleCircuitPermit(generation, halfOpenProbe = true)
        }
        return ModuleCircuitPermit(generation, halfOpenProbe = false)
    }

    @Synchronized
    fun success(permit: ModuleCircuitPermit) {
        if (permit.generation != generation) return
        if (permit.halfOpenProbe && state == ModuleCircuitState.HALF_OPEN) {
            state = ModuleCircuitState.CLOSED
            probeInFlight = false
            failures = 0
            generation = Math.addExact(generation, 1L)
        } else if (state == ModuleCircuitState.CLOSED) {
            failures = 0
        }
    }

    @Synchronized
    fun failure(permit: ModuleCircuitPermit, counted: Boolean) {
        if (permit.generation != generation) return
        if (permit.halfOpenProbe && state == ModuleCircuitState.HALF_OPEN) {
            probeInFlight = false
            if (counted) {
                state = ModuleCircuitState.OPEN
                openedAtMillis = clock.millis()
                failures = config.circuitFailureThreshold
            }
            generation = Math.addExact(generation, 1L)
            return
        }
        if (state != ModuleCircuitState.CLOSED || !counted) return
        failures += 1
        if (failures >= config.circuitFailureThreshold) {
            state = ModuleCircuitState.OPEN
            openedAtMillis = clock.millis()
            generation = Math.addExact(generation, 1L)
        }
    }

    @Synchronized
    fun state(): ModuleCircuitState = if (
        state == ModuleCircuitState.OPEN && clock.millis() - openedAtMillis >= config.circuitCooldownMs
    ) ModuleCircuitState.HALF_OPEN else state
}

data class ModuleCircuitPermit internal constructor(val generation: Long, val halfOpenProbe: Boolean)

class AccountBusinessRateLimiter(
    private val config: AccountRateLimitProperties,
    private val clock: Clock,
) {
    private data class Counter(var startedAtMillis: Long, var count: Int)
    private val counters = LinkedHashMap<UUID, Counter>(16, 0.75f, true)

    @Synchronized
    fun check(accountId: UUID) {
        val now = clock.millis()
        val expired = counters.entries.filter { now - it.value.startedAtMillis >= config.windowMs }.map { it.key }
        expired.forEach(counters::remove)
        var counter = counters[accountId]
        if (counter == null) {
            if (counters.size >= config.maxTrackedAccounts) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
            counter = Counter(now, 0)
            counters[accountId] = counter
        }
        if (counter.count >= config.maxRequests) {
            val retryAfter = ((config.windowMs - (now - counter.startedAtMillis)).coerceAtLeast(1) + 999) / 1000
            throw ApiException(ApiErrorCode.RATE_LIMITED, retryAfter)
        }
        counter.count += 1
    }
}

class BoundedSingleFlight(private val config: SingleFlightProperties) {
    private data class SharedOutcome(val retry: Boolean = false, val errorCode: ApiErrorCode? = null)

    private data class Flight(
        val completed: CompletableFuture<SharedOutcome> = CompletableFuture(),
        var waiters: Int = 0,
    )

    private val lock = Any()
    private val entries = HashMap<SingleFlightKey, Flight>()

    fun execute(key: SingleFlightKey, deadlineNanos: Long, leader: () -> Unit) {
        var isLeader = false
        val flight = synchronized(lock) {
            val existing = entries[key]
            if (existing != null) {
                if (existing.waiters >= config.maxWaitersPerEntry) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                existing.waiters += 1
                existing
            } else {
                if (entries.size >= config.maxEntries) throw ApiException(ApiErrorCode.DEPENDENCY_UNAVAILABLE)
                Flight().also {
                    entries[key] = it
                    isLeader = true
                }
            }
        }

        if (isLeader) {
            var deferRemoval = false
            try {
                leader()
                flight.completed.complete(SharedOutcome())
            } catch (error: ApiException) {
                val callerScoped = error.code in CALLER_SCOPED_ERRORS
                val physicalCompletion = error.completionSignal
                if (callerScoped && physicalCompletion != null && !physicalCompletion.isDone) {
                    deferRemoval = true
                    physicalCompletion.whenComplete {
                            _, _ ->
                        synchronized(lock) { entries.remove(key, flight) }
                        flight.completed.complete(SharedOutcome(retry = true))
                    }
                } else {
                    if (callerScoped) synchronized(lock) { entries.remove(key, flight) }
                    flight.completed.complete(
                        if (callerScoped) SharedOutcome(retry = true) else SharedOutcome(errorCode = error.code),
                    )
                    if (physicalCompletion != null && !physicalCompletion.isDone) {
                        deferRemoval = true
                        physicalCompletion.whenComplete { _, _ -> synchronized(lock) { entries.remove(key, flight) } }
                    }
                }
                throw error
            } catch (_: Exception) {
                flight.completed.complete(SharedOutcome(errorCode = ApiErrorCode.UPSTREAM_UNAVAILABLE))
                throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
            } finally {
                if (!deferRemoval) synchronized(lock) { entries.remove(key, flight) }
            }
            return
        }

        try {
            val remainingNanos = minOf(
                deadlineNanos - System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(config.waitTimeoutMs),
            )
            if (remainingNanos <= 0) throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT)
            val outcome = flight.completed.get(remainingNanos, TimeUnit.NANOSECONDS)
            if (outcome.retry) return
            outcome.errorCode?.let { throw ApiException(it) }
            return
        } catch (_: TimeoutException) {
            throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT)
        } catch (error: ExecutionException) {
            throw ApiException(ApiErrorCode.UPSTREAM_UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException(ApiErrorCode.UPSTREAM_TIMEOUT)
        } finally {
            synchronized(lock) { flight.waiters = (flight.waiters - 1).coerceAtLeast(0) }
        }
    }

    internal fun activeWaiterCount(): Int = synchronized(lock) { entries.values.sumOf { it.waiters } }

    internal fun activeFlightCount(): Int = synchronized(lock) { entries.size }

    companion object {
        private val CALLER_SCOPED_ERRORS = setOf(
            ApiErrorCode.TOKEN_INVALID,
            ApiErrorCode.CREDENTIAL_UPDATE_REQUIRED,
        )
    }
}

data class SingleFlightKey(
    val accountId: UUID,
    val credentialGeneration: Long,
    val serviceKey: String,
)
