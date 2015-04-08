package com.gossiperl.client

import akka.actor.ActorSystem
import akka.util.Timeout
import com.gossiperl.client.ClientSupervisorProtocol.ClientSupervisorAction
import com.typesafe.scalalogging.slf4j.LazyLogging

import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

import concurrent.duration._

class GossiperlProxy( val system:ActorSystem, val configuration: OverlayConfiguration ) extends LazyLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val actorSystem = system
  implicit val timeout = Timeout(5 seconds)

  def disconnect():Unit = {
    action( ClientSupervisorProtocol.Disconnect( configuration.overlayName ) ) onFailure {
      case ex => logger.error(s"[${configuration.overlayName}}] Disconnect failed. Supervisor not started.", ex)
    }
  }

  def currentState():Future[Option[FSMState.ClientState]] = {
    val p = Promise[Option[FSMState.ClientState]]
    action( ClientSupervisorProtocol.CheckState( configuration.overlayName, p ) ) onFailure {
      case ex =>
        logger.debug(s"[${configuration.overlayName}}] Current status check failed. Supervisor not started.", ex)
        p.failure(ex)
    }
    p.future
  }

  def subscriptions():Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( ClientSupervisorProtocol.Subscriptions( configuration.overlayName, p ) ) onFailure {
      case ex =>
        logger.debug(s"[${configuration.overlayName}}] Current subscriptions check failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def subscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( ClientSupervisorProtocol.Subscribe( configuration.overlayName, eventTypes, p ) ) onFailure {
      case ex =>
        logger.error(s"[${configuration.overlayName}}] Subscribe to $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def unsubscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( ClientSupervisorProtocol.Unsubscribe( configuration.overlayName, eventTypes, p ) ) onFailure {
      case ex =>
        logger.error(s"[${configuration.overlayName}}] Unsubscribe from $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  private def action( action: ClientSupervisorAction ):Future[Boolean] = {
    val p = Promise[Boolean]; system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>  a ! action ; p.success(true)
      case Failure(ex) => p.failure(ex)
    }; p.future
  }

}
