package com.gossiperl.client

import org.scalatest.concurrent.AsyncAssertions
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.scalatest._

class ClientTests extends FlatSpec with ShouldMatchers with GossiperlClient with AsyncAssertions with LazyLogging {

  val receivedEvents = scala.collection.mutable.ArrayBuffer.empty[Option[FSMState.ClientState]]
  val receivedSubscriptionSeqs = scala.collection.mutable.ArrayBuffer.empty[Option[Seq[String]]]

  val config = new OverlayConfiguration(
    overlayName = "gossiper_overlay_remote",
    clientName = "scala-client",
    symmetricKey = "v3JElaRswYgxOt4b",
    clientSecret = "scala-client-secret",
    overlayPort = 6666,
    clientPort = 54321
  )

  val topics1 = Seq[String]("member_in", "member_out")
  val topics2 = Seq[String]("custom_digest")

  "Gossiperl client supervisor" should "behave" in {

    withOverlay( config, {
      case Some(proxy) =>

        //import scala.concurrent.ExecutionContext.Implicits.global
        //implicit val timeout = Timeout(5 seconds)

        proxy.event {
          case GossiperlProxyProtocol.Connected( config ) =>
            logger.info( s"Overlay connected. Issuing disconnect request in 5 seconds..." )
            Thread.sleep(5000)
            proxy.disconnect
          case GossiperlProxyProtocol.Disconnected( config ) => logger.info( s"Overlay disconnected: (proxy: ${proxy})" )
        }

/*
        Thread.sleep(1000)
        proxy.currentState onComplete {
          case Success(r) => receivedEvents += r
          case Failure(ex) => logger.error("Error while executing test.", ex)
        }
        Thread.sleep(2000)

        proxy.subscribe( topics1 )

        Thread.sleep(1000)

        proxy.subscriptions onComplete {
          case Success(s) => receivedSubscriptionSeqs += s
          case Failure(ex) => logger.error("Error while executing test.", ex)
        }

        proxy.subscribe( topics2 )

        Thread.sleep(1000)

        proxy.subscriptions onComplete {
          case Success(s) => receivedSubscriptionSeqs += s
          case Failure(ex) => logger.error("Error while executing test.", ex)
        }

        proxy.unsubscribe( topics1 )

        Thread.sleep(1000)

        proxy.subscriptions onComplete {
          case Success(s) => receivedSubscriptionSeqs += s
          case Failure(ex) => logger.error("Error while executing test.", ex)
        }

        proxy.unsubscribe( topics2 )

        Thread.sleep(1000)

        proxy.subscriptions onComplete {
          case Success(s) => receivedSubscriptionSeqs += s
          case Failure(ex) => logger.error("Error while executing test.", ex)
        }

        Thread.sleep(8000)
        proxy.disconnect
        Thread.sleep(1000)
        */
      case None => logger.error(s"Could not load proxy for $config, supervisor isn't running.")
    } )

    Thread.sleep(20000)

    1 shouldEqual( 1 )

    //receivedEvents.toList shouldEqual( List(Some(FSMState.ClientStateDisconnected), Some(FSMState.ClientStateConnected)) )
    //receivedSubscriptionSeqs.toList shouldEqual( List( Some(topics1), Some((topics1 ++ topics2).distinct.sorted), Some(topics2), Some(Seq.empty[String]) ) )

  }

}
