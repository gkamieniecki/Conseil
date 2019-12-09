package tech.cryptonomic.conseil

import akka.actor.ActorSystem
import akka.Done
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.ActorMaterializer
import mouse.any._
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.tezos.{
  ApiOperations,
  DatabaseConversions,
  FeeOperations,
  ShutdownComplete,
  TezosErrors,
  TezosNodeInterface,
  TezosNodeOperator,
  TezosTypes,
  TezosDatabaseOperations => TezosDb
}
import tech.cryptonomic.conseil.tezos.TezosTypes._
import tech.cryptonomic.conseil.io.MainOutputs.LorreOutput
import tech.cryptonomic.conseil.util.DatabaseUtil
import tech.cryptonomic.conseil.tezos.TezosDatabaseOperations.VotingData
import tech.cryptonomic.conseil.config.{ChainEvent, Custom, Everything, LorreAppConfig, Newest, Platforms}
import tech.cryptonomic.conseil.tezos.TezosNodeOperator.FetchRights

import scala.concurrent.duration._
import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import scala.collection.SortedSet
import tech.cryptonomic.conseil.tezos.TezosTypes.BlockHash

/**
  * Entry point for synchronizing data between the Tezos blockchain and the Conseil database.
  */
object Lorre extends App with TezosErrors with LazyLogging with LorreAppConfig with LorreOutput {

  type AccountUpdatesEvents = SortedSet[(Int, ChainEvent.AccountIdPattern)]

  //reads all configuration upstart, will only complete if all values are found

  val config = loadApplicationConfiguration(args)

  //stop if conf is not available
  config.left.foreach { _ =>
    sys.exit(1)
  }

  //unsafe call, will only be reached if loadedConf is a Right, otherwise the merge will fail
  val LorreAppConfig.CombinedConfiguration(
    lorreConf,
    tezosConf,
    callsConf,
    streamingClientConf,
    batchingConf,
    verbose
  ) = config.merge
  val ignoreProcessFailures = sys.env.get(LORRE_FAILURE_IGNORE_VAR)

  //the dispatcher is visible for all async operations in the following code
  implicit val system: ActorSystem = ActorSystem("lorre-system")
  implicit val dispatcher = system.dispatcher

  //how long to wait for graceful shutdown of system components
  val shutdownWait = 10.seconds

  //whatever happens we try to clean up
  sys.addShutdownHook(shutdown())

  lazy val db = DatabaseUtil.lorreDb
  lazy val apiOperations = new ApiOperations

  val tezosNodeOperator = new TezosNodeOperator(
    new TezosNodeInterface(tezosConf, callsConf, streamingClientConf),
    tezosConf.network,
    batchingConf,
    apiOperations
  )

  // starts processing Tezos votes
  //system.scheduler.scheduleOnce(1.minute)(processTezosVotes())

  def processVotes: Future[Done] = {
    processTezosVotes().flatMap(_ => processVotes)
  }

  /** close resources for application stop */
  private[this] def shutdown(): Unit = {
    logger.info("Doing clean-up")
    db.close()
    val nodeShutdown =
      tezosNodeOperator.node
        .shutdown()
        .flatMap((_: ShutdownComplete) => system.terminate())

    Await.result(nodeShutdown, shutdownWait)
    logger.info("All things closed")
  }

  /** Tries to fetch blocks head to verify if connection with Tezos node was successfully established */
  @tailrec
  private[this] def checkTezosConnection(): Unit =
    Try {
      Await.result(tezosNodeOperator.getBareBlockHead(), lorreConf.bootupConnectionCheckTimeout)
    } match {
      case Failure(e) =>
        logger.error("Could not make initial connection to Tezos", e)
        Thread.sleep(lorreConf.bootupRetryInterval.toMillis)
        checkTezosConnection()
      case Success(_) =>
        logger.info("Successfully made initial connection to Tezos")
    }

