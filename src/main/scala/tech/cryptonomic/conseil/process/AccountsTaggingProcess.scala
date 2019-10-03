package tech.cryptonomic.conseil.process
import cats.Applicative
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.instances.int._
import cats.mtl.MonadState
import scala.{Stream => _} //hide from default scope
import fs2._
import tech.cryptonomic.conseil.tezos.Tables.OperationsRow
import tech.cryptonomic.conseil.tezos.TezosTypes.PublicKeyHash
import tech.cryptonomic.conseil.util.PureLogging

object AccountsTaggingProcess {

  /** Creates an instance of the process, which is an effectful operation */
  def apply[F[_]: Sync: MonadState[?[_], Option[BlockLevel]]]: AccountsTaggingProcess[F] =
    new AccountsTaggingProcess[F]

  /** Convenience alias */
  type BlockLevel = BigDecimal

  /** The kinds of relevant operations for tagging */
  val operationKinds = Set("activate_account", "reveal")

}

/** Scans the existing blocks stored by conseil for specific account-related operations
  * i.e. Revelation, Activation.
  * It then marks such accounts with the appropriate flag (e.g. revealed, activated).
  * @param highWatermarkRef a pure reference that tracks the highest verified block level for flagging operations
  */
class AccountsTaggingProcess[F[_]: Sync](
    implicit highWatermark: MonadState[F, Option[AccountsTaggingProcess.BlockLevel]]
) extends PureLogging {
  import AccountsTaggingProcess._

  /** the process signals how many flags have been added */
  type ProcessorOutput = Option[Int]

  /** the process needs to have an initial reference to tag from */
  type ProcessorInput = Option[BlockLevel]

  /* an optional input (might be implicit?) that contain references to
   * other conseil internal services/apis needed to perform the processing
   * e.g. database operations, conseil api calls
   */
  trait ServiceDependencies {

    /** Computes the highest level that previously "tagged" an account.
      * @return the highest marked account level, or `-1`, if none fits the role
      */
    def readHighWatermark: F[BlockLevel]

    /** mark as active an account on the db */
    def flagAsActive(accountId: PublicKeyHash): F[Int]

    /** mark as revealed an account on the db */
    def flagAsRevealed(accountId: PublicKeyHash): F[Int]

    /** Loads all stored reveal or activation operations from a block level onward
      * @param fromLevel the [excluded] lowest level to load
      * @return an effectful stream of rows, sorted with ascending block level references
      */
    def readOperations(fromLevel: BlockLevel): Stream[F, OperationsRow]

  }

  /* Actually gets all operations and flags the accounts.
   * Returns the effectful computation that will count the number of
   * changes to the accounts.
   */
  private def scan(fromLevel: BlockLevel)(implicit api: ServiceDependencies): F[Option[Int]] = {

    val missingResult = Applicative[F].pure(Option.empty[Int])

    /* Actually flag an account based on the operation passed, which can be a reveal or activation
     * @return the IO action that will store the flags and return the number of changed rows, or none if
     *         the operation was not valid
     */
    def flag(
        accountRef: Option[String],
        flagOperation: PublicKeyHash => F[Int]
    )(
        implicit op: OperationsRow
    ): F[Option[Int]] =
      accountRef match {
        case None =>
          logger.pureLog[F](
            _.error("No account reference found in {}", op)
          ) >> missingResult
        case Some(hash) =>
          flagOperation(PublicKeyHash(hash)).map(_.some)
      }

    /* Uppdates the passed-in reference with the new level, if higher */
    def updateIfHigher(newLevel: BlockLevel): F[Unit] =
      highWatermark.modify {
        //puts the new level if it's higher than the stored one, or if there was nothing there
        stored =>
          stored.map(_ max newLevel).orElse(newLevel.some)
      }

    /* find sorted operations and flag the related accounts as they come by, all the while keeping track of the
     * new block level, as read from the operations
     */
    api
      .readOperations(fromLevel)
      .evalMap { implicit operation =>
        val effect = operation.kind match {
          case "reveal" =>
            flag(operation.source, api.flagAsRevealed)
          case "activate_account" =>
            flag(operation.pkh, api.flagAsActive)
          case kind =>
            //log unexpected kind
            logger.pureLog[F](
              _.error(
                """I didn't expect to process such operation kind: "{}", while flagging accounts. It should be one of {}""",
                operation,
                operationKinds.mkString(", ")
              )
            ) >> missingResult
        }
        effect.tupleRight(operation.blockLevel)
      }
      .evalTap {
        //evenutually store the reference level, if needed
        case (Some(flaggedCount), blockLevel) => updateIfHigher(blockLevel)
        case (None, _) => Applicative[F].unit
      }
      .foldMap {
        //extracts and sums all integer values (counting the actual updates)
        case (anyFlag, _) => anyFlag.getOrElse(0)
      }
      .compile
      .last

  }

  /* Will have the method that accepts data and actualy processes it.
   *
   * Watch out: we're doing sequential updates of the state (i.e. highWatermark)
   * only because we don't expect other concurrent processes to try and write on it.
   * If this constraint is relaxed for any reason, we might want to switch to
   * some sort of atomic update that handles concurrency.
   */
  def process(implicit api: ServiceDependencies): F[ProcessorOutput] =
    for {
      initialMark <- highWatermark.get
      _ <- logger.pureLog(_.info("Initial mark is {}", initialMark))
      defaultedMark <- initialMark.fold(api.readHighWatermark)(_.pure[F])
      _ <- logger.pureLog(_.info("Will start scanning operations to flag, from block level {}", defaultedMark + 1))
      _ <- highWatermark.set(defaultedMark.some)
      rowsFlags <- scan(defaultedMark + 1)
    } yield rowsFlags

}
