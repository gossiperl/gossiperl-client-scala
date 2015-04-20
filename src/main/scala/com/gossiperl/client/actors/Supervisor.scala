package com.gossiperl.client.actors

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import com.gossiperl.client.exceptions.GossiperlClientException
import com.gossiperl.client.{GossiperlClientProtocol, GossiperlClient, OverlayConfiguration}

import scala.collection.mutable.{ Map => MutableMap }

object SupervisorProtocol {

  case class Connect( c: OverlayConfiguration, client: GossiperlClient )
  case class Disconnect( c: OverlayConfiguration )
  case class ForwardEvent( config: OverlayConfiguration, event: GossiperlClientProtocol.ProtocolOp )
  case class OverlayStopped( c: OverlayConfiguration )

}

object Supervisor {

  val supervisorName = "gossiperl-client-supervisor"

  protected val clientStore = MutableMap.empty[String, GossiperlClient]

}

class Supervisor extends ActorEx with ActorLogging {

  import Supervisor._

  import akka.util.Timeout
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(1 seconds)

  log.debug(s"Supervisor $supervisorName running.")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
    case _:Exception => Escalate
  }

  def receive = {
    case SupervisorProtocol.Connect( config, client ) =>
      clientStore.get( config.overlayName ) match {
        case Some(existing) =>
          val message = s"Overlay ${config.overlayName} already used by client $existing"
          client.event.applyOrElse( GossiperlClientProtocol.Error(config, message, new GossiperlClientException(message)), unhandled )
        case None =>
          context.actorOf(Props( new OverlayWorker( config ) ), name = config.overlayName)
          clientStore.put( config.overlayName, client )
          client.event.applyOrElse( GossiperlClientProtocol.Accepted( config ), unhandled )
      }
    case SupervisorProtocol.OverlayStopped( config ) =>
      clientStore.get( config.overlayName ) match {
        case Some(stopped) =>
          log.debug(s"Overlay ${config.overlayName} is now stopped. Notifying the client...")
          clientStore.remove(config.overlayName)
          stopped.event.applyOrElse( GossiperlClientProtocol.Stopped( config ), unhandled )
        case None =>
          log.error(s"Could not find the client for ${config.overlayName}. Could not notify stopped state.")
      }
    case SupervisorProtocol.Disconnect( config ) =>
      clientStore.get( config.overlayName ) match {
        case Some(client) =>
          !:(s"/user/${supervisorName}/${config.overlayName}", OverlayWorkerProtocol.Disconnect) onFailure {
            case ex =>
              self ! SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error(config, "There was an error handing in a shutdown request.", ex) )
          }
        case None => log.error(s"Overlay ${config.overlayName} not found.")
      }
    case SupervisorProtocol.ForwardEvent( config, sourceEvent ) =>
      clientStore.get( config.overlayName ) match {
        case Some(client) =>
          client.event.applyOrElse( sourceEvent, unhandled )
        case None => log.error(s"Overlay ${config.overlayName} not found. Event $sourceEvent not handed in.")
      }
  }

}