  // Finds unprocessed levels for account refreshes (i.e. when there is a need to reload all accounts data from the chain)
  private def unprocessedLevelsForRefreshingAccounts(): Future[AccountUpdatesEvents] =
    lorreConf.chainEvents.collectFirst {
      case ChainEvent.AccountsRefresh(levelsNeedingRefresh) if levelsNeedingRefresh.nonEmpty =>
        db.run(TezosDb.fetchProcessedEventsLevels(ChainEvent.accountsRefresh.render)).map { levels =>
          //used to remove processed events
          val processed = levels.map(_.intValue).toSet
          //we want individual event levels with the associated pattern, such that we can sort them by level
          val unprocessedEvents = levelsNeedingRefresh.toList.flatMap {
            case (accountPattern, levels) => levels.filterNot(processed).sorted.map(_ -> accountPattern)
          }
          SortedSet(unprocessedEvents: _*)
        }
    }.getOrElse(Future.successful(SortedSet.empty))

  /** The regular loop, once connection with the node is established */
  @tailrec
  private[this] def mainLoop(
      iteration: Int,
      accountsRefreshLevels: AccountUpdatesEvents
  ): Unit = {
    val noOp = Future.successful(())
    val processing = for {
      nextRefreshes <- processAccountRefreshes(accountsRefreshLevels)
      _ <- processTezosBlocks()
//      _ <- processTezosVotes()
      _ <- if (iteration % lorreConf.feeUpdateInterval == 0)
        FeeOperations.processTezosAverageFees(lorreConf.numberOfFeesAveraged)
      else
        noOp
    } yield Some(nextRefreshes)

    /* Won't stop Lorre on failure from processing the chain, unless overridden by the environment to halt.
     * Can be used to investigate issues on consistently failing block or account processing.
     * Otherwise, any error will make Lorre proceed as expected by default (stop or wait for next cycle)
     */
    val attemptedProcessing =
      if (ignoreProcessFailures.exists(ignore => ignore == "true" || ignore == "yes"))
        processing.recover {
          //swallow the error and proceed with the default behaviour
          case f @ (AccountsProcessingFailed(_, _) | BlocksProcessingFailed(_, _) | DelegatesProcessingFailed(_, _)) =>
            logger.error("Failed processing but will keep on going next cycle", f)
            None //we have no meaningful response to provide
        } else processing

    //if something went wrong and wasn't recovered, this will actually blow the app
    val updatedLevels = Await.result(attemptedProcessing, atMost = Duration.Inf)

    lorreConf.depth match {
      case Newest =>
        logger.info("Taking a nap")
        Thread.sleep(lorreConf.sleepInterval.toMillis)
        mainLoop(iteration + 1, updatedLevels.getOrElse(accountsRefreshLevels))
      case _ =>
        logger.info("Synchronization is done")
    }
  }

  displayInfo(tezosConf)
  if (verbose.on)
    displayConfiguration(Platforms.Tezos, tezosConf, lorreConf, (LORRE_FAILURE_IGNORE_VAR, ignoreProcessFailures))

  try {
    checkTezosConnection()
    val accountRefreshesToRun = Await.result(unprocessedLevelsForRefreshingAccounts(), atMost = 5.seconds)
    mainLoop(0, accountRefreshesToRun)
  } finally {
    shutdown()
  }

