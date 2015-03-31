package com.gossiperl.client

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{ActorLogging, OneForOneStrategy, Actor}

case class Connect(configuration:OverlayConfiguration)
case class Disconnect(overlayName:String)

case class CheckState(overlayName:String)
case class Subscriptions(overlayName:String)

case class Subscribe(overlayName:String, eventTypes:Array[String])
case class Unsubscribe(overlayName:String, eventTypes:Array[String])
case class Send(overlayName:String, digestType:String, digestData:List[AnyRef])
case class Read(digestType:String, binDigest:Array[Byte], digestInfo:List[AnyRef])

class ClientSupervisor extends Actor with ActorLogging {

    import scala.concurrent.duration._

    private val configurationStore = scala.collection.mutable.Map.empty[String, OverlayConfiguration]

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
      case _:Exception => Escalate
    }

    def receive = {
      case Connect(configuration) =>
        overlayForConfiguration( configuration.overlayName ) match {
          case Some(f) =>
            log.error(s"Overlay ${configuration.overlayName} already exists.")
          case None =>
            log.info("Overlay needs to be added")
        }
      case Disconnect(overlayName) =>
        log.info("Disconnecting to an overlay...")
      case CheckState(overlayName) =>
        log.info("Checking state of an overlay...")
      case Subscriptions(overlayName) =>
        log.info("Subscriptions of an overlay...")
      case Subscribe(overlayName, eventTypes) =>
        log.info("Subscriptions of an overlay...")
      case Unsubscribe(overlayName, eventTypes) =>
        log.info("Subscriptions of an overlay...")
      case Send(overlayName, digestType, digestData) =>
        log.info("Sending a digest...")
      case Read(digestType, binDigest, digestInfo) =>
        log.info("Reading a digest...")
    }

    def overlayForConfiguration(overlayName:String):Option[OverlayConfiguration] = {
      configurationStore.get(overlayName)
    }

}