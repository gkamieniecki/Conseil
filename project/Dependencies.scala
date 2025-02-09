import sbt._

object Dependencies {

  private object Versions {
    val typesafeConfig = "1.4.1"
    val pureConfig = "0.10.2"
    val scopt = "4.0.1"

    val akka = "2.6.17"
    val akkaHttp = "10.2.7"
    val akkaHttpJson = "1.38.2"
    val akkaHttpCors = "1.1.2"

    val scribe = "3.1.3"

    val slick = "3.3.3"
    val slickPG = "0.19.7"
    val slickEffect = "0.4.0"
    val postgres = "42.3.1"

    val endpoints4s = "1.3.0"
    val endpoints4sBackCompatible = "1.1.0"
    val endpoints4sAkkaServer = "4.0.0"

    val monix = "3.4.0"

    val cats = "2.6.1"
    val catsEffect = "3.2.9"

    val mouse = "0.25"
    val monocle = "2.0.0"
    val circe = "0.14.1"
    val http4s = "0.23.6"

    val silencer = "1.6.0"
    val kantanCsv = "0.6.0"
    val apacheCommonText = "1.7"
    val radixTree = "0.5.1"

    val scalaTest = "3.2.9"
    val scalaTestScalaCheck = "3.2.9.0"

    val scalaTestJson = "0.2.5"
    val scalaMock = "5.1.0"
    val testContainerPostgres = "1.16.2"
    val diffX = "0.6.0"
    val scalaCheckShapeless = "1.3.0"

    val libsodiumJna = "1.0.4"
    val jna = "5.10.0"

    val chimney = "0.6.1"
    val bitcoin = "0.9.18-SNAPSHOT"
    val scrypto = "2.1.8"
    val scorex = "0.1.7"
  }

  private val config = Seq("com.typesafe" % "config" % Versions.typesafeConfig)

  private val pureConfig = Seq("com.github.pureconfig" %% "pureconfig" % Versions.pureConfig)

  private val scopt = Seq("com.github.scopt" %% "scopt" % Versions.scopt)

  private val scribe = Seq(
    "com.outr" %% "scribe"          % Versions.scribe,
    "com.outr" %% "scribe-slf4j"    % Versions.scribe,
    "com.outr" %% "scribe-logstash" % Versions.scribe
  )

  private val akka = Seq(
    "com.typesafe.akka" %% "akka-actor"   % Versions.akka exclude ("com.typesafe", "config"),
    "com.typesafe.akka" %% "akka-stream"  % Versions.akka exclude ("com.typesafe", "config"),
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka % Test exclude ("com.typesafe", "config")
  )

  private val akkaHttp = Seq(
    "com.typesafe.akka" %% "akka-http"         % Versions.akkaHttp exclude ("com.typesafe", "config"),
    "com.typesafe.akka" %% "akka-http-caching" % Versions.akkaHttp exclude ("com.typesafe", "config"),
    "com.typesafe.akka" %% "akka-http-testkit" % Versions.akkaHttp % Test exclude ("com.typesafe", "config")
  )

  private val akkaHttpJson = Seq(
    "de.heikoseeberger" %% "akka-http-circe" % Versions.akkaHttpJson exclude ("com.typesafe.akka", "akka-http")
  )

  private val akkaHttpCors = Seq(
    "ch.megard" %% "akka-http-cors" % Versions.akkaHttpCors exclude ("com.typesafe.akka", "akka-http")
  )

  private val slick = Seq(
    "com.typesafe.slick" %% "slick"          % Versions.slick exclude ("org.reactivestreams", "reactive-streams") exclude ("com.typesafe", "config") exclude ("org.slf4j", "slf4j-api"),
    "com.typesafe.slick" %% "slick-hikaricp" % Versions.slick exclude ("org.slf4j", "slf4j-api")
  )
  private val slickCodeGen = Seq("com.typesafe.slick" %% "slick-codegen" % Versions.slick)
  private val slickPG = Seq("com.github.tminglei"     %% "slick-pg"      % Versions.slickPG)
  private val slickEffect = Seq(
    "com.kubukoz" %% "slick-effect"            % Versions.slickEffect exclude ("com.typesafe.slick", "slick"),
    "com.kubukoz" %% "slick-effect-transactor" % Versions.slickEffect exclude ("com.typesafe.slick", "slick")
  )

  private val postgres = Seq("org.postgresql" % "postgresql" % Versions.postgres)

  private val endpoints = Seq(
    "org.endpoints4s" %% "algebra"             % Versions.endpoints4s,
    "org.endpoints4s" %% "openapi"             % Versions.endpoints4sBackCompatible,
    "org.endpoints4s" %% "json-schema-generic" % Versions.endpoints4sBackCompatible,
    "org.endpoints4s" %% "json-schema-circe"   % Versions.endpoints4sBackCompatible,
    "org.endpoints4s" %% "akka-http-server"    % Versions.endpoints4sAkkaServer
  )

  private val cats = Seq(
    "org.typelevel" %% "cats-core"   % Versions.cats,
    "org.typelevel" %% "cats-effect" % Versions.catsEffect
  )
  private val mouse = Seq("org.typelevel" %% "mouse" % Versions.mouse) // related to cats