  /* Possibly updates all accounts if the current block level is past any of the given ones
   * @param events the relevant levels, each with its own selection pattern, that calls for a refresh
   */
  private def processAccountRefreshes(events: AccountUpdatesEvents): Future[AccountUpdatesEvents] =
    if (events.nonEmpty) {
      for {
        storedHead <- apiOperations.fetchMaxLevel
        updated <- if (events.exists(_._1 <= storedHead)) {
          val (past, toCome) = events.partition(_._1 <= storedHead)
          val (levels, selectors) = past.unzip
          logger.info(
            "A block was reached that requires an update of account data as specified in the configuration file. A full refresh is now underway. Relevant block levels: {}",
            levels.mkString(", ")
          )
          apiOperations.fetchBlockAtLevel(levels.max).flatMap {
            case Some(referenceBlockForRefresh) =>
              val (hashRef, levelRef, timestamp, cycle) =
                (
                  BlockHash(referenceBlockForRefresh.hash),
                  referenceBlockForRefresh.level,
                  referenceBlockForRefresh.timestamp.toInstant(),
                  referenceBlockForRefresh.metaCycle
                )
              db.run(
                  //put all accounts in checkpoint, log the past levels to the db, keep the rest for future cycles
                  TezosDb.refillAccountsCheckpointFromExisting(hashRef, levelRef, timestamp, cycle, selectors.toSet) >>
                      TezosDb.writeProcessedEventsLevels(
                        ChainEvent.accountsRefresh.render,
                        levels.map(BigDecimal(_)).toList
                      )
                )
                .andThen {
                  case Success(accountsCount) =>
                    logger.info(
                      "Checkpoint stored for{} account updates in view of the full refresh.",
                      accountsCount.fold("")(" " + _)
                    )
                  case Failure(err) =>
                    logger.error(
                      "I failed to store the accounts refresh updates in the checkpoint",
                      err
                    )
                }
                .map(_ => toCome) //keep the yet unreached levels and pass them on
            case None =>
              logger.warn(
                "I couldn't find in Conseil the block data at level {}, required for the general accounts update, and this is actually unexpected. I'll retry the whole operation at next cycle.",
                levels.max
              )
              Future.successful(events)
          }
        } else Future.successful(events)
      } yield updated
    } else Future.successful(events)

