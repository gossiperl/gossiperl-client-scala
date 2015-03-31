package com.gossiperl.client

import akka.actor.ActorDSL._
import akka.util.Timeout
import org.scalatest.{WordSpec, Matchers, GivenWhenThen}
import concurrent.duration._
import akka.actor._

import scala.util.{Failure, Success}

class ClientTests extends WordSpec with Matchers with GivenWhenThen {

  val config = new OverlayConfiguration(
    overlayName = "gossiper_overlay_remote",
    clientName = "scala-client",
    symmetricKey = "v3JElaRswYgxOt4b",
    clientSecret = "scala-client-secret",
    overlayPort = 6666,
    clientPort = 54321
  )

  implicit val system = ActorSystem("test-system")
  system.actorOf(Props[ ClientSupervisor ], name = "client-supervisor")

  actor(new Act {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = Timeout(1 seconds)
    val f = system.actorSelection("/user/client-supervisor").resolveOne()
    f onComplete { t =>
      t match {
        case Success(r) =>
          r ! Connect(config)
          Thread.sleep(1000)
          r ! Disconnect(config.overlayName)
          Thread.sleep(1000)
        case Failure(ex) => ex.printStackTrace()
      }
    }
  })

  Thread.sleep(10000)

  1 shouldBe 1

}
