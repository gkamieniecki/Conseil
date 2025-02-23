package tech.cryptonomic.conseil.indexer.tezos.bigmaps

import com.github.tminglei.slickpg.ExPostgresProfile

import scala.concurrent.ExecutionContext
import scala.collection.immutable.TreeMap
import cats.implicits._
import tech.cryptonomic.conseil.indexer.tezos.michelson.contracts.{TNSContract, TokenContracts}
import tech.cryptonomic.conseil.common.io.Logging.ConseilLogSupport
import tech.cryptonomic.conseil.common.util.Conversion.Syntax._
import tech.cryptonomic.conseil.common.tezos.TezosTypes.{Block, Contract, ContractId, Decimal, ParametersCompatibility}
import tech.cryptonomic.conseil.common.tezos.TezosTypes.Contract.BigMapUpdate
import tech.cryptonomic.conseil.common.tezos.{Fork, Tables}
import tech.cryptonomic.conseil.common.tezos.Tables.{BigMapContentsRow, BigMapsRow, OriginatedAccountMapsRow}
import tech.cryptonomic.conseil.common.tezos.TezosOptics
import TezosOptics.Operations.{
  extractAppliedOriginationsResults,
  extractAppliedTransactions,
  extractAppliedTransactionsResults
}
import scribe._
import tech.cryptonomic.conseil.common.tezos.TezosTypes.BlockTagged.fromBlockData

/** Defines big-map-diffs specific handling, from block data extraction to database storage
  *
  * @param profile is the actual profile needed to define database operations
  */