  /**
    * Fetches all blocks not in the database from the Tezos network and adds them to the database.
    * Additionally stores account references that needs updating, too
    */
  private[this] def processTezosBlocks(): Future[Done] = {
    import cats.instances.future._
    import cats.syntax.flatMap._
    import TezosTypes.Syntax._

    logger.info("Processing Tezos Blocks..")

    val blockPagesToSynchronize = lorreConf.depth match {
      case Newest => tezosNodeOperator.getBlocksNotInDatabase()
      case Everything => tezosNodeOperator.getLatestBlocks()
      case Custom(n) => tezosNodeOperator.getLatestBlocks(Some(n), lorreConf.headHash)
    }

    /* will store a single page of block results */
    def processBlocksPage(
        results: tezosNodeOperator.BlockFetchingResults
    )(implicit mat: ActorMaterializer): Future[Int] = {
      def logBlockOutcome[A]: PartialFunction[Try[Option[A]], Unit] = {
        case Success(accountsCount) =>
          logger.info(
            "Wrote {} blocks to the database, checkpoint stored for{} account updates",
            results.size,
            accountsCount.fold("")(" " + _)
          )
        case Failure(e) =>
          logger.error("Could not write blocks or accounts checkpoints to the database.", e)
      }

      def logVotingOutcome[A]: PartialFunction[Try[Option[A]], Unit] = {
        case Success(votesCount) =>
          logger.info("Wrote{} voting data records to the database", votesCount.fold("")(" " + _))
        case Failure(e) =>
          logger.error("Could not write voting data to the database", e)
      }

      //ignore the account ids for storage, and prepare the checkpoint account data
      //we do this on a single sweep over the list, pairing the results and then unzipping the outcome
      val (blocks, accountUpdates) =
        results.map {
          case (block, accountIds) =>
            block -> accountIds.taggedWithBlock(
                  block.data.hash,
                  block.data.header.level,
                  Some(block.data.header.timestamp.toInstant),
                  DatabaseConversions.extractCycle(block)
                )
        }.unzip

      for {
        _ <- db.run(TezosDb.writeBlocksAndCheckpointAccounts(blocks, accountUpdates)) andThen logBlockOutcome
        delegateCheckpoints <- processAccountsForBlocks(accountUpdates) // should this fail, we still recover data from the checkpoint
        _ <- processDelegatesForBlocks(delegateCheckpoints) // same as above
        _ <- processVotesForBlocks(results.map { case (block, _) => block }) andThen logVotingOutcome
      } yield results.size

    }

    def processBakingAndEndorsingRights(fetchingResults: tezosNodeOperator.BlockFetchingResults): Future[Unit] = {
      import cats.implicits._

      val blockHashesWithCycleAndGovernancePeriod = fetchingResults.map { results =>
        {
          val data = results._1.data
          val hash = data.hash
          data.metadata match {
            case GenesisMetadata => FetchRights(None, None, hash)
            case BlockHeaderMetadata(_, _, _, _, _, level) =>
              FetchRights(Some(level.cycle), Some(level.voting_period), hash)

          }
        }
      }

      (
        tezosNodeOperator.getBatchBakingRights(blockHashesWithCycleAndGovernancePeriod),
        tezosNodeOperator.getBatchEndorsingRights(blockHashesWithCycleAndGovernancePeriod)
      ).mapN {
        case (br, er) =>
          (db.run(TezosDb.writeBakingRights(br)), db.run(TezosDb.writeEndorsingRights(er)))
            .mapN((_, _) => ())
      }.flatten
    }

    blockPagesToSynchronize.flatMap {
      // Fails the whole process if any page processing fails
      case (pages, total) => {
        //this will be used for the pages streaming, and within all sub-processing doing a similar paging trick
        implicit val mat = ActorMaterializer()

        //custom progress tracking for blocks
        val logProgress =
          logProcessingProgress(entityName = "block", totalToProcess = total, processStartNanos = System.nanoTime()) _

        // Process each page on his own, and keep track of the progress
        Source
          .fromIterator(() => pages)
          .mapAsync[tezosNodeOperator.BlockFetchingResults](1)(identity)
          .mapAsync(1) { fetchingResults =>
            processBlocksPage(fetchingResults)
              .flatTap(
                _ =>
                  processTezosAccountsCheckpoint >> processTezosDelegatesCheckpoint >> processBakingAndEndorsingRights(
                        fetchingResults
                      )
              )
          }
          .runFold(0) { (processed, justDone) =>
            processed + justDone <| logProgress
          }

      }
    } transform {
      case Failure(accountFailure @ AccountsProcessingFailed(_, _)) =>
        Failure(accountFailure)
      case Failure(delegateFailure @ DelegatesProcessingFailed(_, _)) =>
        Failure(delegateFailure)
      case Failure(e) =>
        val error = "Could not fetch blocks from client"
        logger.error(error, e)
        Failure(BlocksProcessingFailed(message = error, e))
      case Success(_) => Success(Done)
    }

  }

  /**
    * Fetches voting data for the blocks and stores any relevant
    * result into the appropriate database table
    */
  private[this] def processVotesForBlocks(blocks: List[TezosTypes.Block]): Future[Option[Int]] = {
    import cats.syntax.traverse._
    import cats.instances.list._
    import slickeffect.implicits._
    import slick.jdbc.PostgresProfile.api._

    tezosNodeOperator.getVotingDetails(blocks).flatMap {
      case (proposals, bakersBlocks, ballotsBlocks) =>
        //this is a nested list, each block with many baker rolls
        val writeBakers = TezosDb.writeVotingRolls(bakersBlocks)

        val updateAccountsHistory = bakersBlocks.traverse {
          case (block, bakersRolls) =>
            TezosDb.updateAccountsHistoryWithBakers(bakersRolls, block)
        }

        val updateAccounts = bakersBlocks.traverse {
          case (block, bakersRolls) =>
            TezosDb.updateAccountsWithBakers(bakersRolls, block)
        }

        val combinedVoteWrites = for {
          bakersWritten <- writeBakers
          accountsHistoryUpdated <- updateAccountsHistory
          accountsUpdated <- updateAccounts
        } yield
          bakersWritten
            .map(_ + proposals.size + ballotsBlocks.size + accountsHistoryUpdated.size + accountsUpdated.size)

        db.run(combinedVoteWrites.transactionally)
    }
  }

