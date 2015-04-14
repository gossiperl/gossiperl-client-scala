package com.gossiperl.client

import com.gossiperl.client.actors.ActorNotRegisteredException
import org.scalatest.concurrent.AsyncAssertions
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.scalatest._
import org.scalatest.time.{Millis, Seconds, Span}

import scala.util.{Failure, Success}

class ProcessTest extends FeatureSpec with GivenWhenThen with ShouldMatchers with AsyncAssertions with GossiperlClient with LazyLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  val w = new Waiter
  val disconnectWaiter = new Waiter
  val topics1 = Seq[String]("member_in", "member_out")
  val topics2 = Seq[String]("custom_digest")

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(100, Millis)))

  feature("Gossiperl Scala client") {

    scenario("Communication process") {

      Given("Valid configuration")

      val config = new OverlayConfiguration(
        overlayName = "gossiper_overlay_remote",
        clientName = "scala-client",
        symmetricKey = "v3JElaRswYgxOt4b",
        clientSecret = "scala-client-secret",
        overlayPort = 6666,
        clientPort = 54321 )

      When("Connection is requested")

      withOverlay( config ) {
        case Some(proxy) =>

          Then("Proxy should be returned")

          proxy shouldBe a [GossiperlProxy]

          proxy.event {
            case GossiperlProxyProtocol.Connected( config ) =>

              When("after requesting the connection")
              val w2 = new Waiter; proxy.currentState onComplete {
                case Success(Some(state)) => state shouldBe FSMState.ClientStateConnected; w2.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for current state. Overlay not there?")); w2.dismiss
                case Failure(ex) => Assertions.fail(ex); w2.dismiss
              }
              Then("state is connected"); w2.await

              When("subscribing to some topics")
              val w3 = new Waiter; proxy.subscribe( topics1 ) onComplete {
                case Success(Some( subscribedEvents )) => subscribedEvents shouldEqual topics1; w3.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for subscriptions. Overlay not there?")); w3.dismiss
                case Failure(ex) => Assertions.fail(ex); w3.dismiss
              }
              Then("same topics are yielded back"); w3.await

              When("subscribing to more topics")
              val w4 = new Waiter; proxy.subscribe( topics2 ) onComplete {
                case Success(Some( subscribedEvents )) => subscribedEvents shouldEqual topics2; w4.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for subscriptions. Overlay not there?")); w4.dismiss
                case Failure(ex) => Assertions.fail(ex); w4.dismiss
              }
              Then("only those topics are yielded back"); w4.await

              When("asking for all subscriptions")
              val w5 = new Waiter; proxy.subscriptions onComplete {
                case Success(Some( allSubscriptions )) => allSubscriptions shouldEqual (topics1 ++ topics2).sorted.distinct; w5.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for subscriptions request. Overlay not there?")); w5.dismiss
                case Failure(ex) => Assertions.fail(ex); w5.dismiss
              }
              Then("all subscriptions, sorted, are yielded back"); w5.await

              When("unsubscribing from some topics")
              val w6 = new Waiter; proxy.unsubscribe( topics1 ) onComplete {
                case Success(Some( unsubscribedEvents )) => unsubscribedEvents shouldEqual topics1; w6.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for subscriptions. Overlay not there?")); w6.dismiss
                case Failure(ex) => Assertions.fail(ex); w6.dismiss
              }
              Then("those topics are yielded back"); w6.await

              When("asking for all subscriptions after unsubscribing from part of the topics")
              val w7 = new Waiter; proxy.subscriptions onComplete {
                case Success(Some( allSubscriptions )) => allSubscriptions shouldEqual topics2; w7.dismiss
                case Success(None) => Assertions.fail(new RuntimeException("No result for subscriptions request. Overlay not there?")); w7.dismiss
                case Failure(ex) => Assertions.fail(ex); w7.dismiss
              }
              Then("only the remainder of the topics is yielded back"); w7.await

              When("requested disconnect")
              proxy.disconnect
              Thread.sleep(1000)
              val w8 = new Waiter; proxy.currentState onComplete {
                case Failure(ex) => ex shouldBe a [ActorNotRegisteredException]; w8.dismiss
                case anyOther => Assertions.fail( new RuntimeException( s"Unexpected result $anyOther after requesting overlay shutdown." ) ); w8.dismiss
              }
              Then("the overlay should no longer exist"); w8.await

              w.dismiss()

            case any => logger.debug(s"Received an event: $any")

          }

        case None =>
          Assertions.fail("No proxy returned")
          w.dismiss()
      }

      w.await()

    }
  }
}
