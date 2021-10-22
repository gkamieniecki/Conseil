package tech.cryptonomic.conseil.indexer.config

import tech.cryptonomic.conseil.common.config.ChainEvent
import tech.cryptonomic.conseil.indexer.config.ConfigUtil.Depth._

import scala.concurrent.duration.FiniteDuration

/** configurations related to a chain-node network calls */
final case class NetworkCallsConfiguration(
    requestAwaitTime: FiniteDuration,
    GETResponseEntityTimeout: FiniteDuration,
    POSTResponseEntityTimeout: FiniteDuration
)

/** generic configuration for the lorre */
case class LorreConfiguration(
    sleepInterval: FiniteDuration,
    bootupRetryInterval: FiniteDuration,
    bootupConnectionCheckTimeout: FiniteDuration,
    feeUpdateInterval: Int,
    feesAverageTimeWindow: FiniteDuration,
    depth: Depth,
    headHash: Option[String],
    chainEvents: List[ChainEvent],
    blockRightsFetching: BakingAndEndorsingRights,
    tokenContracts: TokenContracts,
    metadataFetching: TzipMetadata,
    forkHandling: ForkHandling,
    enabledFeatures: Features
)

final case class LorreConfigurationHelper(
    sleepInterval: FiniteDuration,
    bootupRetryInterval: FiniteDuration,
    bootupConnectionCheckTimeout: FiniteDuration,
    feeUpdateInterval: Int,
    feesAverageTimeWindow: FiniteDuration,
    depth: String,
    headHash: Option[String],
    chainEvents: List[ChainEvent],
    blockRightsFetching: BakingAndEndorsingRights,
    tokenContracts: TokenContracts,
    metadataFetching: TzipMetadata,
    enabledFeatures: Features
) {
  def toConf: LorreConfiguration =
    new LorreConfiguration(
      sleepInterval,
      bootupRetryInterval,
      bootupConnectionCheckTimeout,
      feeUpdateInterval,
      feesAverageTimeWindow,
      depth.toDepth.getOrElse(Newest),
      headHash,
      chainEvents,
      blockRightsFetching,
      tokenContracts,
      metadataFetching,
      enabledFeatures
    )
}

/** configuration for fetching baking and endorsing rights */
final case class BakingAndEndorsingRights(
    initDelay: FiniteDuration,
    interval: FiniteDuration,
    cyclesToFetch: Int,
    cycleSize: Int,
    fetchSize: Int,
    updateSize: Int
)

/** configuration for fetching baking and endorsing rights */
final case class TzipMetadata(
    initDelay: FiniteDuration,
    interval: FiniteDuration
)

/** details how to handle data pagination when fetching from the chain */
final case class BatchFetchConfiguration(
    accountConcurrencyLevel: Int,
    blockOperationsConcurrencyLevel: Int,
    blockPageSize: Int,
    blockPageProcessingTimeout: FiniteDuration,
    accountPageProcessingTimeout: FiniteDuration,
    delegatePageProcessingTimeout: FiniteDuration
)

/** custom select specific features to be enabled when chain-indexing */
final case class Features(
    blockRightsFetchingIsOn: Boolean,
    forkHandlingIsOn: Boolean,
    metadataFetchingIsOn: Boolean,
    registeredTokensIsOn: Boolean
)

final case class TokenContracts(
    url: String,
    initialDelay: FiniteDuration,
    interval: FiniteDuration
)

final case class ForkHandling(
    backtrackLevels: Int,
    backtrackInterval: Int
)

/** sodium library references */
final case class SodiumConfiguration(libraryPath: String) extends AnyVal with Product with Serializable

/** holds custom-verified lightbend configuration for the akka-http-client hostpool used to stream requests */
final case class HttpStreamingConfiguration(pool: com.typesafe.config.Config)
