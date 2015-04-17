package com.gossiperl.client

import akka.actor.{ActorRef, Props, ActorLogging}
import akka.util.Timeout
import com.gossiperl.client.actors.ActorEx
import com.gossiperl.client.transport.UdpTransport

import scala.concurrent.Promise
import scala.util.{Failure, Success}
import concurrent.duration._

class OverlayConfiguration( val overlayName:String,
                            val clientName:String,
                            val clientSecret:String,
                            val symmetricKey:String,
                            val overlayPort:Int,
                            val clientPort:Int,
                            val thriftWindowSize:Int = 1024 ) {
  override def toString() = {
    s"OverlayConfiguration(overlayName=${overlayName}, clientName=${clientName}, clientSecret=<protected>, symmetricKey=<protected>, overlayPort=${overlayPort}, clientPort=${clientPort}, thriftWindowSize=${thriftWindowSize})"
  }
}

class OverlayWorker(val configuration:OverlayConfiguration) extends ActorEx with ActorLogging {

  context.actorOf(Props( new UdpTransport(configuration) ), name = "transport")
  context.actorOf(Props( new Messaging(configuration) ), name = "messaging")
  context.actorOf(Props( new State(configuration) ), name = "client-state")

  log.debug(s"Overlay ${configuration.overlayName} is running.")

  implicit val timeout = Timeout(1 seconds)
  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case GossiperlProxyProtocol.ShutdownRequest( p ) =>
      log.debug("Received shutdown request.")
      val p2 = Promise[ActorRef]
      !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}/client-state", GossiperlProxyProtocol.ShutdownRequest( p2 ) ) onFailure {
        case ex =>
          log.error("Could not request state shutdown. State actor not found. Proceeding with shutdown.")
          !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}/messaging", GossiperlProxyProtocol.ShutdownRequest( Promise[ActorRef] ) ) // we're not interested in the result of this promise
          p.failure(ex)
          context.stop(self)
      }
      p2.future.onComplete {
        case Success(_)  =>
          !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}/messaging", GossiperlProxyProtocol.ShutdownRequest( Promise[ActorRef] ) ) // we're not interested in the result of this promise
          p.success(self)
          context.stop(self)
        case Failure(ex) =>
          log.error("Error while announcing shutdown request of the client state. Proceeding with shutdown.", ex)
          !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}/messaging", GossiperlProxyProtocol.ShutdownRequest( Promise[ActorRef] ) ) // we're not interested in the result of this promise
          p.failure(ex)
          context.stop(self)
      }
  }

}
