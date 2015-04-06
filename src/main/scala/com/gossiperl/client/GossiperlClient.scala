package com.gossiperl.client

import akka.actor._
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.LazyLogging
import concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

trait GossiperlClient extends LazyLogging {

  val system = ActorSystem("gossiperl-system")
  val supervisor = system.actorOf(Props[ClientSupervisor], name=ClientSupervisor.actorName)
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(5 seconds)

  def withOverlay( configuration: OverlayConfiguration, action: Option[GossiperlProxy] => Unit ):Unit = {
    system.actorSelection(s"/user/${ClientSupervisor.actorName}").resolveOne( timeout.duration ) onComplete {
      case Success(a) =>
        val p = Promise[GossiperlProxy]
        p.future onComplete {
          case Success( proxy ) =>
            action.apply( Some( proxy ) )
          case Failure( ex ) =>
            logger.error(s"Error while awaiting for gossiperl proxy with configuration $configuration.", ex)
            action.apply( None )
        }
        a ! ClientSupervisorProtocol.Connect( configuration, p )
      case Failure(ex) =>
        logger.error("Error while looking up client supervisor. Is it up?", ex)
        action.apply( None )
    }
  }

}

