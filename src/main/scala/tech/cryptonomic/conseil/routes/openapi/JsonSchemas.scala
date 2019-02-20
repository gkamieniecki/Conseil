package tech.cryptonomic.conseil.routes.openapi

import endpoints.{algebra, generic}
import tech.cryptonomic.conseil.generic.chain.DataTypes._
import tech.cryptonomic.conseil.tezos.FeeOperations.AverageFees
import tech.cryptonomic.conseil.tezos.Tables

/** Trait containing JSON schemas */
trait JsonSchemas extends algebra.JsonSchemas with generic.JsonSchemas   {

  /** API query schema */
  implicit def queryRequestSchema: JsonSchema[ApiQuery] =
    genericJsonSchema[ApiQuery]

  /** Query predicate schema */
  implicit def queryPredicateSchema: JsonSchema[Predicate] =
    genericJsonSchema[Predicate]

  /** Query ordering operation schema */
  implicit def queryOrderingOperationSchema: JsonSchema[OperationType.Value] =
    enumeration(OperationType.values.toSeq)(_.toString)

  /** Query ordering schema */
  implicit def queryOrderingSchema: JsonSchema[QueryOrdering] =
    genericJsonSchema[QueryOrdering]

  /** Query ordering direction schema */
  implicit def queryOrderingDirectionSchema: JsonSchema[OrderDirection.Value] =
    enumeration(OrderDirection.values.toSeq)(_.toString)

  /** Timestamp schema */
  implicit def timestampSchema: JsonSchema[java.sql.Timestamp]

  /** Blocks row schema */
  implicit def blocksRowSchema: JsonSchema[Tables.BlocksRow] =
    genericJsonSchema[Tables.BlocksRow]

  /** Operation groups row schema */
  implicit def operationGroupsRowSchema: JsonSchema[Tables.OperationGroupsRow] =
    genericJsonSchema[Tables.OperationGroupsRow]

  /** Average fees schema */
  implicit def avgFeeSchema: JsonSchema[AverageFees] =
    genericJsonSchema[AverageFees]

  /** Any schema */
  implicit def anySchema: JsonSchema[Any]

  /** Query response schema schema */
  implicit def queryResponseSchema: JsonSchema[List[QueryResponse]]

  /** AnyMap schema */
  implicit def blocksByHashSchema: JsonSchema[AnyMap]

}
