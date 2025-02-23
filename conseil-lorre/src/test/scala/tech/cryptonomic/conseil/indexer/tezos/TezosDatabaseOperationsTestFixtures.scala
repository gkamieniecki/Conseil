package tech.cryptonomic.conseil.indexer.tezos

import java.sql.Timestamp
import java.time.{Instant, ZonedDateTime}

import monocle.Optional
import tech.cryptonomic.conseil.common.testkit.util.{RandomGenerationKit, RandomSeed}
import tech.cryptonomic.conseil.common.tezos.Tables.{AccountsRow, BakersRow, BlocksRow, OperationGroupsRow}
import tech.cryptonomic.conseil.common.tezos.TezosTypes.Fee.AverageFees
import tech.cryptonomic.conseil.common.tezos.TezosTypes.Scripted.Contracts
import tech.cryptonomic.conseil.common.tezos.TezosTypes._
import tech.cryptonomic.conseil.common.tezos.{Fork, Tables, TezosOptics, TezosTypes}

import scala.util.Random

trait TezosDatabaseOperationsTestFixtures extends RandomGenerationKit {
  import TezosTypes.Syntax._
  import TezosTypes.Voting.Vote

  /* randomly populate a number of fees */
  def generateFees(howMany: Int, startAt: Timestamp)(implicit randomSeed: RandomSeed): List[AverageFees] = {
    require(howMany > 0, "the test can generates a positive number of fees, you asked for a non positive value")

    val rnd = new Random(randomSeed.seed)

    (1 to howMany).map { current =>
      val low = rnd.nextInt(10)
      val medium = rnd.nextInt(10) + 10
      val high = rnd.nextInt(10) + 20
      AverageFees(
        low = low,
        medium = medium,
        high = high,
        timestamp = new Timestamp(startAt.getTime + current),
        kind = "kind",
        level = 1,
        cycle = None
      )
    }.toList
  }

  /* randomly generates a number of accounts with associated block data */
  def generateAccounts(
      howMany: Int,
      blockHash: TezosBlockHash,
      blockLevel: BlockLevel,
      time: Instant = testReferenceTimestamp.toInstant
  )(
      implicit randomSeed: RandomSeed
  ): BlockTagged[Map[AccountId, Account]] = {
    require(howMany > 0, "the test can generates a positive number of accounts, you asked for a non positive value")

    val rnd = new Random(randomSeed.seed)

    val accounts = (1 to howMany).map { currentId =>
      makeAccountId(String valueOf currentId) ->
        Account(
          balance = rnd.nextInt,
          counter = Some(currentId),
          delegate = Some(Right(PublicKeyHash("delegate-value"))),
          script = Some(Contracts(Micheline("storage"), Micheline("script"))),
          manager = None,
          spendable = None,
          isBaker = None,
          isActivated = None
        )
    }.toMap

    accounts.taggedWithBlock(BlockReference(blockHash, blockLevel, Some(time), None, None))
  }

  /* randomly generates a number of delegates with associated block data */
  def generateDelegates(
      delegatedHashes: List[String],
      blockHash: TezosBlockHash,
      blockLevel: BlockLevel,
      delegateKey: Option[PublicKeyHash] = None
  )(
      implicit randomSeed: RandomSeed
  ): BlockTagged[Map[PublicKeyHash, Delegate]] = {
    require(
      delegatedHashes.nonEmpty,
      "the test can generate only a positive number of delegates, you can't pass an empty list of account key hashes"
    )

    val rnd = new Random(randomSeed.seed)

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(rnd)

    val delegates = delegatedHashes.map { accountPkh =>
      delegateKey.getOrElse(PublicKeyHash(generateHash(10))) ->
        Delegate(
          balance = PositiveDecimal(rnd.nextInt()),
          frozen_balance = PositiveDecimal(rnd.nextInt()),
          frozen_balance_by_cycle = List.empty,
          staking_balance = PositiveDecimal(rnd.nextInt()),
          delegated_contracts = List(ContractId(accountPkh)),
          delegated_balance = PositiveDecimal(rnd.nextInt()),
          deactivated = false,
          grace_period = rnd.nextInt()
        )
    }.toMap

    delegates.taggedWithBlock(BlockReference(blockHash, blockLevel, Some(Instant.ofEpochSecond(0)), None, None))
  }

