package com.bisphone.sarf.implv1

import akka.actor.{ActorRef, ActorSystem, SupervisorStrategy}
import akka.util.{ByteString, Timeout}
import akka.pattern.ask
import tcpservice.Director

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.util._

import scala.concurrent.duration._

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
object TCPServer {

  def apply(
    name: String,
    tcpConfig: TCPConfigForServer,
    streamConfig: StreamConfig,
    debug: Boolean
    // ,executionContext: ExecutionContextExecutor
    // ,supervisorStrategy: Option[SupervisorStrategy] = None
  )(onRequest: ByteString => Future[IOCommand])(
    implicit actorSystem: ActorSystem
  ): Future[TCPServiceRef] = {

    val props = Director.props(name, tcpConfig, streamConfig, debug)(onRequest)

    val actorRef = actorSystem.actorOf(props, name)

    val ref: TCPServiceRefImpl =
      new TCPServiceRefImpl(
        actorRef,
        actorSystem.dispatcher,
        60 seconds
      )

    ref.waitUntilActivation()
  }

}

class TCPServiceRefImpl(
  val ref: ActorRef,
  ec: ExecutionContextExecutor,
  timeout: FiniteDuration
) extends TCPServiceRef {

  private implicit val akkaTimeout = Timeout(timeout)
  private implicit val internalEC = ec

  private def unexp[T](t: T) =
    throw new RuntimeException(s"Unexpected Response from director: ${t}")

  private def getState(): Future[Director.State] =
    ask(ref, Director.Command.GetState).map{
      case state: Director.State => state
      case any => unexp(any)
    }

  def isActive(): Future[Boolean] = {
    getState().map {
      case _:Director.State.Bound => true
      case _ => false
    }
  }

  def shutdown(): Future[Unit] = {
    ask(ref, Director.Command.Unbind).map{
      case Director.State.Unbound => ()
      case any => unexp(any)
    }
  }


  def waitUntilActivation(): Future[this.type] = {
    def loop():Future[Director.State] = getState().flatMap {
      case Director.State.Binding => loop()
      case st:Director.State.Bound => Future successful st
      case invalid => Future failed new RuntimeException(s"Invalid State: ${invalid}")
    }
    loop.map(_ => this)
  }

}
