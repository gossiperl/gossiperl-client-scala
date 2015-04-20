package com.gossiperl.client

import com.gossiperl.client.actors.FSMState
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.time.{Seconds, Span, Millis}

import scala.util.{Failure, Success}

class ProcessTest extends FlatSpec with GivenWhenThen with ShouldMatchers with AsyncAssertions with GossiperlClient with LazyLogging {

  // test setup and data:
  val stopAwait = new Waiter
  val topics1 = Seq[String]("member_in", "member_out")
  val topics2 = Seq[String]("custom_digest")

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(60, Seconds)), interval = scaled(Span(100, Millis)))

  // configuration:
  import scala.concurrent.ExecutionContext.Implicits.global

  Given("valid configuration")

  override implicit val configuration = new OverlayConfiguration(
                                    overlayName = "gossiper_overlay_remote",
                                    clientName = "scala-client",
                                    clientSecret = "scala-client-secret",
                                    symmetricKey = "v3JElaRswYgxOt4b",
                                    overlayPort = 6666,
                                    clientPort = 54321 )

  def event = {
    case GossiperlClientProtocol.Connected( _ ) =>

      When("after requesting the connection")
      val w2 = new Waiter; currentState onComplete {
        case Success(state) => state shouldBe FSMState.ClientStateConnected; w2.dismiss
        case Failure(ex) => Assertions.fail(ex); w2.dismiss
      }
      Then("state is connected"); w2.await

      When("subscribing to some topics")
      val w3 = new Waiter; subscribe( topics1 ) onComplete {
        case Success(subscribedEvents) => subscribedEvents shouldEqual topics1; w3.dismiss
        case Failure(ex) => Assertions.fail(ex); w3.dismiss
      }
      Then("same topics are yielded back"); w3.await

      When("subscribing to more topics")
      val w4 = new Waiter; subscribe( topics2 ) onComplete {
        case Success(subscribedEvents) => subscribedEvents shouldEqual topics2; w4.dismiss
        case Failure(ex) => Assertions.fail(ex); w4.dismiss
      }
      Then("only those topics are yielded back"); w4.await

      When("asking for all subscriptions")
      val w5 = new Waiter; subscriptions onComplete {
        case Success(allSubscriptions) => allSubscriptions shouldEqual (topics1 ++ topics2).sorted.distinct; w5.dismiss
        case Failure(ex) => Assertions.fail(ex); w5.dismiss
      }
      Then("all subscriptions, sorted, are yielded back"); w5.await

      When("unsubscribing from some topics")
      val w6 = new Waiter; unsubscribe( topics1 ) onComplete {
        case Success(unsubscribedEvents) => unsubscribedEvents shouldEqual topics1; w6.dismiss
        case Failure(ex) => Assertions.fail(ex); w6.dismiss
      }
      Then("those topics are yielded back"); w6.await

      When("asking for all subscriptions after unsubscribing from part of the topics")
      val w7 = new Waiter; subscriptions onComplete {
        case Success(allSubscriptions) => allSubscriptions shouldEqual topics2; w7.dismiss
        case Failure(ex) => Assertions.fail(ex); w7.dismiss
      }
      Then("only the remainder of the topics is yielded back"); w7.await

      When("requested disconnect")
      disconnect()

    case GossiperlClientProtocol.Disconnected( _ ) =>
      val w8 = new Waiter; currentState onComplete {
        case Success(state) => state shouldEqual FSMState.ClientStateDisconnected; w8.dismiss()
        case Failure(ex) => Assertions.fail( ex ); w8.dismiss()
      }
      Then("the overlay should no longer exist"); w8.await

    case GossiperlClientProtocol.Stopped( _ ) =>
      stopAwait.dismiss()
  }

  // kick it off:
  connect()

  stopAwait.await()

}
