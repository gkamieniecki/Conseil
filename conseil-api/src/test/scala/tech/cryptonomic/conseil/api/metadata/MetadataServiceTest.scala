package tech.cryptonomic.conseil.api.metadata

import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterEach, OneInstancePerTest}
import tech.cryptonomic.conseil.api.routes.platform.discovery.TestPlatformDiscoveryOperations
import tech.cryptonomic.conseil.common.config.Platforms.{
  BitcoinBatchFetchConfiguration,
  BitcoinConfiguration,
  BitcoinNodeConfiguration,
  PlatformsConfiguration,
  TezosConfiguration,
  TezosNodeConfiguration
}
import tech.cryptonomic.conseil.common.config.Types.PlatformName
import tech.cryptonomic.conseil.common.config._
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.DataType.{Hash, Int}
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.KeyType.NonKey
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.{Attribute, Entity, Network, Platform}
import tech.cryptonomic.conseil.common.metadata._
import tech.cryptonomic.conseil.common.testkit.ConseilSpec

class MetadataServiceTest
    extends ConseilSpec
    with ScalatestRouteTest
    with MockFactory
    with BeforeAndAfterEach
    with OneInstancePerTest {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(20, Millis))

  // shared objects
  private val platformDiscoveryOperations: TestPlatformDiscoveryOperations = new TestPlatformDiscoveryOperations
  private val cacheOverrides: AttributeValuesCacheConfiguration = stub[AttributeValuesCacheConfiguration]

  private val dbCfg = ConfigFactory.parseString("""
                                          |    db {
                                          |      dataSourceClass: "org.postgresql.ds.PGSimpleDataSource"
                                          |      properties {
                                          |        user: "foo"
                                          |        password: "bar"
                                          |        url: "jdbc:postgresql://localhost:5432/postgres"
                                          |      }
                                          |      numThreads: 10
                                          |      maxConnections: 10
                                          |    }
        """.stripMargin)

  private val sut = (metadataOverridesConfiguration: Map[PlatformName, PlatformConfiguration]) =>
    new MetadataService(
      PlatformsConfiguration(
        List(
          TezosConfiguration(
            "mainnet",
            enabled = true,
            TezosNodeConfiguration("tezos-host", 123, "https://"),
            BigDecimal.decimal(8000),
            dbCfg,
            None
          ),
          BitcoinConfiguration(
            "testnet",
            enabled = false,
            BitcoinNodeConfiguration("host", 0, "protocol", "username", "password"),
            dbCfg,
            BitcoinBatchFetchConfiguration(1, 1, 1, 1, 1)
          )
        )
      ),
      new UnitTransformation(MetadataConfiguration(metadataOverridesConfiguration)),
      cacheOverrides,
      platformDiscoveryOperations
    )

  private val defaultNetworkPath: NetworkPath = NetworkPath("mainnet", PlatformPath("tezos"))
  private val defaultEntityPath: EntityPath = EntityPath("entity", defaultNetworkPath)

  "The metadata service" should {

      "fetch the list of supported platforms" in {
        sut(Map("tezos" -> PlatformConfiguration(None, Some(true)))).getPlatforms shouldBe List(
          Platform("tezos", "Tezos")
        )
      }

      "override the display name for a platform" in {
        sut(Map("tezos" -> PlatformConfiguration(Some("overwritten name"), Some(true)))).getPlatforms shouldBe List(
          Platform("tezos", "overwritten name")
        )
      }

      "override description for a platform" in {
        sut(Map("tezos" -> PlatformConfiguration(Some("overwritten name"), Some(true), Some("description")))).getPlatforms shouldBe List(
          Platform("tezos", "overwritten name", Some("description"))
        )
      }

      "filter out disabled platform" in {
        sut(Map("tezos" -> PlatformConfiguration(None, Some(false)))).getPlatforms shouldBe List.empty
      }

      "filter out disabled platform (default behaviour)" in {
        sut(Map.empty).getPlatforms shouldBe List.empty
      }

      "fetch the list of supported networks" in {
        // given
        val overriddenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(None, Some(true))
                )
              )
        )

        // expect
        sut(overriddenConfiguration).getNetworks(PlatformPath("tezos")) shouldBe Some(
          List(Network("mainnet", "Mainnet", "tezos", "mainnet"))
        )
      }

      "override the display name for a network" in {
        // given
        val overriddenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(Some("overwritten name"), Some(true))
                )
              )
        )

        // when
        val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

        // then
        result shouldBe Some(List(Network("mainnet", "overwritten name", "tezos", "mainnet")))
      }

      "override description for a network" in {
        // given
        val overriddenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(Some("overwritten name"), Some(true), Some("description"))
                )
              )
        )

        // when
        val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

        // then
        result shouldBe Some(List(Network("mainnet", "overwritten name", "tezos", "mainnet", Some("description"))))
      }

      "filter out a hidden network" in {
        // given
        val overriddenConfiguration = Map(
          "tezos" -> PlatformConfiguration(
                None,
                Some(true),
                None,
                Map("mainnet" -> NetworkConfiguration(None, Some(false)))
              )
        )

        // when
        val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

        // then
        result shouldBe Some(List.empty)
      }

      "return None when fetching network for a non existing platform" in {
        // when
        val result = sut(Map.empty).getNetworks(PlatformPath("non-existing-platform"))

        // then
        result shouldBe None
      }

      "return None when fetching networks for a hidden platform" in {
        // given
        val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

        // when
        val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

        // then
        result shouldBe None
      }

      "return None when fetching networks for a hidden platform (by default)" in {
        // given
        val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None))

        // when
        val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

        // then
        result shouldBe None
      }

      "fetch the list of supported entities" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, Some(true))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List(Entity("entity", "entity-name", 0)))
      }

      "fetch the list of supported entities with updated values" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, Some(true))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getCurrentEntities(defaultNetworkPath).futureValue

        // then
        result shouldBe Some(List(Entity("entity", "entity-name", 0)))
      }

      "override the display name for an entity" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(Some("overwritten name"), None, Some(true))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List(Entity("entity", "overwritten name", 0)))
      }

      "override the display name plural for an entity" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, Some("overwritten display name plural"), Some(true))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List(Entity("entity", "entity-name", 0, Some("overwritten display name plural"))))
      }

      "override description for an entity" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, Some(true), Some("description"))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List(Entity("entity", "entity-name", 0, None, Some("description"))))
      }

      "filter out a hidden entity" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, Some(false))
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List.empty)
      }

      "filter out a hidden entity (by default)" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, None)
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe Some(List.empty)
      }

      "filter out a hidden entity (by default) with updated entities" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(None, None, None)
                        )
                      )
                )
              )
        )

        // when
        val result =
          sut(overwrittenConfiguration).getCurrentEntities(defaultNetworkPath).futureValue

        // then
        result shouldBe Some(List.empty)
      }

      "return None when fetching entities for a non existing platform" in {
        // when
        val result =
          sut(Map.empty).getEntities(NetworkPath("mainnet", PlatformPath("non-existing-platform")))

        // then
        result shouldBe None
      }

      "return None when fetching entities for a hidden platform" in {
        // given
        val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(true)))

        // when
        val result = sut(overriddenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe None
      }

      "return None when fetching entities for a non existing network" in {
        // given
        val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(true)))

        // when
        val result =
          sut(overriddenConfiguration).getEntities(NetworkPath("non-existing-network", PlatformPath("tezos")))

        // then
        result shouldBe None
      }

      "return None when fetching entities for a hidden network" in {
        // given
        val overriddenConfiguration = Map(
          "tezos" -> PlatformConfiguration(
                None,
                Some(true),
                None,
                Map("mainnet" -> NetworkConfiguration(None, Some(false)))
              )
        )

        // when
        val result = sut(overriddenConfiguration).getEntities(defaultNetworkPath)

        // then
        result shouldBe None
      }

      "fetch the list of supported attributes" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(None, Some(true))
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        val result = sut(overwrittenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))
      }

      "fetch the list of supported attributes with updated values" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(None, Some(true))
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        val result = sut(overwrittenConfiguration)
          .getCurrentTableAttributes(defaultEntityPath)
          .futureValue

        // then
        result shouldBe Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))
      }

      "override additional fields for an attribute" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(
                                        displayName = Some("overwritten-name"),
                                        visible = Some(true),
                                        description = Some("description"),
                                        placeholder = Some("placeholder"),
                                        scale = Some(6),
                                        dataType = Some("hash"),
                                        dataFormat = Some("dataFormat"),
                                        valueMap = Some(Map("0" -> "value1", "1" -> "other value")),
                                        reference = Some(Map("0" -> "value1", "1" -> "other value")),
                                        displayPriority = Some(1),
                                        displayOrder = Some(2),
                                        currencySymbol = Some("ꜩ"),
                                        currencySymbolCode = Some(42793)
                                      )
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        val result = sut(overwrittenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe Some(
          List(
            Attribute(
              name = "attribute",
              displayName = "overwritten-name",
              dataType = Hash,
              cardinality = None,
              keyType = NonKey,
              entity = "entity",
              description = Some("description"),
              placeholder = Some("placeholder"),
              dataFormat = Some("dataFormat"),
              valueMap = Some(Map("0" -> "value1", "1" -> "other value")),
              reference = Some(Map("0" -> "value1", "1" -> "other value")),
              scale = Some(6),
              displayPriority = Some(1),
              displayOrder = Some(2),
              currencySymbol = Some("ꜩ"),
              currencySymbolCode = Some(42793)
            )
          )
        )
      }

      "filter out a hidden attribute" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(None, Some(false))
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        val result = sut(overwrittenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe Some(List.empty)
      }

      "filter out a hidden attribute (bu default)" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overwrittenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(
                        None,
                        Some(true),
                        None,
                        Map(
                          "entity" ->
                              EntityConfiguration(
                                None,
                                None,
                                Some(true),
                                None,
                                Map(
                                  "attribute" ->
                                      AttributeConfiguration(None, None)
                                )
                              )
                        )
                      )
                )
              )
        )

        // when
        val result = sut(overwrittenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe Some(List.empty)
      }

      "return None when fetching attributes for a non existing platform" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        // when
        val result = sut(Map.empty)
          .getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("non-existing-platform"))))

        // then
        result shouldBe None
      }

      "return None when fetching attributes for a hidden platform" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

        // when
        val result = sut(overriddenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe None
      }

      "return None when fetching attributes for a non existing network" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        // when
        val result = sut(Map.empty)
          .getTableAttributes(EntityPath("entity", NetworkPath("non-existing-network", PlatformPath("tezos"))))

        // then
        result shouldBe None
      }

      "return None when fetching attributes for a hidden network" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        // given
        val overriddenConfiguration = Map(
          "tezos" -> PlatformConfiguration(
                None,
                Some(true),
                None,
                Map("mainnet" -> NetworkConfiguration(None, Some(false)))
              )
        )

        // when
        val result = sut(overriddenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe None
      }

      "return None when fetching attributes for a non existing entity" in {
        // given
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        // when
        val result = sut(Map.empty).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe None
      }

      "return None when fetching attributes for a hidden entity" in {
        // given
        platformDiscoveryOperations.addEntity(defaultNetworkPath, Entity("entity", "entity-name", 0))
        platformDiscoveryOperations.addAttribute(
          defaultEntityPath,
          Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")
        )

        val overriddenConfiguration = Map(
          "tezos" ->
              PlatformConfiguration(
                None,
                Some(true),
                None,
                Map(
                  "mainnet" ->
                      NetworkConfiguration(None, Some(true))
                )
              )
        )

        // when
        val result = sut(overriddenConfiguration).getTableAttributes(defaultEntityPath)

        // then
        result shouldBe None
      }

    }
}