case class BigMapsOperations[Profile <: ExPostgresProfile](profile: Profile) extends ConseilLogSupport {
  import profile.api._
  import io.scalaland.chimney.dsl._

  /** Create an action to find and copy big maps based on the diff contained in the blocks
    *
    * @param blocks the blocks containing the diffs
    * @param ec needed to compose db operations
    * @return the count of records added
    */
  def copyContent(blocks: List[Block])(implicit ec: ExecutionContext): DBIO[Option[Int]] = {
    val diffsPerBlock = blocks.map(b => b.data -> TezosOptics.Blocks.acrossBigMapDiffCopy.getAll(b))
    if (logger.includes(Level.Debug)) {
      diffsPerBlock.foreach {
        case (blockData, diffs) if diffs.nonEmpty =>
          logger.debug(
            s"For block hash ${blockData.hash.value}, I'm about to copy the following big maps data: \n\t${diffs.mkString(", ")}"
          )
        case _ =>
      }
    }

    /* we load the sources and copy them with a new destination id
     * collecting relevant diffs per block level
     * What we get out is a sequence of queries whose results are the new rows
     * to write back to db, sorted by growing level
     */
    val sortedQueries = {
      val copyDataByLevel = diffsPerBlock.map {
        case (blockData, diffs) =>
          val queries = diffs.collect {
            case Contract.BigMapCopy(_, Decimal(sourceId), Decimal(destinationId)) =>
              Tables.BigMapContents
                .filter(_.bigMapId === sourceId)
                .map(
                  it =>
                    (
                      destinationId,
                      it.key,
                      it.keyHash,
                      it.operationGroupId,
                      it.value,
                      it.valueMicheline,
                      it.blockLevel,
                      it.timestamp,
                      it.cycle,
                      it.period,
                      it.forkId,
                      it.invalidatedAsof
                    )
                )
                .result
                .headOption
          }
          blockData.header.level -> DBIO.sequence(queries).map(_.flatten)

      }
      TreeMap(copyDataByLevel: _*)
    }

    def dedup(contents: List[BigMapContentsRow]) =
      contents
        .groupBy(row => (row.bigMapId, row.key))
        .values
        .flatMap(_.lastOption)

    val writesByLevel = sortedQueries.values.map(
      readAction =>
        readAction.flatMap { updateData =>
          val rowsToWrite = dedup(updateData.map(BigMapContentsRow.tupled))
          DBIO.sequence {
            List(
              Tables.BigMapContents.insertOrUpdateAll(rowsToWrite),
              Tables.BigMapContentsHistory ++= updateData
                    .map(BigMapContentsRow.tupled)
                    .map(_.transformInto[Tables.BigMapContentsHistoryRow])
                    .distinct
            )
          }
        }
    )

    DBIO.sequence(writesByLevel).map { upserts =>
      val sum = upserts.flatten.fold(Some(0))(_ |+| _)
      val showSum = sum.fold("An unspecified number of")(String.valueOf)
      logger.info(s"$showSum big maps will be actually copied in the db.")
      sum
    }

  }

  /** Create an action to delete big maps and all related content based on the diff contained in the blocks
    *
    * @param blocks the blocks containing the diffs
    * @return a database operation with no results
    */
  def removeMaps(blocks: List[Block]): DBIO[Unit] = {

    val removalDiffs = if (logger.includes(Level.Debug)) {
      val diffsPerBlock = blocks.map(b => b.data.hash.value -> TezosOptics.Blocks.acrossBigMapDiffRemove.getAll(b))
      diffsPerBlock.foreach {
        case (hash, diffs) if diffs.nonEmpty =>
          logger.debug(
            s"For block hash ${hash.value}, I'm about to delete the big maps for ids: \n\t${diffs.mkString(", ")}"
          )
        case _ =>
      }
      diffsPerBlock.map(_._2).flatten
    } else blocks.flatMap(TezosOptics.Blocks.acrossBigMapDiffRemove.getAll)

    val idsToRemove = removalDiffs.collect {
      case Contract.BigMapRemove(_, Decimal(bigMapId)) =>
        bigMapId
    }.toSet

    val showRemove = if (idsToRemove.nonEmpty) s"A total of ${idsToRemove.size}" else "No"
    logger.info(s"$showRemove big maps will be removed from the db.")

    DBIO.seq(
      Tables.BigMapContents.filter(_.bigMapId inSet idsToRemove).delete,
      Tables.BigMaps.filter(_.bigMapId inSet idsToRemove).delete,
      Tables.OriginatedAccountMaps.filter(_.bigMapId inSet idsToRemove).delete
    )
  }

  /** Creates an action to add new maps based on the diff contained in the blocks
    *
    * @param blocks the blocks containing the diffs
    * @return the count of records added, if available from the underlying db-engine
    */
  def saveMaps(blocks: List[Block]): DBIO[Option[Int]] = {

    val diffsPerBlock = blocks.flatMap(
      b =>
        extractAppliedOriginationsResults(b).flatMap {
          case (groupHash, op) => op.big_map_diff.toList.flatMap(keepLatestDiffsFormat).map(groupHash -> _)
        }.map {
          case (groupHash, diff) =>
            BigMapsConversions.BlockBigMapDiff(fromBlockData(b.data, (b.data.hash, Some(groupHash), diff)))
        }
    )

    val maps = if (logger.includes(Level.Debug)) {
      val rowsPerBlock = diffsPerBlock
        .map(it => it.get.content._1 -> it.convertToA[Option, BigMapsRow])
        .filterNot(_._2.isEmpty)
        .groupBy { case (hash, _) => hash }
        .mapValues(entries => List.concat(entries.map(_._2.toList): _*))
        .toMap

      rowsPerBlock.foreach {
        case (hash, rows) =>
          logger.debug(
            s"For block hash ${hash.value}, I'm about to add the following big maps: \n\t${rows.mkString(", ")}"
          )
      }

      rowsPerBlock.map(_._2).flatten
    } else diffsPerBlock.flatMap(_.convertToA[Option, BigMapsRow].toList)

    val showAdd = if (maps.nonEmpty) s"A total of ${maps.size}" else "No"
    logger.info(s"$showAdd big maps will be added to the db.")

    Tables.BigMaps ++= maps
  }

  /** Creates an action to add or replace map contents based on the diff contained in the blocks
    * The contents might override existing data with the latest, based on block level
    *
    * @param blocks the blocks containing the diffs
    * @return the count of records added, if available from the underlying db-engine
    */
  def upsertContent(blocks: List[Block])(implicit ec: ExecutionContext): DBIO[Option[Int]] = {

    import tech.cryptonomic.conseil.common.tezos.TezosTypes.BlockTagged._
    val diffsPerBlock = blocks.flatMap(
      b =>
        extractAppliedTransactionsResults(b).flatMap {
          case (groupHash, op) => op.big_map_diff.toList.flatMap(keepLatestDiffsFormat).map(groupHash -> _)
        }.map {
          case (groupHash, diff) =>
            BigMapsConversions.BlockBigMapDiff(fromBlockData(b.data, (b.data.hash, Some(groupHash), diff)))
        }
    )

    val rowsPerBlock = diffsPerBlock
      .map(it => it.get.content._1 -> it.convertToA[Option, BigMapContentsRow])
      .filterNot(_._2.isEmpty)
      .groupBy { case (hash, _) => hash }
      .mapValues(entries => List.concat(entries.map(_._2.toList): _*))
      .toMap

    if (logger.includes(Level.Debug)) {
      rowsPerBlock.foreach {
        case (hash, rows) =>
          val showRows = rows.mkString(", ")
          logger.debug(
            s"For block hash ${hash.value}, I'm about to update big map contents with the following data: \n\t$showRows"
          )
      }
    }

    //we want to use block levels to figure out correct processing order
    //and then collect only the latest contents for each map-id and key
    val newContent = blocks
      .sortBy(_.data.header.level)(Ordering[Long].reverse)
      .foldLeft(Map.empty[(BigDecimal, String), BigMapContentsRow]) {
        case (collected, block) =>
          val seen = collected.keySet
          val rows = rowsPerBlock
            .getOrElse(block.data.hash, List.empty)
            .filterNot(row => seen((row.bigMapId, row.key)))
          collected ++ rows.map(row => (row.bigMapId, row.key) -> row)
      }
      .values

    val showAdd = if (newContent.nonEmpty) s"A total of ${newContent.size}" else "No"
    logger.info(s"$showAdd big map content entries will be added.")
    import io.scalaland.chimney.dsl._

    DBIO
      .sequence(
        Seq(
          Tables.BigMapContents.insertOrUpdateAll(newContent),
          Tables.BigMapContentsHistory ++= rowsPerBlock.values.flatten
                .map(_.into[Tables.BigMapContentsHistoryRow].transform)
                .toList
                .distinct
        )
      )
      .map(_.fold(Some(0))(_ |+| _))
  }

  /** Takes from blocks referring a big map allocation the reference to the
    * corresponding account, storing that reference in the database
    *
    * @param blocks the blocks containing the diffs
    * @return the count of records added, if available from the underlying db-engine
    */
  def saveContractOrigin(blocks: List[Block]): DBIO[List[OriginatedAccountMapsRow]] = {

    val diffsPerBlock = blocks.flatMap(
      b =>
        extractAppliedOriginationsResults(b).flatMap {
          case (_, results) =>
            for {
              contractIds <- results.originated_contracts.toList
              diff <- results.big_map_diff.toList.flatMap(keepLatestDiffsFormat)
            } yield
              BigMapsConversions.BlockContractIdsBigMapDiff(
                (b.data.hash, contractIds, diff, Some(b.data.header.level))
              )
        }
    )

    val refs =
      if (logger.includes(Level.Debug)) {
        val rowsPerBlock = diffsPerBlock
          .map(it => it.get._1 -> it.convertToA[List, OriginatedAccountMapsRow])
          .filterNot(_._2.isEmpty)
          .groupBy { case (hash, _) => hash }
          .mapValues(entries => List.concat(entries.map(_._2): _*))
          .toMap

        rowsPerBlock.foreach {
          case (hash, rows) =>
            val showRows = rows.mkString(", ")
            logger.debug(
              s"For block hash ${hash.value}, I'm about to add the following links from big maps to originated accounts: \nt$showRows"
            )
        }

        rowsPerBlock.flatMap(_._2).toList
      } else diffsPerBlock.flatMap(_.convertToA[List, OriginatedAccountMapsRow])

    val showRefs = if (refs.nonEmpty) s"A total of ${refs.size}" else "No"
    logger.info(s"$showRefs big map accounts references will be made.")

    //the returned DBIOAction will provide the rows just added
    (Tables.OriginatedAccountMaps ++= refs) andThen DBIO.successful(refs)
  }

  /** Updates the reference to the stored big map, for accounts associated to a token contract */
  def initTokenMaps(
      contractsReferences: List[OriginatedAccountMapsRow]
  )(implicit tokenContracts: TokenContracts): Unit =
    contractsReferences.foreach {
      case OriginatedAccountMapsRow(mapId, accountId, _, _, _) if tokenContracts.isKnownToken(ContractId(accountId)) =>
        tokenContracts.setMapId(ContractId(accountId), mapId)
      case _ =>
    }

  /** Updates the reference to the stored big maps, for accounts associated to a name-service contract */
  def initTNSMaps(
      contractsReferences: List[OriginatedAccountMapsRow]
  )(implicit ec: ExecutionContext, tnsContracts: TNSContract): DBIO[Unit] = {
    //fetch the right maps
    val mapsQueries: List[DBIO[Option[(String, BigMapsRow)]]] =
      contractsReferences.collect {
        case OriginatedAccountMapsRow(mapId, accountId, _, _, _)
            if tnsContracts.isKnownRegistrar(ContractId(accountId)) =>
          Tables.BigMaps
            .findBy(_.bigMapId)
            .applied(mapId)
            .map(accountId -> _) //track each map row with the originated account
            .result
            .headOption
      }
    //put together the values and record on the TNS
    DBIO.sequence(mapsQueries).map(collectMapsByContract _ andThen setOnTNS)
  }

  /* Reorganize the input list to discard empty values and group them by first element, i.e. the contract id */
  private def collectMapsByContract(ids: List[Option[(String, BigMapsRow)]]): Map[ContractId, List[BigMapsRow]] =
    ids.flattenOption.groupBy {
      case (contractId, row) => ContractId(contractId)
    }.mapValues(values => values.map(_._2))

  /* For each entry, tries to pass the values to the TNS object to initialize the map ids properly */
  private def setOnTNS(maps: Map[ContractId, List[BigMapsRow]])(implicit tnsContracts: TNSContract) =
    maps.foreach {
      case (contractId, rows) => tnsContracts.setMaps(contractId, rows)
    }

  /** Matches blocks' transactions to extract updated balance for any contract corresponding to a known
    * token definition
    *
    * We only consider transactions whose source starts with a valid contract address hash
    *
    * @param blocks containing the possible token exchange operations
    * @param ec needed to sequence multiple database operations
    * @return optional count of rows stored on db
    */
  def updateTokenBalances(
      blocks: List[Block]
  )(implicit ec: ExecutionContext, tokenContracts: TokenContracts): DBIO[Option[Int]] = {
    import slickeffect.implicits._

    val toSql = (zdt: java.time.ZonedDateTime) => java.sql.Timestamp.from(zdt.toInstant)

    //we first extract all data available from the blocks themselves, as necessary to make a proper balance entry
    val tokenUpdates = blocks.flatMap { b =>
      val transactions = extractAppliedTransactions(b).filter {
        case Left(op) => tokenContracts.isKnownToken(op.destination)
        case Right(op) => tokenContracts.isKnownToken(op.destination)
      }

      //now extract relevant diffs for each destination, along with call parameters
      val updatesMap: Map[ContractId, (Option[ParametersCompatibility], List[BigMapUpdate])] = transactions.map {
        case Left(op) =>
          op.destination -> (op.parameters_micheline.orElse(op.parameters), op.metadata.operation_result.big_map_diff)
        case Right(op) =>
          op.destination -> (op.parameters_micheline.orElse(op.parameters), op.result.big_map_diff)
      }.toMap.mapValues {
        case (optionalParams, optionalDiffs) =>
          optionalParams -> keepLatestUpdateFormat(optionalDiffs.toList.flatten)

      }

      if (logger.includes(Level.Debug)) {
        val showTransactions =
          updatesMap.map {
            case (tokenId, (params, updates)) =>
              val paramsValue = params.fold("missing params") {
                case Left(params) => params.toString
                case Right(micheline) => micheline.toString
              }
              val updateString = updates.mkString("\t", "\n\t", "\n")
              s"Token ${tokenId.id}:\n $paramsValue -> $updateString"
          }.mkString("\n")

        logger.debug(
          s"For block hash ${b.data.hash.value}, I'm about to extract the token balance updates from the following transactions' data : \n$showTransactions"
        )
      }

      //convert packed data
      BigMapsConversions
        .TokenUpdatesInput(b, updatesMap)
        .convertToA[List, BigMapsConversions.TokenUpdate]
    }

    //now we need to check on the token registry for matching contracts, to get a valid token-id as defined on the db
    val rowOptions = tokenUpdates.map { tokenUpdate =>
      val blockData = tokenUpdate.block.data

      Tables.RegisteredTokens
        .filter(_.address === tokenUpdate.tokenContractId.id)
        .map(_.address)
        .result
        .map { results =>
          results.headOption.map(
            tokenId =>
              Tables.TokenBalancesRow(
                tokenAddress = tokenId,
                address = tokenUpdate.accountId.value,
                balance = BigDecimal(tokenUpdate.balance),
                blockId = blockData.hash.value,
                blockLevel = blockData.header.level,
                asof = toSql(blockData.header.timestamp),
                forkId = Fork.mainForkId
              )
          )
        }
    }.sequence[DBIO, Option[Tables.TokenBalancesRow]]

    rowOptions.flatMap { ops =>
      val validBalances = ops.flatten

      val showUpdates = if (validBalances.nonEmpty) s"A total of ${validBalances.size}" else "No"
      logger.info(s"$showUpdates token balance updates will be stored.")

      Tables.TokenBalances ++= validBalances
    }
  }

  /* filter out pre-babylon big map updates */
  private def keepLatestUpdateFormat: List[Contract.CompatBigMapDiff] => List[Contract.BigMapUpdate] =
    compatibilityWrapped =>
      compatibilityWrapped.collect {
        case Left(update: Contract.BigMapUpdate) => update
      }

  /* filter out pre-babylon big map diffs */
  private def keepLatestDiffsFormat: List[Contract.CompatBigMapDiff] => List[Contract.BigMapDiff] =
    compatibilityWrapped =>
      compatibilityWrapped.collect {
        case Left(diff) => diff
      }
}
