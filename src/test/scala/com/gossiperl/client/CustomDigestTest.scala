package com.gossiperl.client

import com.gossiperl.client.serialization.{DeserializeResultCustomOK, CustomDigestField, Serializer}
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.time.{Millis, Seconds, Span}

class CustomDigestTest  extends FeatureSpec with GivenWhenThen with ShouldMatchers with GossiperlClient with LazyLogging {

  import scala.collection.JavaConversions._

  val customDigestType = "CustomDigestType"
  val digestData = Seq[CustomDigestField](
    new CustomDigestField("field_name", "some value for the field", 1),
    new CustomDigestField("integer_field", 1234L, 2) )

  val digestInfo = Set[CustomDigestField](
    new CustomDigestField("field_name", "", 1),
    new CustomDigestField("integer_field", 1L, 2) )

  feature("Gossiperl Scala client") {

    scenario("Custom digest") {

      Given("digest type and digest data to the serializer")

      val serialized = new Serializer().serializeArbitrary( customDigestType, digestData.toList )

      When("deserialized")

      val deserialized = new Serializer().deserializeArbitrary( customDigestType, serialized, digestInfo.toList )

      Then("resulting data is the same as an input")

      deserialized shouldBe a [DeserializeResultCustomOK]

      val resultOk = deserialized.asInstanceOf[DeserializeResultCustomOK]
      resultOk.getDigestType shouldEqual customDigestType
      resultOk.getResultData.toMap.keySet.size shouldEqual digestInfo.size

    }
  }
}