  /* randomly populate a number of blocks based on a level range */
  def generateBlocks(toLevel: Int, startAt: ZonedDateTime)(implicit randomSeed: RandomSeed): List[Block] = {
    require(
      toLevel > 0,
      "the test can generate blocks up to a positive chain level, you asked for a non positive value"
    )

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    //same for all blocks
    val chainHash = generateHash(5)

    //fix a seed generator and provides a generation function
    val randomMetadataLevel = {
      val rnd = new Random(randomSeed.seed)
      () =>
        BlockHeaderMetadataLevel(
          level = rnd.nextInt(),
          level_position = rnd.nextInt(),
          cycle = rnd.nextInt(),
          cycle_position = rnd.nextInt(),
          voting_period = rnd.nextInt(),
          voting_period_position = rnd.nextInt(),
          expected_commitment = rnd.nextBoolean()
        )
    }

    def generateOne(level: Int, predecessorHash: TezosBlockHash, genesis: Boolean = false): Block =
      Block(
        BlockData(
          protocol = "protocol",
          chain_id = Some(chainHash),
          hash = TezosBlockHash(generateHash(10)),
          header = BlockHeader(
            level = level,
            proto = 1,
            predecessor = predecessorHash,
            timestamp = startAt.plusSeconds(level),
            validation_pass = 0,
            operations_hash = None,
            fitness = Seq.empty,
            priority = Some(0),
            context = s"context$level",
            signature = Some(s"sig${generateHash(10)}")
          ),
          metadata =
            if (genesis) GenesisMetadata
            else
              BlockHeaderMetadata(
                balance_updates = List.empty,
                baker = PublicKeyHash(generateHash(10)),
                voting_period_kind = Some(VotingPeriod.proposal),
                nonce_hash = Some(NonceHash(generateHash(10))),
                consumed_gas = PositiveDecimal(0),
                level = Some(randomMetadataLevel()),
                voting_period_info = None,
                level_info = None
              )
        ),
        operationGroups = List.empty,
        votes = CurrentVotes.empty
      )

    //we need a block to start
    val genesis = generateOne(0, TezosBlockHash("genesis"), genesis = true)

    //use a fold to pass the predecessor hash, to keep a plausibility of sort
    (1 to toLevel)
      .foldLeft(List(genesis)) {
        case (chain, lvl) =>
          val currentBlock = generateOne(lvl, chain.head.data.hash)
          currentBlock :: chain
      }
      .reverse

  }

  /** Randomly generates a single block, for a specific level
    * WARN the algorithm is linear in the level requested, don't use it with high values
    */
  def generateSingleBlock(
      atLevel: Int,
      atTime: ZonedDateTime,
      balanceUpdates: List[OperationMetadata.BalanceUpdate] = List.empty
  )(implicit randomSeed: RandomSeed): Block = {
    import TezosOptics.Blocks._
    import mouse.any._

    val generated = generateBlocks(toLevel = atLevel, startAt = atTime).last

    generated |> setTimestamp(atTime) |> setBalances(balanceUpdates)
  }

  def generateBalanceUpdates(howMany: Int)(implicit randomSeed: RandomSeed): List[OperationMetadata.BalanceUpdate] = {
    require(
      howMany > 0,
      "the test can only generate a positive number of balance updates, you asked for a non positive value"
    )

    val randomSource = new Random(randomSeed.seed)

    //custom hash generator with predictable seed
    val generateAlphaNumeric: Int => String = alphaNumericGenerator(randomSource)

    List.fill(howMany) {
      OperationMetadata.BalanceUpdate(
        kind = generateAlphaNumeric(10),
        change = randomSource.nextLong(),
        category = Some(generateAlphaNumeric(10)),
        contract = Some(ContractId(generateAlphaNumeric(10))),
        delegate = Some(PublicKeyHash(generateAlphaNumeric(10))),
        level = Some(randomSource.nextInt(100))
      )
    }

  }

