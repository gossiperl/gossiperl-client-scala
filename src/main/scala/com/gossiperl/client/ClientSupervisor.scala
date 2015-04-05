package com.gossiperl.client

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.{Promise, Await}
import scala.util.{Failure, Success}

import ClientSupervisorProtocol._

object ClientSupervisorProtocol {

  case class Connect(configuration:OverlayConfiguration)

  case class Disconnect(overlayName:String)

  case class CheckState(overlayName:String, p:Promise[ Option[ FSMProtocol.ResponseCurrentState ] ])

  case class Subscriptions(overlayName:String)

  case class Subscribe(overlayName:String, eventTypes:Seq[String])

  case class Unsubscribe(overlayName:String, eventTypes:Seq[String])

  case class Send(overlayName:String, digestType:String, digestData:List[AnyRef])

  case class Read(digestType:String, binDigest:Array[Byte], digestInfo:List[AnyRef])

}

object ClientSupervisor {
  val actorName = "gossiperl-client-supervisor"
}

class ClientSupervisor extends Actor with ActorLogging {

    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    private val configurationStore = scala.collection.mutable.Map.empty[String, OverlayConfiguration]

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
      case _:Exception => Escalate
    }

    implicit val timeout = Timeout(5 seconds)

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
        log.debug(s"Overlay ${configuration.overlayName} shutdown complete.")
      case Disconnect(overlayName) =>
        log.debug(s"Requesting shutdown for overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName", o => { o match {
            case Some(a) => a ! RequestShutdown()
            case None => log.error(s"Could not request overlay $overlayName shutdown. Overlay does not exist.")
        } } )
      case CheckState(overlayName, p) =>
        log.debug(s"Requesting client state for overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some(a) =>
            a ? FSMProtocol.RequestCurrentState onComplete {
              case Success(r) =>  p.success( Some(r.asInstanceOf[FSMProtocol.ResponseCurrentState]) )
              case Failure(ex) => p.failure( ex )
            }
          case None =>
            log.error(s"Could not request client state for $overlayName. Overlay does not exist.")
            sender ! None
        } )
      case Subscriptions(overlayName) =>
        log.debug(s"Requesting subscriptions for overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some(a) =>
            // TODO: try
            val f = a ? FSMProtocol.RequestSubscriptions
            sender ! Await.result( f, timeout.duration ).asInstanceOf[ FSMProtocol.ResponseSubscriptions ]
          case None =>
            log.error(s"Could not request subscription for $overlayName. Overlay does not exist.")
            sender ! None
        } )
      case Subscribe(overlayName, eventTypes) =>
        log.debug(s"Attempting subscribing to $eventTypes on overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some( a ) =>
            // TODO: try
            val f = a ? FSMProtocol.RequestSubscribe( eventTypes )
            sender ! Await.result( f, timeout.duration ).asInstanceOf[ FSMProtocol.ResponseSubscribe ]
          case None =>
            log.error(s"Could not subscribe to $eventTypes on $overlayName. Overlay does not exist.")
            sender ! None
        } )
      case Unsubscribe(overlayName, eventTypes) =>
        log.debug(s"Attempting unsubscribing from $eventTypes on overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some(a) =>
            val f = a ? FSMProtocol.RequestUnsubscribe (eventTypes)
            sender ! Await.result (f, timeout.duration).asInstanceOf[FSMProtocol.ResponseUnsubscribe]
          case None =>
            log.error(s"Could not unsubscribe from $eventTypes on $overlayName. Overlay does not exist.")
        } )
      case Send(overlayName, digestType, digestData) =>
        log.info("Sending a digest...")
      case Read(digestType, binDigest, digestInfo) =>
        log.info("Reading a digest...")
    }

    def overlayForConfiguration(overlayName:String):Option[OverlayConfiguration] = {
      configurationStore.get(overlayName)
    }

    private def resolveOverlayActor(overlayName:String, actorPath:String, cb: Option[ActorRef] => Unit ):Unit = {
      implicit val timeout = Timeout(1 seconds)
      overlayForConfiguration(overlayName) match {
        case Some(_) =>
          context.actorSelection(s"/user/${ClientSupervisor.actorName}/$actorPath").resolveOne() onComplete {
            case Success(a) =>
              cb.apply( Some(a) )
            case Failure(ex) =>
              log.error(s"Could not load actor for $overlayName", ex)
              cb.apply( None )
          }
        case None =>
          log.error(s"Overlay $overlayName does not exist.")
          cb.apply( None )
      }
    }

}