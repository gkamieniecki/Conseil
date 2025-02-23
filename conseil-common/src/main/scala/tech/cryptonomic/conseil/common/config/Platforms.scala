package tech.cryptonomic.conseil.common.config

import com.typesafe.config.Config
import slick.jdbc.JdbcBackend.Database

import java.net.URL
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.{Network, Platform}

import scala.concurrent.duration.Duration

/** defines configuration types for conseil available platforms */
object Platforms {

  /** a trait defining existing platforms */
  sealed trait BlockchainPlatform extends Product with Serializable {

    /** a name usually used in configurations to identify the platform */
    def name: String
  }

  /** Represents blockchain platform for Tezos */
  case object Tezos extends BlockchainPlatform {
    val name = "tezos"
  }

  /** Represents blockchain platform for Bitcoin */
  case object Bitcoin extends BlockchainPlatform {
    val name = "bitcoin"
  }

  /** Represents blockchain platform for Ethereum */
  case object Ethereum extends BlockchainPlatform {
    val name: String = "ethereum"
  }

  /** Represents blockchain platform for Quorum */
  case object Quorum extends BlockchainPlatform {
    val name: String = "quorum"
  }

  object BlockchainPlatform {

    /** maps a generic string to a typed BlockchainPlatform */
    def fromString(s: String): BlockchainPlatform = s match {
      // Note that we are not handling match-error,
      // due to the fact that unknown platforms will be handled at configuration reading level
      case Tezos.name => Tezos
      case Bitcoin.name => Bitcoin
      case Ethereum.name => Ethereum
      case Quorum.name => Quorum
    }
  }

  /**
    * Collects all platforms configuration in a list.
    *
    * To access specific type of the configuration,
    * - match the inner `BlockchainPlatform` type over one of supported platforms,
    * - match the outer `PlatformConfiguration` type over one of specified platform's configuration.
    *
    * Keep in mind, that specific platform's configuration can be enabled or disabled.
    */
  case class PlatformsConfiguration(platforms: List[PlatformConfiguration]) {

    /*** Extracts platforms from configuration */
    def getPlatforms(enabled: Boolean = true): List[Platform] =
      platforms
        .filter(_.enabled == enabled)
        .map(_.platform)
        .map(platform => Platform(platform.name, platform.name.capitalize))

    /*** Extracts networks from configuration */
    def getNetworks(platformName: String, enabled: Boolean = true): List[Network] =
      platforms
        .filter(v => v.platform.name == platformName && v.enabled == enabled)
        .map { config =>
          Network(config.network, config.network.capitalize, config.platform.name, config.network)
        }

    def getDbConfig(platformName: String, network: String, enabled: Boolean = true): Config =
      platforms
        .filter(v => v.platform.name == platformName && v.network == network && v.enabled == enabled)
        .map(_.db)
        .head

    def getDatabases(enabled: Boolean = true): Map[(String, String), Database] =
      platforms
        .filter(_.enabled == enabled)
        .map(c => (c.platform.name, c.network) -> Database.forConfig("", c.db))
        .toMap

  }

  /** configurations to describe a tezos node */
  final case class TezosNodeConfiguration(
      hostname: String,
      port: Int,
      protocol: String,
      pathPrefix: String = "",
      chainEnv: String = "main",
      traceCalls: Boolean = false
  )

  /** Defines chain-specific values to identify the TNS (Tezos Naming Service) smart contract */
  final case class TNSContractConfiguration(name: String, contractType: String, accountId: String)

  /** generic trait for any platform configuration, where each instance corresponds to a network available on that chain */
  sealed trait PlatformConfiguration extends Product with Serializable {

    /** Defines the blockchain platform that configuration belongs to */
    def platform: BlockchainPlatform

    /** Defines whether this specific configuration is enabled */
    def enabled: Boolean

    /** Defines the name of the network for specific blockchain */
    def network: String

    /** View on the db config object */
    def db: Config
  }

  /** collects all config related to a tezos network */
  final case class TezosConfiguration(
      network: String,
      enabled: Boolean,
      node: TezosNodeConfiguration,
      bakerRollsSize: BigDecimal,
      db: Config,
      tns: Option[TNSContractConfiguration]
  ) extends PlatformConfiguration {
    override val platform: BlockchainPlatform = Tezos
  }

  /** configurations to describe a bitcoin node */
  final case class BitcoinNodeConfiguration(
      hostname: String,
      port: Int,
      protocol: String,
      username: String,
      password: String
  ) {
    val url = s"$protocol://$hostname:$port"
  }

  /** configurations to describe a bitcoin batch fetch */
  final case class BitcoinBatchFetchConfiguration(
      indexerThreadsCount: Int,
      httpFetchThreadsCount: Int,
      hashBatchSize: Int,
      blocksBatchSize: Int,
      transactionsBatchSize: Int
  )

  /** collects all config related to a bitcoin network */
  final case class BitcoinConfiguration(
      network: String,
      enabled: Boolean,
      node: BitcoinNodeConfiguration,
      db: Config,
      batching: BitcoinBatchFetchConfiguration
  ) extends PlatformConfiguration {
    override val platform: BlockchainPlatform = Bitcoin
  }

  /**
    * Configurations to describe a Ethereum retry policy.
    *
    * @param maxWait Max wait time between attempts
    * @param maxRetry retry count
    */
  final case class EthereumRetryConfiguration(
      maxWait: Duration,
      maxRetry: Int
  )

  /**
    * Configurations to describe a Ethereum batch fetch.
    *
    * @param indexerThreadsCount The number of threads used by the Lorre process
    * @param httpFetchThreadsCount The number of theread used by the http4s
    * @param blocksBatchSize The number of the blocks batched into one JSON-RPC request
    * @param transactionsBatchSize The number of the transactions batched into one JSON-RPC request
    * @param contractsBatchSize The number of the contracts batched into one JSON-RPC request
    * @param tokensBatchSize The number of the tokens batched into one JSON-RPC request
    */
  final case class EthereumBatchFetchConfiguration(
      indexerThreadsCount: Int,
      httpFetchThreadsCount: Int,
      blocksBatchSize: Int,
      transactionsBatchSize: Int,
      contractsBatchSize: Int,
      tokensBatchSize: Int
  )

  /** collects all config related to a Ethereum network */
  final case class EthereumConfiguration(
      network: String,
      enabled: Boolean,
      node: URL,
      db: Config,
      retry: EthereumRetryConfiguration,
      batching: EthereumBatchFetchConfiguration
  ) extends PlatformConfiguration {
    override val platform: BlockchainPlatform = Ethereum
  }

  /** collects all config related to a Quorum network */
  final case class QuorumConfiguration(
      network: String,
      enabled: Boolean,
      node: URL,
      db: Config,
      retry: EthereumRetryConfiguration,
      batching: EthereumBatchFetchConfiguration
  ) extends PlatformConfiguration {
    override val platform: BlockchainPlatform = Quorum

    lazy val toEthereumConfiguration = EthereumConfiguration(network, enabled, node, db, retry, batching)
  }

}