  /**
    * Collect data from Blocks Table, Operations Table, and the Tezos RPC,
    * return the appropriate list of VoteAggregates, and write them to the Votes table.
    * @param ex the needed ExecutionContext to combine multiple database operations
    * @return a future result of the number of rows stored to db, if supported by the driver
    */
  private[this] def processTezosVotes(): Future[Done] = {
    import slick.jdbc.PostgresProfile.api._
    implicit val mat = ActorMaterializer()

    logger.info("Processing latest Tezos votes data...")

    /**
      * * Helper function, takes a sequence of GovernanceData, separates them by ballot types.
      * @param votingData
      * @return
      */
    def partitionByVote(votingData: Seq[VotingData]): (Seq[VotingData], Seq[VotingData], Seq[VotingData]) = {
      val (yays, notYays) = votingData.partition(x => x.ballot.contains("yay"))
      val (nays, notYaysOrNays) = notYays.partition(x => x.ballot.contains("nay"))
      val (passes, _) = notYaysOrNays.partition(x => x.ballot.contains("pass"))
      (yays, nays, passes)
    }

    /**
      * * Helper function, takes a sequence of GovernanceData, calculates stake.
      * @param votingData
      * @return
      */
    def calculateStake(votingData: Seq[VotingData]): Future[BigDecimal] =
      Future
        .sequence(
          votingData.map {
            case VotingData(_, _, hash, _, _, _, address) =>
              tezosNodeOperator.getAccountBalanceForBlock(BlockHash(hash), AccountId(address.get))
          }
        )
        .map(balances => balances.reduceOption(_ + _).getOrElse(0))

    /**
      * * Helper function, takes a sequence of GovernanceData, processes aggregate fields.
      * @param votingData
      * @return
      */
    def processVoteAggregate(votingData: Seq[VotingData]): Future[TezosTypes.VoteAggregates] = {
      val sampleVotingField = votingData.head
      val timestamp = sampleVotingField.timestamp
      val cycle = sampleVotingField.cycle
      val level = sampleVotingField.level
      val proposalHash = sampleVotingField.proposalHash
      val (yays, nays, passes) = partitionByVote(votingData)
      val yayCount = yays.length
      val nayCount = nays.length
      val passCount = passes.length
      for {
        yayStake <- calculateStake(yays)
        nayStake <- calculateStake(nays)
        passStake <- calculateStake(passes)
        totalStake = yayStake + nayStake + passStake
      } yield
        VoteAggregates(
          timestamp,
          cycle,
          level,
          proposalHash,
          yayCount,
          nayCount,
          passCount,
          yayStake,
          nayStake,
          passStake,
          totalStake
        )
    }

    //get fields from db in Tezos Database Operations, use the block levels as input
    db.run(TezosDb.fetchVotingBlockLevels).flatMap { levels =>
      Source
        .fromIterator(() => levels.toIterator)
        .grouped(batchingConf.blockPageSize) // we use here block page size, because
        .mapAsync(1) { blockLevels =>
          val computeAndStore = for {
            listOfVotingFields <- TezosDb.fetchVotingData(blockLevels.toSet)
            ballotOperationsByClock = listOfVotingFields.groupBy(_.level).values
            votes <- DBIO.from {
              Future.traverse(ballotOperationsByClock.toSeq)(processVoteAggregate)
            }
            dbWrites <- TezosDb.writeVotes(votes.toList)
          } yield dbWrites

          db.run(computeAndStore).andThen {
            case Success(Some(written)) => logger.info("Wrote {} vote aggregates to the database.", written)
            case Success(None) => logger.info("Wrote vote aggregates to the database.")
            case Failure(e) => logger.error("Could not write vote aggregates to the database because", e)
          }
        }
        .runWith(Sink.ignore)
    }

  }

