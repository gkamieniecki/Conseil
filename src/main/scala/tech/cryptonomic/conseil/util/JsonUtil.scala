package tech.cryptonomic.conseil.util

import com.fasterxml.jackson.core.{JsonParser, JsonParseException}
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import scala.annotation.tailrec
import scala.util.Try

/**
  * Jackson wrapper for JSON serialization and deserialization functions.
  */
object JsonUtil {

  /*
   * We're reducing visibility of the JsonString constuction (both class and object)
   * to allow instantiation only from JsonUtil's methods
   * The goal is to guarantee that only valid json will be contained within the value class wrapper
   */
  final case class JsonString private (json: String) extends AnyVal with Product with Serializable

  object JsonString {

    // Note: instead of making it private, it might make sense to verify the input
    // and return the [[JsonString]] within a wrapping effect (e.g. Option, Try, Either)
    private[JsonUtil] def apply(json: String): JsonString = new JsonString(json)

    /**
      * Creates a [[JsonString]] from a generic String, doing formal validation
      * @param s the "stringified" json
      * @return a valid JsonString or a failed [[Try]] with the parsing error
      */
    def wrapString(s: String): Try[JsonString] =
      Try {
        validate(mapper.getFactory.createParser(s))
      }.map(_ => JsonString(s))

    //verifies if the parser can proceed till the end
    @tailrec
    @throws[JsonParseException]("when content is not parseable, especially for not well-formed json")
    private def validate(parser: JsonParser): Boolean = {
      parser.nextToken == null || validate(parser)
    }

    /** A [[JsonString]] representing a json object with no attributes */
    lazy val emptyObject = JsonString("{}")

  }

  private val mapper = new ObjectMapper with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

  def toJson(value: Map[Symbol, Any]): JsonString =
    toJson(value map { case (k,v) => k.name -> v})

  def toJson[T](value: T): JsonString =
    JsonString(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))

  def toMap[V](json:String)(implicit m: Manifest[V]): Map[String, V] =
    fromJson[Map[String,V]](json)

  def fromJson[T: Manifest](json: String): T =
    mapper.readValue[T](json.filterNot(Character.isISOControl))

}