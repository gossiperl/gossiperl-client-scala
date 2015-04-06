package com.gossiperl.client

import akka.actor._
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.LazyLogging
import concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

trait GossiperlClient {

  val system = ActorSystem("gossiperl-system")
  val supervisor = system.actorOf(Props[ClientSupervisor], name=ClientSupervisor.actorName)

  def withOverlay( configuration: OverlayConfiguration, action: Option[GossiperlProxy] => Unit ):Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = Timeout(5 seconds)
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne( timeout.duration ) onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.Connect( configuration )
        action.apply( Some( new GossiperlProxy( system, configuration ) ) )
      case Failure(ex) =>
        action.apply( None )
    }
  }

}

class GossiperlProxy( val system:ActorSystem, val configuration: OverlayConfiguration ) extends LazyLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val actorSystem = system
  implicit val timeout = Timeout(5 seconds)

  // TODO: these calls below can be generalised

  def disconnect():Unit = {
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.Disconnect( configuration.overlayName )
      case Failure(ex) => logger.error(s"[${configuration.overlayName}}] Disconnect failed. Supervisor not started.", ex)
    }
  }

  def currentState():Future[Option[FSMState.ClientState]] = {
    val p = Promise[Option[FSMState.ClientState]]
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.CheckState( configuration.overlayName, p )
      case Failure(ex) =>
        logger.debug(s"[${configuration.overlayName}}] Current status check failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def subscriptions():Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.Subscriptions( configuration.overlayName, p )
      case Failure(ex) =>
        logger.debug(s"[${configuration.overlayName}}] Current subscriptions check failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def subscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.Subscribe( configuration.overlayName, eventTypes, p )
      case Failure(ex) =>
        logger.error(s"[${configuration.overlayName}}] Subscribe to $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def unsubscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>
        a ! ClientSupervisorProtocol.Unsubscribe( configuration.overlayName, eventTypes, p )
      case Failure(ex) =>
        logger.error(s"[${configuration.overlayName}}] Unsubscribe from $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

}