  /* Fetches accounts from account-id and saves those associated with the latest operations
   * (i.e.the highest block level)
   * @return the delegates key-hashes found for the accounts passed-in, grouped by block reference
   */
  private[this] def processAccountsForBlocks(
      updates: List[BlockTagged[List[AccountId]]]
  )(implicit mat: ActorMaterializer): Future[List[BlockTagged[List[PublicKeyHash]]]] = {
    logger.info("Processing latest Tezos data for updated accounts...")

    def keepMostRecent(associations: List[(AccountId, BlockReference)]): Map[AccountId, BlockReference] =
      associations.foldLeft(Map.empty[AccountId, BlockReference]) { (collected, entry) =>
        val key = entry._1
        if (collected.contains(key)) collected else collected + (key -> entry._2)
      }

    val sorted = updates.flatMap {
      case BlockTagged(hash, level, timestamp, cycle, ids) =>
        ids.map(_ -> (hash, level, timestamp, cycle))
    }.sortBy {
      case (id, (hash, level, timestamp, cycle)) => level
    }(Ordering[Int].reverse)

    val toBeFetched = keepMostRecent(sorted)

    AccountsProcessor.process(toBeFetched)
  }

  /** Fetches and stores all accounts from the latest blocks still in the checkpoint */
  private[this] def processTezosAccountsCheckpoint(implicit mat: ActorMaterializer): Future[Done] = {
    logger.info("Selecting all accounts left in the checkpoint table...")
    db.run(TezosDb.getLatestAccountsFromCheckpoint) flatMap { checkpoints =>
      if (checkpoints.nonEmpty) {
        logger.info(
          "I loaded all of {} checkpointed ids from the DB and will proceed to fetch updated accounts information from the chain",
          checkpoints.size
        )
        AccountsProcessor.process(checkpoints).map(_ => Done)
      } else {
        logger.info("No data to fetch from the accounts checkpoint")
        Future.successful(Done)
      }
    }
  }

  private[this] def processDelegatesForBlocks(
      updates: List[BlockTagged[List[PublicKeyHash]]]
  )(implicit mat: ActorMaterializer): Future[Done] = {
    logger.info("Processing latest Tezos data for account delegates...")

    def keepMostRecent(associations: List[(PublicKeyHash, BlockReference)]): Map[PublicKeyHash, BlockReference] =
      associations.foldLeft(Map.empty[PublicKeyHash, BlockReference]) { (collected, entry) =>
        val key = entry._1
        if (collected.contains(key)) collected else collected + (key -> entry._2)
      }

    val sorted = updates.flatMap {
      case BlockTagged(hash, level, timestamp, cycle, ids) =>
        ids.map(_ -> (hash, level, timestamp, cycle))
    }.sortBy {
      case (id, (hash, level, timestamp, cycle)) => level
    }(Ordering[Int].reverse)

    val toBeFetched = keepMostRecent(sorted)

    DelegatesProcessor.process(toBeFetched)
  }

  /** Fetches and stores all delegates from the latest blocks still in the checkpoint */
  private[this] def processTezosDelegatesCheckpoint(implicit mat: ActorMaterializer): Future[Done] = {
    logger.info("Selecting all delegates left in the checkpoint table...")
    db.run(TezosDb.getLatestDelegatesFromCheckpoint) flatMap { checkpoints =>
      if (checkpoints.nonEmpty) {
        logger.info(
          "I loaded all of {} checkpointed ids from the DB and will proceed to fetch updated delegates information from the chain",
          checkpoints.size
        )
        DelegatesProcessor.process(checkpoints)
      } else {
        logger.info("No data to fetch from the delegates checkpoint")
        Future.successful(Done)
      }
    }
  }

  private object AccountsProcessor {

    type AccountsIndex = Map[AccountId, Account]
    type DelegateKeys = List[PublicKeyHash]