  /* randomly populate a number of blocks based on a level range */
  def generateBlockRows(toLevel: Int, startAt: Timestamp)(implicit randomSeed: RandomSeed): List[Tables.BlocksRow] = {
    require(
      toLevel > 0,
      "the test can generate blocks up to a positive chain level, you asked for a non positive value"
    )

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    //same for all blocks
    val chainHash = generateHash(5)

    val startMillis = startAt.getTime

    def generateOne(level: Int, predecessorHash: String): BlocksRow =
      BlocksRow(
        level = level,
        proto = 1,
        predecessor = predecessorHash,
        timestamp = new Timestamp(startMillis + level),
        fitness = "fitness",
        protocol = "protocol",
        context = Some(s"context$level"),
        signature = Some(s"sig${generateHash(10)}"),
        chainId = Some(chainHash),
        hash = generateHash(10),
        operationsHash = Some(generateHash(10)),
        periodKind = Some("period_kind"),
        currentExpectedQuorum = Some(1000),
        activeProposal = None,
        baker = Some(generateHash(10)),
        consumedGas = Some(0),
        utcYear = 1970,
        utcMonth = 1,
        utcDay = 1,
        utcTime = "00:00:00",
        forkId = Fork.mainForkId
      )

    //we need somewhere to start with
    val genesis = generateOne(0, "genesis")

    //use a fold to pass the predecessor hash, to keep a plausibility of sort
    (1 to toLevel)
      .foldLeft(List(genesis)) {
        case (chain, lvl) =>
          val currentBlock = generateOne(lvl, chain.head.hash)
          currentBlock :: chain
      }
      .reverse

  }

  /* create an operation group for each block passed in, using random values, with the requested copies of operations */
  def generateOperationGroup(block: Block, generateOperations: Boolean)(
      implicit randomSeed: RandomSeed
  ): OperationsGroup = {

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    OperationsGroup(
      protocol = "protocol",
      chain_id = block.data.chain_id.map(ChainId),
      hash = OperationHash(generateHash(10)),
      branch = TezosBlockHash(generateHash(10)),
      signature = Some(Signature(s"sig${generateHash(10)}")),
      contents = if (generateOperations) Operations.sampleOperations else List.empty
    )
  }

  /* create an empty operation group for each block passed in, using random values */
  def generateOperationGroupRows(
      blocks: BlocksRow*
  )(implicit randomSeed: RandomSeed): List[Tables.OperationGroupsRow] = {
    require(blocks.nonEmpty, "the test won't generate any operation group without a block to start with")

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    blocks
      .map(
        block =>
          Tables.OperationGroupsRow(
            protocol = "protocol",
            chainId = block.chainId,
            hash = generateHash(10),
            branch = generateHash(10),
            signature = Some(s"sig${generateHash(10)}"),
            blockId = block.hash,
            blockLevel = block.level,
            forkId = Fork.mainForkId
          )
      )
      .toList
  }

  /* create operation rows to hold the given fees */
  def wrapFeesWithOperations(
      fees: Seq[Option[BigDecimal]],
      block: BlocksRow,
      group: OperationGroupsRow
  ): Seq[Tables.OperationsRow] =
    fees.zipWithIndex.map {
      case (fee, index) =>
        Tables.OperationsRow(
          kind = "kind",
          operationGroupHash = group.hash,
          operationId = -1,
          fee = fee,
          blockHash = block.hash,
          blockLevel = block.level,
          timestamp = new Timestamp(block.timestamp.getTime + index),
          level = Some(block.level),
          internal = false,
          utcYear = 1970,
          utcMonth = 1,
          utcDay = 1,
          utcTime = "00:00:00",
          forkId = Fork.mainForkId
        )
    }

