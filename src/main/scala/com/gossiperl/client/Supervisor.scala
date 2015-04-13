package com.gossiperl.client

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.util.Timeout
import com.gossiperl.client.actors.ActorRegistry

import scala.collection.mutable.{ Map => MutableMap }

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

import SupervisorProtocol._

object SupervisorProtocol {

  sealed trait SupervisorAction

  case class Connect(configuration:OverlayConfiguration, p:Promise[GossiperlProxy]) extends SupervisorAction

  case class Disconnect(overlayName:String) extends SupervisorAction

  case class CheckState(overlayName:String, p:Promise[Option[FSMState.ClientState]]) extends SupervisorAction

  case class Subscriptions(overlayName:String, p:Promise[Option[Seq[String]]]) extends SupervisorAction

  case class Subscribe(overlayName:String, eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends SupervisorAction

  case class Unsubscribe(overlayName:String, eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends SupervisorAction

  case class Send(overlayName:String, digestType:String, digestData:List[AnyRef]) extends SupervisorAction

  case class Read(digestType:String, binDigest:Array[Byte], digestInfo:List[AnyRef]) extends SupervisorAction

}

object Supervisor {
  val actorName = "gossiperl-client-supervisor"
}

class Supervisor extends ActorRegistry with ActorLogging {

    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    private val proxyStore = MutableMap.empty[String, GossiperlProxy]

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
      case _:Exception => Escalate
    }

    implicit val timeout = Timeout(1 seconds)

    def receive = {
      case Connect(configuration, p) =>
        proxyForConfiguration( configuration.overlayName ) match {
          case Some(proxy) =>
            log.warning(s"Overlay ${configuration.overlayName} already exists.")
            p.success( proxy )
          case None =>
            log.debug(s"Requesting overlay ${configuration.overlayName}")
            val p2 = Promise[GossiperlProxy]
            p2.future.onComplete {
              case Success(proxy) =>
                context.actorOf(Props(new OverlayWorker(configuration)), name = configuration.overlayName)
                proxyStore.put( configuration.overlayName, proxy )
                p.success( proxy )
              case Failure(ex) =>  p.failure(ex)
            }
            context.actorOf(Props(new GossiperlProxy( configuration, p2 )), name=s"${configuration.overlayName}-proxy")
        }
      case Disconnect(overlayName) =>
        log.debug(s"Requesting shutdown for overlay $overlayName")
        proxyForConfiguration(overlayName) match {
          case Some(_) =>
            // What is going to happen, sequentially:
            //  - supervisor requests the shutdown of overlay worker
            //  - overlay worker requests shutdown of state
            //  - state sends digestExit, digestExit will be sent with an ack, once ack is received by transport,
            //    transport will issue an Unbind on itself, fulfill the promise for state and progress to transport stopped state
            //  - state, in any promise case, will issue stop shutdown request for messaging, fulfill the promise of worker and stop itself
            //  - worker, in any promise case, will fulfill the promise of the supervisor and stop itself
            //  - supervisor, in any promise case, will issue shutdown request of the proxy actor
            //  - in any case, result of the proxy removal will trigger state cleanup of the overlay - overlay will be considered removed
            def requestProxyShutdown:Unit = {
              val p2 = Promise[ActorRef]
              !:( s"$overlayName-proxy", GossiperlProxyProtocol.ShutdownRequest( p2 ) ) onFailure {
                case ex =>
                  proxyStore.remove(overlayName)
                  log.warning(s"\n ----------------------------------\n Shutdown of $overlayName complete but there was an error while announcing shutdown of the proxy. Proxy actor not found.\n ----------------------------------\n")
              }
              p2.future.onComplete {
                case Success(_) =>
                  proxyStore.remove(overlayName)
                  log.debug(s"\n ----------------------------------\n Shutdown of $overlayName complete.\n ----------------------------------\n")
                case Failure(ex) =>
                  proxyStore.remove(overlayName)
                  log.warning(s"\n ----------------------------------\n Shutdown of $overlayName complete but there was an error while announcing shutdown of the proxy.\n ----------------------------------\n")
              }
            }
            val p = Promise[ActorRef]
            !:( overlayName, GossiperlProxyProtocol.ShutdownRequest( p ) ) onFailure {
              case ex =>
                log.error("There was an error while requesting shutdown of the overlay worker, worker actor not found. Proceeding with shutdown.", ex)
                requestProxyShutdown
            }
            p.future onComplete {
              case Success(_) => requestProxyShutdown
              case Failure(ex) =>
                log.error("There was an error while requesting shutdown of the overlay worker. Proceeding with shutdown.", ex)
                requestProxyShutdown
            }
          case None => log.error(s"Could not request overlay $overlayName shutdown. Overlay does not exist.")
        }
      case CheckState(overlayName, p) =>
        log.debug(s"Requesting client state for overlay $overlayName")
        proxyForConfiguration(overlayName) match {
          case Some(_) =>
            !:( s"$overlayName-client-state", FSMProtocol.RequestCurrentState( p ) ) onFailure {
              case ex => p.failure( ex )
            }
          case None => p.failure( new RuntimeException(s"Overlay $overlayName does not exist.") )
        }
      case Subscriptions(overlayName, p) =>
        log.debug(s"Requesting subscriptions for overlay $overlayName")
        proxyForConfiguration(overlayName) match {
          case Some(_) =>
            !:( s"$overlayName-client-state", FSMProtocol.RequestSubscriptions( p ) ) onFailure {
              case ex => p.failure( ex )
            }
          case None => p.failure( new RuntimeException(s"Overlay $overlayName does not exist.") )
        }
      case Subscribe(overlayName, eventTypes, p) =>
        log.debug(s"Attempting subscribing to $eventTypes on overlay $overlayName")
        proxyForConfiguration(overlayName) match {
          case Some( _ ) =>
            !:( s"$overlayName-client-state", FSMProtocol.RequestSubscribe( eventTypes, p ) ) onFailure {
              case ex => p.failure(ex)
            }
          case None => p.failure( new RuntimeException(s"Overlay $overlayName does not exist.") )
        }
      case Unsubscribe(overlayName, eventTypes, p) =>
        log.debug(s"Attempting unsubscribing from $eventTypes on overlay $overlayName")
        proxyForConfiguration(overlayName) match {
          case Some(_) =>
            !:(s"$overlayName-client-state", FSMProtocol.RequestUnsubscribe( eventTypes, p ) ) onFailure {
              case ex => p.failure(ex)
            }
          case None => p.failure( new RuntimeException(s"Overlay $overlayName does not exist.") )
        }
      case Send(overlayName, digestType, digestData) =>
        log.info("Sending a digest...")
      case Read(digestType, binDigest, digestInfo) =>
        log.info("Reading a digest...")
    }

    private def proxyForConfiguration(overlayName:String):Option[GossiperlProxy] = {
      proxyStore.get(overlayName)
    }

}