    /* Fetches the data from the chain node and stores accounts into the data store.
     * @param ids a pre-filtered map of account ids with latest block referring to them
     */
    def process(
        ids: Map[AccountId, BlockReference]
    )(implicit mat: ActorMaterializer): Future[List[BlockTagged[DelegateKeys]]] = {
      import cats.Monoid
      import cats.instances.future._
      import cats.instances.list._
      import cats.instances.int._
      import cats.instances.option._
      import cats.instances.tuple._
      import cats.syntax.flatMap._
      import cats.syntax.functorFilter._
      import cats.syntax.monoid._

      def logWriteFailure: PartialFunction[Try[_], Unit] = {
        case Failure(e) =>
          logger.error("Could not write accounts to the database")
      }

      def logOutcome: PartialFunction[Try[(Int, Option[Int], _)], Unit] = {
        case Success((accountsRows, delegateCheckpointRows, _)) =>
          logger.info(
            "{} accounts were touched on the database. Checkpoint stored for{} delegates.",
            accountsRows,
            delegateCheckpointRows.fold("")(" " + _)
          )
      }

      def extractDelegatesInfo(
          taggedAccounts: Seq[BlockTagged[AccountsIndex]]
      ): (List[BlockTagged[AccountsIndex]], List[BlockTagged[DelegateKeys]]) = {
        val taggedList = taggedAccounts.toList
        def extractDelegateKey(account: Account): Option[PublicKeyHash] =
          PartialFunction.condOpt(account.delegate) {
            case Some(Right(pkh)) => pkh
            case Some(Left(Protocol4Delegate(_, Some(pkh)))) => pkh
          }
        val taggedDelegatesKeys = taggedList.map {
          case BlockTagged(blockHash, blockLevel, timestamp, cycle, accountsMap) =>
            import TezosTypes.Syntax._
            val delegateKeys = accountsMap.values.toList
              .mapFilter(extractDelegateKey)

            delegateKeys.taggedWithBlock(blockHash, blockLevel, timestamp, cycle)
        }
        (taggedList, taggedDelegatesKeys)
      }

      def processAccountsPage(
          taggedAccounts: List[BlockTagged[AccountsIndex]],
          taggedDelegateKeys: List[BlockTagged[DelegateKeys]]
      ): Future[(Int, Option[Int], List[BlockTagged[DelegateKeys]])] =
        db.run(TezosDb.writeAccountsAndCheckpointDelegates(taggedAccounts, taggedDelegateKeys))
          .map {
            case (accountWrites, accountHistoryWrites, delegateCheckpoints) =>
              (accountWrites, delegateCheckpoints, taggedDelegateKeys)
          }
          .andThen(logWriteFailure)

      def cleanup[T] = (_: T) => {
        //can fail with no real downsides
        val processed = Some(ids.keySet)
        logger.info("Cleaning {} processed accounts from the checkpoint...", ids.size)
        db.run(TezosDb.cleanAccountsCheckpoint(processed))
          .map(cleaned => logger.info("Done cleaning {} accounts checkpoint rows.", cleaned))
      }

      logger.info("Ready to fetch updated accounts information from the chain")
      val (pages, total) = tezosNodeOperator.getAccountsForBlocks(ids)

      /* Streams the (unevaluated) incoming data, actually fetching the results.
       * We use combinators to keep the ongoing requests' flow under control, taking advantage of
       * akka-streams automatic backpressure control.
       * The results are grouped to optimize for database storage.
       * We do this to re-aggregate results from pages which are now based on single blocks,
       * which would lead to inefficient storage performances as-is.
       */
      val saveAccounts = Source
          .fromIterator(() => pages)
          .mapAsync[List[BlockTagged[Map[AccountId, Account]]]](1)(identity)
          .mapConcat(identity)
          .grouped(batchingConf.blockPageSize)
          .map(extractDelegatesInfo)
          .mapAsync(1)((processAccountsPage _).tupled)
          .runFold(Monoid[(Int, Option[Int], List[BlockTagged[DelegateKeys]])].empty) { (processed, justDone) =>
            processed |+| justDone
          } andThen logOutcome

      (saveAccounts flatTap cleanup).transform {
        case Success((_, _, delegateCheckpoints)) => Success(delegateCheckpoints)
        case Failure(e) =>
          val error = "I failed to fetch accounts from client and update them"
          logger.error(error, e)
          Failure(AccountsProcessingFailed(message = error, e))
      }
    }
  }