  private val monocle = Seq(
    "com.github.julien-truffaut" %% "monocle-core"  % Versions.monocle exclude ("org.typelevel.cats", "cats-core"),
    "com.github.julien-truffaut" %% "monocle-macro" % Versions.monocle exclude ("org.typelevel.cats", "cats-core") exclude ("org.typelevel.cats", "cats-macros")
  )

  private val circe = Seq(
    "io.circe" %% "circe-core"           % Versions.circe,
    "io.circe" %% "circe-parser"         % Versions.circe,
    "io.circe" %% "circe-generic"        % Versions.circe,
    "io.circe" %% "circe-generic-extras" % Versions.circe
  )

  private val http4s = Seq(
    "org.http4s" %% "http4s-blaze-client" % Versions.http4s,
    "org.http4s" %% "http4s-dsl"          % Versions.http4s,
    "org.http4s" %% "http4s-circe"        % Versions.http4s
  )

  private val silencer = Seq(
    compilerPlugin("com.github.ghik" % "silencer-plugin" % Versions.silencer cross CrossVersion.full),
    "com.github.ghik" % "silencer-lib" % Versions.silencer % Provided cross CrossVersion.full
  )

  private val kantanCsv = Seq(
    "com.nrinaudo" %% "kantan.csv-generic" % Versions.kantanCsv,
    "com.nrinaudo" %% "kantan.csv-java8"   % Versions.kantanCsv
  )

  private val scalaTestCompile = Seq(
    "org.scalactic"              %% "scalactic"                 % Versions.scalaTest,
    "org.scalatest"              %% "scalatest"                 % Versions.scalaTest,
    "org.scalatestplus"          %% "scalacheck-1-15"           % Versions.scalaTestScalaCheck,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.15" % Versions.scalaCheckShapeless
  ) // Dedicated for common-testkit
  private val scalaTest = scalaTestCompile.map(_ % Test)
  private val scalaTestJson = Seq("com.stephenn" %% "scalatest-json-jsonassert" % Versions.scalaTestJson % Test)

  private val scalaMock = Seq("org.scalamock" %% "scalamock" % Versions.scalaMock % Test)

  private val postgresTestContainerCompile = Seq("org.testcontainers"    % "postgresql" % Versions.testContainerPostgres)
  private val postgresTestContainer = postgresTestContainerCompile.map(_ % Test)

  private val diffX = Seq("com.softwaremill.diffx" %% "diffx-scalatest-should" % Versions.diffX % Test)

  private val apacheCommonsText = Seq("org.apache.commons" % "commons-text" % Versions.apacheCommonText)

  private val radixTree = Seq("com.rklaehn" %% "radixtree" % Versions.radixTree)

  private val jna = Seq(
    "com.muquit.libsodiumjna" % "libsodium-jna" % Versions.libsodiumJna exclude ("org.slf4j", "slf4j-log4j12") exclude ("org.slf4j", "slf4j-api"),
    "net.java.dev.jna"        % "jna"           % Versions.jna //see https://github.com/muquit/libsodium-jna/#update-your-projects-pomxml
  )

  private val chimney = Seq("io.scalaland" %% "chimney" % Versions.chimney)

  private val bitcoin = Seq("fr.acinq" %% "bitcoin-lib" % Versions.bitcoin)

  private val scorex = Seq(
    "org.scorexfoundation" %% "scrypto"     % Versions.scrypto,
    "org.scorexfoundation" %% "scorex-util" % Versions.scorex
  )

  val conseilCommonInclude: Seq[ModuleID] =
    concat(
      config,
      scribe,
      pureConfig,
      slick,
      slickCodeGen,
      slickPG,
      slickEffect,
      postgres,
      circe,
      cats,
      mouse,
      http4s,
      radixTree,
      jna,
      chimney,
      silencer,
      monocle,
      kantanCsv,
      scalaTest,
      scalaTestJson,
      scalaMock,
      postgresTestContainer,
      diffX,
      apacheCommonsText,
      bitcoin,
      scorex
    )

  val conseilCommonTestKitInclude: Seq[ModuleID] =
    concat(config, slick, scalaTestCompile, postgresTestContainerCompile, scribe)

  val conseilApiInclude: Seq[ModuleID] =
    concat(
      scribe,
      scopt,
      akka,
      akkaHttp,
      akkaHttpJson,
      akkaHttpCors,
      silencer,
      scalaMock,
      scalaTestJson,
      diffX,
      endpoints
    )

  val conseilLorreInclude: Seq[ModuleID] =
    concat(config, pureConfig, scopt, silencer, akka, akkaHttp, scalaTest, scalaMock, diffX, akkaHttpJson)

  val conseilSchemaInclude: Seq[ModuleID] = concat(config, pureConfig)

  val conseilSmokeTestsInclude: Seq[ModuleID] = concat(config, http4s, circe, cats)

  private def concat(xs: Seq[ModuleID]*): Seq[ModuleID] = xs.reduceLeft(_ ++ _)

}
