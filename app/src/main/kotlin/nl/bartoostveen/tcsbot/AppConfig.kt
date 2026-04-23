package nl.bartoostveen.tcsbot

import io.github.crackthecodeabhi.kreds.connection.Endpoint
import io.github.crackthecodeabhi.kreds.connection.newClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import nl.bartoostveen.tcsbot.database.*
import nl.bartoostveen.tcsbot.util.dataSource
import nl.bartoostveen.tcsbot.util.printException
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

object AppConfig {
  private val id: (String) -> String = { it }
  private val int: (String) -> Int = { it.toInt() }
  private fun <T> list(
    delimiter: Char = ',',
    mapper: (String) -> T
  ): (String) -> List<T> = { it.split(delimiter).map(mapper) }

  private fun list(delimiter: Char = ',') = list(delimiter, id)

  private inline fun <reified T : Enum<T>> enum(): (String) -> T = {
    enumValues<T>().first { value -> value.name.equals(it, ignoreCase = true) }
  }

  private val required = { error("Property required, but not given!") }

  private fun <T> variable(
    default: () -> T,
    mapper: (String) -> T,
    envName: String? = null
  ) = PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> { _, property ->
    val name = envName ?: property.name
    ReadOnlyProperty { _, _ ->
      runCatching {
        env[name]?.let { mapper(it) } ?: default()
      }.getOrElse { th ->
        throw RuntimeException("Property $name in Configuration had an error mapping its value", th)
      }
    }
  }

  private fun variable(envName: String? = null) = variable(
    default = required,
    mapper = id,
    envName = envName
  )

  enum class Environment {
    DEVELOPMENT, PRODUCTION
  }

  val DISCORD_ACCESS_TOKEN by variable()
  val CANVAS_ACCESS_TOKEN by variable()
  val CANVAS_BASE_URL by variable({ "https://canvas.utwente.nl" }, id)
  val CANVAS_CA_BUNDLE by variable({ null }, id)

  val REDIS_CONNECTION_STRING by variable({ "localhost:6379" }, id)
  val DATABASE_CONNECTION_STRING by variable()
  val DATABASE_USERNAME by variable({ "" }, id)
  val DATABASE_PASSWORD by variable({ "" }, id)

  val MICROSOFT_CLIENT_ID by variable()
  val MICROSOFT_CLIENT_SECRET by variable()
  val MICROSOFT_AUTH_ENDPOINT by variable()

  val HOST by variable({ "0.0.0.0" }, id)
  val PORT by variable({ 6969 }, int)
  val HOSTNAME by variable()
  val ENVIRONMENT by variable({ Environment.PRODUCTION }, enum<Environment>())
  val METRICS_PREFIX by variable({ null }, id)

  val DISCORD_DEPLOYER_ID by variable(required, list())

  val redisClient by lazy {
    runCatching { newClient(Endpoint.from(REDIS_CONNECTION_STRING)) }
      .printException()
      .getOrNull()
  }

  @OptIn(FlowPreview::class)
  val database by lazy {
    Database.connect(
      dataSource {
        jdbcUrl = DATABASE_CONNECTION_STRING
        username = DATABASE_USERNAME
        password = DATABASE_PASSWORD
        maximumPoolSize = DEFAULT_CONCURRENCY
        metricRegistry = metricsRegistry
      },
      databaseConfig = DatabaseConfig {
        keepLoadedReferencesOutOfTransaction = true
      }
    ).also {
      transaction(db = it) {
        migrate()
      }
    }
  }

  fun Transaction.migrate(onlyCreate: Boolean = false) {
    addLogger(StdOutSqlLogger)

    val tables = arrayOf(
      Guilds,
      Members,
      GuildMembers,
      GuildRoles,
      Courses
    )

    @Suppress("deprecation", "RedundantSuppression") // why
    if (onlyCreate) SchemaUtils.create(
      *tables,
      inBatch = true
    ) else SchemaUtils.createMissingTablesAndColumns(
      *tables,
      inBatch = true,
      withLogs = true
    )
  }

  val httpClient by lazy {
    HttpClient(CIO) {
      install(Logging) {
        level = if (ENVIRONMENT == Environment.DEVELOPMENT) LogLevel.ALL else LogLevel.INFO
      }
      install(ContentNegotiation) { json(json) }
      install(HttpRequestRetry) {
        exponentialDelay()
        retryOnException(
          maxRetries = 3,
          retryOnTimeout = true
        )
      }
      install(HttpCache)
    }
  }

  val metricsRegistry by lazy {
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  }
}
