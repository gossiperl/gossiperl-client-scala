package com.gossiperl.client

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.util.Timeout

import scala.util.{Failure, Success}

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
    import scala.concurrent.ExecutionContext.Implicits.global

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
            log.debug(s"Requesting overlay ${configuration.overlayName}")
            context.actorOf(Props(new OverlaySupervisor(configuration)), name = configuration.overlayName)
            configurationStore.put( configuration.overlayName, configuration )
        }
      case OverlayShutdownComplete(configuration) =>
        configurationStore.remove(configuration.overlayName)
        log.debug(s"Overlay ${configuration.overlayName} shutdown complete...")
      case Disconnect(overlayName) =>
        overlayForConfiguration(overlayName) match {
          case Some(_) =>
            resolveOverlayActor(overlayName, a => {
              log.debug(s"Requesting shutdown of overlay $overlayName")
              a ! RequestShutdown()
            } )
          case None => log.error(s"Overlay $overlayName does not exist.")
        }
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

    private def resolveOverlayActor(overlayName:String, cb: ( ActorRef ) => Unit ):Unit = {
      implicit val timeout = Timeout(1 seconds)
      val f = context.actorSelection(s"$overlayName").resolveOne()
      f onComplete { t =>
        t match {
          case Success(a) =>
            log.debug(s"Actor for overlay $overlayName found.")
            cb( a )
          case Failure(ex) =>
            log.info(s"Actor for overlay $overlayName not resolved. Error $ex.")
            None
        }
      }
    }

}