  private object DelegatesProcessor {

    /* Fetches the data from the chain node and stores delegates into the data store.
     * @param ids a pre-filtered map of delegate hashes with latest block referring to them
     */
    def process(ids: Map[PublicKeyHash, BlockReference])(implicit mat: ActorMaterializer): Future[Done] = {
      import cats.Monoid
      import cats.instances.int._
      import cats.instances.future._
      import cats.syntax.monoid._
      import cats.syntax.flatMap._

      def logWriteFailure: PartialFunction[Try[_], Unit] = {
        case Failure(e) =>
          logger.error(s"Could not write delegates to the database", e)
      }

      def logOutcome: PartialFunction[Try[Int], Unit] = {
        case Success(rows) =>
          logger.info("{} delegates were touched on the database.", rows)
      }

      def processDelegatesPage(
          taggedDelegates: Seq[BlockTagged[Map[PublicKeyHash, Delegate]]]
      ): Future[Int] =
        db.run(TezosDb.writeDelegatesAndCopyContracts(taggedDelegates.toList))
          .andThen(logWriteFailure)

      def cleanup[T] = (_: T) => {
        //can fail with no real downsides
        val processed = Some(ids.keySet)
        logger.info("Cleaning {} processed delegates from the checkpoint...", ids.size)
        db.run(TezosDb.cleanDelegatesCheckpoint(processed))
          .map(cleaned => logger.info("Done cleaning {} delegates checkpoint rows.", cleaned))
      }

      logger.info("Ready to fetch updated delegates information from the chain")
      val (pages, total) = tezosNodeOperator.getDelegatesForBlocks(ids)

      /* Streams the (unevaluated) incoming data, actually fetching the results.
       * We use combinators to keep the ongoing requests' flow under control, taking advantage of
       * akka-streams automatic backpressure control.
       * The results are grouped to optimize for database storage.
       * We do this to re-aggregate results from pages which are now based on single blocks,
       * which would lead to inefficient storage performances as-is.
       */
      val saveDelegates =
        Source
          .fromIterator(() => pages)
          .mapAsync[List[BlockTagged[Map[PublicKeyHash, Delegate]]]](1)(identity)
          .mapConcat(identity)
          .grouped(batchingConf.blockPageSize)
          .mapAsync(1)(processDelegatesPage)
          .runFold(Monoid[Int].empty) { (processed, justDone) =>
            processed |+| justDone
          } andThen logOutcome

      (saveDelegates flatTap cleanup).transform {
        case Failure(e) =>
          val error = "I failed to fetch delegates from client and update them"
          logger.error(error, e)
          Failure(DelegatesProcessingFailed(message = error, e))
        case success => Success(Done)
      }

    }
  }

  /** Keeps track of time passed between different partial checkpoints of some entity processing
    * Designed to be partially applied to set properties of the whole process once, and then only compute partial completion
    *
    * @param entityName a string that will be logged to identify what kind of resource is being processed
    * @param totalToProcess how many entities there were in the first place
    * @param processStartNanos a nano-time from jvm monotonic time, used to identify when the whole processing operation began
    * @param processed how many entities were processed at the current checkpoint
    */
  private[this] def logProcessingProgress(entityName: String, totalToProcess: Int, processStartNanos: Long)(
      processed: Int
  ): Unit = {
    val elapsed = System.nanoTime() - processStartNanos
    val progress = processed.toDouble / totalToProcess
    logger.info("Completed processing {}% of total requested {}s", "%.2f".format(progress * 100), entityName)

    val etaMins = Duration(scala.math.ceil(elapsed / progress) - elapsed, NANOSECONDS).toMinutes
    if (processed < totalToProcess && etaMins > 1) logger.info("Estimated time to finish is around {} minutes", etaMins)
  }

}
