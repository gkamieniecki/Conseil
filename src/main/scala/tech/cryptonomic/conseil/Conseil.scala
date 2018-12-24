package tech.cryptonomic.conseil

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import tech.cryptonomic.conseil.directives.EnableCORSDirectives
import tech.cryptonomic.conseil.routes.{QueryProtocol, PlatformDiscovery, Tezos}
import tech.cryptonomic.conseil.util.SecurityUtil

import scala.concurrent.ExecutionContextExecutor

object Conseil extends App with LazyLogging with EnableCORSDirectives {

  val validateApiKey = headerValueByName("apikey").tflatMap[Tuple1[String]] {
    case Tuple1(apiKey) =>
      if (SecurityUtil.validateApiKey(apiKey)) {
        provide(apiKey)
      } else {
        complete((Unauthorized, "Incorrect API key"))
      }
  }

  val conf = ConfigFactory.load
  val conseil_hostname = conf.getString("conseil.hostname")
  val conseil_port = conf.getInt("conseil.port")

  implicit val system: ActorSystem = ActorSystem("conseil-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  val sslConfig = AkkaSSLConfig()

  val route = cors() {
    enableCORS {
      validateApiKey { _ =>
        logRequest("Conseil", Logging.DebugLevel) {
          pathPrefix("tezos") {
            Tezos(system.dispatchers.lookup("akka.tezos-dispatcher")).route
          }
        } ~ pathPrefix("v2") {
          logRequest("Discovery route", Logging.DebugLevel) {
            pathPrefix("metadata") {
              PlatformDiscovery(conf)(system.dispatchers.lookup("akka.tezos-dispatcher")).route
            }
          } ~ logRequest("Query route", Logging.DebugLevel) {
            pathPrefix("query") {
              QueryProtocol(system.dispatchers.lookup("akka.tezos-dispatcher")).route
            }
          } ~ logRequest("Backward compatible data route", Logging.DebugLevel) {
            pathPrefix("data") {
              QueryProtocol(system.dispatchers.lookup("akka.tezos-dispatcher")).route
            }
          }
        }
      } ~ options {
        // Support for CORS pre-flight checks.
        complete("Supported methods : GET and POST.")
      }
    }
  }

  val bindingFuture = Http().bindAndHandle(route, conseil_hostname, conseil_port)
  logger.info("Bonjour...")

  sys.addShutdownHook {
    bindingFuture
    .flatMap(_.unbind().andThen{ case _ => logger.info("Server stopped...")} )
    .flatMap( _ => system.terminate())
    .onComplete(_ => logger.info("We're done here, nothing else to see"))
  }
}
