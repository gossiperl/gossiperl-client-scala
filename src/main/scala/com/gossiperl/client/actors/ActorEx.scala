package com.gossiperl.client.actors

import akka.actor.{ActorRef, Actor}

import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

class ActorNotRegisteredException(message: String, cause: Throwable) extends RuntimeException(message, cause)

trait ActorEx extends Actor {
  def !:( path: String, message: Any):Future[ActorRef] = {

    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.util.Timeout
    import concurrent.duration._
    implicit val timeout = Timeout(1 seconds)

    val p = Promise[ActorRef]
    context.system.actorSelection( path ) resolveOne() onComplete {
      case Success(a) =>
        a ! message; p.success(a)
      case Failure(ex) =>
        p.failure(new ActorNotRegisteredException( path, ex ))
    }
    p.future
  }

}