  /* randomly generates a number of account rows for some block */
  def generateAccountRows(howMany: Int, block: BlocksRow): List[AccountsRow] = {
    require(howMany > 0, "the test can only generate a positive number of accounts, you asked for a non positive value")

    (1 to howMany).map { currentId =>
      AccountsRow(
        accountId = String valueOf currentId,
        blockId = block.hash,
        balance = 0,
        counter = Some(0),
        script = None,
        forkId = Fork.mainForkId
      )
    }.toList

  }

  /* randomly generates a number of delegate rows for some block */
  def generateBakerRows(howMany: Int, block: BlocksRow)(implicit randomSeed: RandomSeed): List[BakersRow] = {
    require(
      howMany > 0,
      "the test can only generate a positive number of delegates, you asked for a non positive value"
    )

    //custom hash generator with predictable seed
    val generateHash: Int => String = alphaNumericGenerator(new Random(randomSeed.seed))

    List.fill(howMany) {
      BakersRow(
        blockId = block.hash,
        pkh = generateHash(10),
        balance = Some(0),
        frozenBalance = Some(0),
        stakingBalance = Some(0),
        delegatedBalance = Some(0),
        deactivated = true,
        gracePeriod = 0,
        forkId = Fork.mainForkId
      )
    }

  }

  object Operations {

    import OperationMetadata.BalanceUpdate

    val sampleScriptedContract: Contracts =
      Contracts(
        code = Micheline(
          """[{"prim":"parameter","args":[{"prim":"string"}]},{"prim":"storage","args":[{"prim":"string"}]},{"prim":"code","args":[[{"prim":"CAR"},{"prim":"NIL","args":[{"prim":"operation"}]},{"prim":"PAIR"}]]}]"""
        ),
        storage = Micheline("""{"string":"hello"}""")
      )

