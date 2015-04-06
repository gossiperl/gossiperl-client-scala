package com.gossiperl.client

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.util.Timeout

import scala.collection.mutable.{ Map => MutableMap }

import scala.concurrent.Promise
import scala.util.{Failure, Success}

import ClientSupervisorProtocol._

object ClientSupervisorProtocol {

  case class Connect(configuration:OverlayConfiguration, p:Promise[GossiperlProxy])

  case class Disconnect(overlayName:String)

  case class CheckState(overlayName:String, p:Promise[Option[FSMState.ClientState]])

  case class Subscriptions(overlayName:String, p:Promise[Option[Seq[String]]])

  case class Subscribe(overlayName:String, eventTypes:Seq[String], p:Promise[Option[Seq[String]]])

  case class Unsubscribe(overlayName:String, eventTypes:Seq[String], p:Promise[Option[Seq[String]]])

  case class Send(overlayName:String, digestType:String, digestData:List[AnyRef])

  case class Read(digestType:String, binDigest:Array[Byte], digestInfo:List[AnyRef])

}

object ClientSupervisor {
  val actorName = "gossiperl-client-supervisor"
}

class ClientSupervisor extends Actor with ActorLogging {

    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    private val proxyStore = MutableMap.empty[String, GossiperlProxy]

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
      case _:Exception => Escalate
    }

    implicit val timeout = Timeout(5 seconds)

    // TODO: most calls below can be generalised

    def receive = {
      case Connect(configuration, p) =>
        proxyForConfiguration( configuration.overlayName ) match {
          case Some(proxy) =>
            log.warning(s"Overlay ${configuration.overlayName} already exists.")
            p.success( proxy )
          case None =>
            log.debug(s"Requesting overlay ${configuration.overlayName}")
            context.actorOf(Props(new OverlaySupervisor(configuration)), name = configuration.overlayName)
            val proxy = new GossiperlProxy( context.system, configuration )
            proxyStore.put( configuration.overlayName, proxy )
            p.success( proxy )
        }
      case OverlayShutdownComplete(configuration) =>
        proxyStore.remove(configuration.overlayName)
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
            a ! FSMProtocol.RequestCurrentState( p )
          case None =>
            log.error(s"Could not request client state for $overlayName. Overlay does not exist.")
            p.success( None )
        } )
      case Subscriptions(overlayName, p) =>
        log.debug(s"Requesting subscriptions for overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some(a) =>
            a ! FSMProtocol.RequestSubscriptions( p )
          case None =>
            log.error(s"Could not request subscription for $overlayName. Overlay does not exist.")
            p.success( None )
        } )
      case Subscribe(overlayName, eventTypes, p) =>
        log.debug(s"Attempting subscribing to $eventTypes on overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some( a ) =>
            a ! FSMProtocol.RequestSubscribe( eventTypes, p )
          case None =>
            log.error(s"Could not subscribe to $eventTypes on $overlayName. Overlay does not exist.")
            p.success( None )
        } )
      case Unsubscribe(overlayName, eventTypes, p) =>
        log.debug(s"Attempting unsubscribing from $eventTypes on overlay $overlayName")
        resolveOverlayActor(overlayName, s"$overlayName/$overlayName-client-state", {
          case Some(a) =>
            a ! FSMProtocol.RequestUnsubscribe( eventTypes, p )
          case None =>
            log.error(s"Could not unsubscribe from $eventTypes on $overlayName. Overlay does not exist.")
            p.success( None )
        } )
      case Send(overlayName, digestType, digestData) =>
        log.info("Sending a digest...")
      case Read(digestType, binDigest, digestInfo) =>
        log.info("Reading a digest...")
    }

    private def proxyForConfiguration(overlayName:String):Option[GossiperlProxy] = {
      proxyStore.get(overlayName)
    }

    private def resolveOverlayActor(overlayName:String, actorPath:String, cb: Option[ActorRef] => Unit ):Unit = {
      implicit val timeout = Timeout(1 seconds)
      proxyForConfiguration(overlayName) match {
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