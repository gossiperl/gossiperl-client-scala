package com.gossiperl.client

import akka.actor._
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.LazyLogging
import concurrent.duration._
import scala.concurrent.Promise
import scala.util.{Failure, Success}

trait GossiperlClient extends LazyLogging {

  val system = ActorSystem("gossiperl-system")
  val supervisor = system.actorOf(Props[Supervisor], name=Supervisor.actorName)
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(1 seconds)

  def withOverlay( configuration: OverlayConfiguration ) ( action: Option[GossiperlProxy] => Unit ):Unit = {
    system.actorSelection(s"/user/${Supervisor.actorName}").resolveOne( timeout.duration ) onComplete {
      case Success(a) =>
        val p = Promise[GossiperlProxy]
        p.future onComplete {
          case Success( proxy ) =>
            action.apply( Some( proxy ) )
          case Failure( ex ) =>
            logger.error(s"Error while awaiting for gossiperl proxy with configuration $configuration.", ex)
            action.apply( None )
        }
        a ! SupervisorProtocol.Connect( configuration, p )
      case Failure(ex) =>
        logger.error("Error while looking up client supervisor. Is it up?", ex)
        action.apply( None )
    }
  }

}