    val sampleEndorsement: Endorsement =
      Endorsement(
        level = 182308,
        metadata = EndorsementMetadata(
          slot = None,
          slots = List(29, 27, 20, 17),
          delegate = PublicKeyHash("tz1fyvFH2pd3V9UEq5psqVokVBYkt7rHTKio"),
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("tz1fyvFH2pd3V9UEq5psqVokVBYkt7rHTKio")),
              change = -256000000,
              category = None,
              delegate = None,
              level = None
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("deposits"),
              delegate = Some(PublicKeyHash("tz1fyvFH2pd3V9UEq5psqVokVBYkt7rHTKio")),
              change = 256000000,
              contract = None,
              level = Some(1424)
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("rewards"),
              delegate = Some(PublicKeyHash("tz1fyvFH2pd3V9UEq5psqVokVBYkt7rHTKio")),
              change = 4000000,
              contract = None,
              level = Some(1424)
            )
          )
        )
      )

    val sampleNonceRevelation: SeedNonceRevelation =
      SeedNonceRevelation(
        level = 199360,
        nonce = Nonce("4ddd711e76cf8c71671688aff7ce9ff67bf24bc16be31cd5dbbdd267456745e0"),
        metadata = BalanceUpdatesMetadata(
          balance_updates = List(
            BalanceUpdate(
              kind = "freezer",
              category = Some("rewards"),
              delegate = Some(PublicKeyHash("tz1aWXP237BLwNHJcCD4b3DutCevhqq2T1Z9")),
              level = Some(1557),
              change = 125000,
              contract = None
            )
          )
        )
      )

    val sampleAccountActivation: ActivateAccount =
      ActivateAccount(
        pkh = PublicKeyHash("tz1ieofA4fCLAnSgYbE9ZgDhdTuet34qGZWw"),
        secret = Secret("026a9a6b7ea07238dab3e4322d93a6abe8da278a"),
        metadata = BalanceUpdatesMetadata(
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("tz1ieofA4fCLAnSgYbE9ZgDhdTuet34qGZWw")),
              change = 13448692695L,
              category = None,
              delegate = None,
              level = None
            )
          )
        )
      )

    val sampleReveal: Reveal =
      Reveal(
        source = PublicKeyHash("KT1PPuBrvCGpJt54hVBgXMm2sKa6QpSwKrJq"),
        fee = PositiveDecimal(10000),
        counter = PositiveDecimal(1),
        gas_limit = PositiveDecimal(10000),
        storage_limit = PositiveDecimal(257),
        public_key = PublicKey("edpktxRxk9r61tjEZCt5a2hY2MWC3gzECGL7FXS1K6WXGG28hTFdFz"),
        metadata = ResultMetadata[OperationResult.Reveal](
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("KT1PPuBrvCGpJt54hVBgXMm2sKa6QpSwKrJq")),
              change = -10000L,
              category = None,
              delegate = None,
              level = None
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("fees"),
              delegate = Some(PublicKeyHash("tz1boot1pK9h2BVGXdyvfQSv8kd1LQM6H889")),
              level = Some(1561),
              change = 10000L,
              contract = None
            )
          ),
          operation_result = OperationResult.Reveal(
            status = "applied",
            consumed_gas = Some(Decimal(10000)),
            errors = None
          )
        )
      )

    val sampleTransaction: Transaction =
      Transaction(
        source = PublicKeyHash("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR"),
        fee = PositiveDecimal(1416),
        counter = PositiveDecimal(407940),
        gas_limit = PositiveDecimal(11475),
        storage_limit = PositiveDecimal(0),
        amount = PositiveDecimal(0),
        destination = ContractId("KT1CkkM5tYe9xRMQMbnayaULGoGaeBUH2Riy"),
        parameters = Some(Left(Parameters(Micheline("""{"string":"world"}""")))),
        parameters_micheline = None,
        metadata = ResultMetadata(
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR")),
              change = -1416L,
              category = None,
              delegate = None,
              level = None
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("fees"),
              delegate = Some(PublicKeyHash("tz1boot2oCjTjUN6xDNoVmtCLRdh8cc92P1u")),
              level = Some(1583),
              change = 1416L,
              contract = None
            )
          ),
          operation_result = OperationResult.Transaction(
            status = "applied",
            storage = Some(Micheline("""{"string":"world"}""")),
            consumed_gas = Some(Decimal(11375)),
            storage_size = Some(Decimal(46)),
            allocated_destination_contract = None,
            balance_updates = None,
            big_map_diff = Some(List.empty),
            originated_contracts = None,
            paid_storage_size_diff = None,
            errors = None
          )
        )
      )

    val sampleOrigination: Origination =
      Origination(
        source = PublicKeyHash("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR"),
        fee = PositiveDecimal(1441),
        counter = PositiveDecimal(407941),
        gas_limit = PositiveDecimal(11362),
        storage_limit = PositiveDecimal(323),
        manager_pubkey = None,
        balance = PositiveDecimal(1000000),
        spendable = Some(false),
        delegatable = Some(false),
        delegate = None,
        script = Some(sampleScriptedContract),
        metadata = ResultMetadata(
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR")),
              change = -1441L,
              category = None,
              delegate = None,
              level = None
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("fees"),
              delegate = Some(PublicKeyHash("tz1boot1pK9h2BVGXdyvfQSv8kd1LQM6H889")),
              level = Some(1583),
              change = 1441L,
              contract = None
            )
          ),
          operation_result = OperationResult.Origination(
            status = "applied",
            big_map_diff = Some(List.empty),
            balance_updates = Some(
              List(
                BalanceUpdate(
                  kind = "contract",
                  contract = Some(ContractId("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR")),
                  change = -46000L,
                  category = None,
                  delegate = None,
                  level = None
                ),
                BalanceUpdate(
                  kind = "contract",
                  contract = Some(ContractId("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR")),
                  change = -257000L,
                  category = None,
                  delegate = None,
                  level = None
                ),
                BalanceUpdate(
                  kind = "contract",
                  contract = Some(ContractId("tz1hSd1ZBFVkoXC5s1zMguz3AjyCgGQ7FMbR")),
                  change = -1000000L,
                  category = None,
                  delegate = None,
                  level = None
                ),
                BalanceUpdate(
                  kind = "contract",
                  contract = Some(ContractId("KT1VuJAgTJT5x2Y2S3emAVSbUA5nST7j3QE4")),
                  change = 1000000L,
                  category = None,
                  delegate = None,
                  level = None
                )
              )
            ),
            originated_contracts = Some(List(ContractId("KT1VuJAgTJT5x2Y2S3emAVSbUA5nST7j3QE4"))),
            consumed_gas = Some(Decimal(11262)),
            storage_size = Some(Decimal(46)),
            paid_storage_size_diff = Some(Decimal(46)),
            errors = None
          )
        )
      )

    val sampleDelegation: Delegation =
      Delegation(
        source = PublicKeyHash("KT1Ck1Mrbxr6RhCiqN6TPfX3NvWnJimcAKG9"),
        fee = PositiveDecimal(1400),
        counter = PositiveDecimal(2),
        gas_limit = PositiveDecimal(10100),
        storage_limit = PositiveDecimal(0),
        delegate = Some(PublicKeyHash("tz1boot2oCjTjUN6xDNoVmtCLRdh8cc92P1u")),
        metadata = ResultMetadata(
          balance_updates = List(
            BalanceUpdate(
              kind = "contract",
              contract = Some(ContractId("KT1Ck1Mrbxr6RhCiqN6TPfX3NvWnJimcAKG9")),
              change = -1400L,
              category = None,
              delegate = None,
              level = None
            ),
            BalanceUpdate(
              kind = "freezer",
              category = Some("fees"),
              delegate = Some(PublicKeyHash("tz1boot1pK9h2BVGXdyvfQSv8kd1LQM6H889")),
              level = Some(1612),
              change = 1400L,
              contract = None
            )
          ),
          operation_result = OperationResult.Delegation(
            status = "applied",
            consumed_gas = Some(Decimal(10000)),
            errors = None
          )
        )
      )

    val sampleBallot: Ballot =
      Ballot(
        ballot = Vote("yay"),
        proposal = Some("PsBABY5HQTSkA4297zNHfsZNKtxULfL18y95qb3m53QJiXGmrbU"),
        source = Some(ContractId("tz1VceyYUpq1gk5dtp6jXQRtCtY8hm5DKt72")),
        period = Some(0)
      )

    val sampleProposals: Proposals =
      Proposals(
        source = Some(ContractId("tz1VceyYUpq1gk5dtp6jXQRtCtY8hm5DKt72")),
        period = Some(10),
        proposals = Some(List("Psd1ynUBhMZAeajwcZJAeq5NrxorM6UCU4GJqxZ7Bx2e9vUWB6z)"))
      )

    val sampleOperations: List[Operation] =
      sampleEndorsement :: sampleNonceRevelation :: sampleAccountActivation :: sampleReveal :: sampleTransaction :: sampleOrigination :: sampleDelegation ::
          DoubleEndorsementEvidence() :: DoubleBakingEvidence() :: sampleProposals :: sampleBallot :: Nil

    /** Converts operations in a list by selectively adding
      * BigMapAlloc within it's results.
      * The selection is made by passing a partial function that generates
      * the allocation element only for certain operations.
      * In this case the generation needs happen only for Originations
      */
    def updateOperationsWithBigMapAllocation(
        diffGenerate: PartialFunction[Operation, List[Contract.BigMapAlloc]]
    ): List[Operation] => List[Operation] = {
      import tech.cryptonomic.conseil.common.tezos.TezosOptics.Operations._

      //applies to the alloc diffs nested within originations
      updateOperationsToBigMapDiff[Contract.BigMapAlloc](
        diffGenerate,
        selectOrigination composeLens onOriginationResult composeOptional whenOriginationBigMapDiffs
      )
    }

    /** Converts operations in a list by selectively adding
      * BigMapAlloc within it's results.
      * The selection is made by passing a partial function that generates
      * the allocation element only for certain operations.
      * In this case the generation needs happen only for Transacions
      */
    def updateOperationsWithBigMapUpdate(
        diffGenerate: PartialFunction[Operation, List[Contract.BigMapUpdate]]
    ): List[Operation] => List[Operation] = {
      import tech.cryptonomic.conseil.common.tezos.TezosOptics.Operations._

      //applies to the update diffs nested within transactions
      updateOperationsToBigMapDiff[Contract.BigMapUpdate](
        diffGenerate,
        selectTransaction composeLens onTransactionResult composeOptional whenTransactionBigMapDiffs
      )
    }

    /** Converts operations in a list by selectively adding
      * BigMapAlloc within it's results.
      * The selection is made by passing a partial function that generates
      * the allocation element only for certain operations.
      * In this case the generation needs happen only for Transactions
      */
    def updateOperationsWithBigMapCopy(
        diffGenerate: PartialFunction[Operation, List[Contract.BigMapCopy]]
    ): List[Operation] => List[Operation] = {
      import tech.cryptonomic.conseil.common.tezos.TezosOptics.Operations._

      //applies to the update diffs nested within transactions
      updateOperationsToBigMapDiff[Contract.BigMapCopy](
        diffGenerate,
        selectTransaction composeLens onTransactionResult composeOptional whenTransactionBigMapDiffs
      )
    }

    /** Converts operations in a list by selectively adding
      * BigMapAlloc within it's results.
      * The selection is made by passing a partial function that generates
      * the allocation element only for certain operations.
      * In this case the generation needs happen only for Transacions
      */
    def updateOperationsWithBigMapRemove(
        diffGenerate: PartialFunction[Operation, List[Contract.BigMapRemove]]
    ): List[Operation] => List[Operation] = {
      import tech.cryptonomic.conseil.common.tezos.TezosOptics.Operations._

      //applies to the update diffs nested within transactions
      updateOperationsToBigMapDiff[Contract.BigMapRemove](
        diffGenerate,
        selectTransaction composeLens onTransactionResult composeOptional whenTransactionBigMapDiffs
      )
    }

    /* Scans a list of operations to apply a specific BigMapDiff in the
     * results, of selected ones
     * @param diffGenerate is a custom function that, based on the specific operation of interests
     *        provides the BigMapDiff value to set in the operation results
     * @param diffSetter is an optic `Optional` type which allows to directly inject
     *        a value inside a deeply nested structure, corresponding to a specific operation type
     * @return the updated operations
     */
    private def updateOperationsToBigMapDiff[Diff <: Contract.BigMapDiff](
        diffGenerate: PartialFunction[Operation, List[Diff]],
        diffSetter: Optional[Operation, List[Contract.CompatBigMapDiff]]
    )(operations: List[Operation]): List[Operation] =
      operations.map { op =>
        // applies the function to create a possible value to set on each individual operation
        val maybeDiffs = PartialFunction.condOpt(op)(diffGenerate)

        //sets the diff value list in the optional field of the operation if there's something, or returns the unchanged operation
        maybeDiffs.fold(op) { diffs =>
          val diffFieldValues = diffs.map(Left(_)) //look at how CompatBigMapDiff is defined
          diffSetter.modify(diffs => diffFieldValues ::: diffs)(op)
        }
      }

    /** Scans a list of operaonts to apply a specific change to
      * some, selectively, based on the partial function matching.
      *
      * Non-matching operations will be left untouched
      *
      * @param update a transaction mapping, which will be applied to matching operations
      * @return the transformed list
      */
    def modifyTransactions(
        update: PartialFunction[Transaction, Transaction]
    ): List[Operation] => List[Operation] = operations => {
      operations.map {
        case op: Transaction => PartialFunction.condOpt(op)(update).getOrElse(op)
        case op => op
      }
    }
  }

}
