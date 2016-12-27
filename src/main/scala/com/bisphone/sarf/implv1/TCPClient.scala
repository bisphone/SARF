package com.bisphone.sarf.implv1

import akka.actor.{ActorRef, ActorSystem}
import akka.util.ByteString
import com.bisphone.sarf.implv1.tcpclient.Director
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient}
import com.bisphone.util._
import com.bisphone.std._
import akka.pattern.ask

import scala.reflect.{ClassTag, classTag}
import scala.concurrent._
import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
object TCPClient {

   def apply[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
      name: String,
      tcp: TCPConfigForClient,
      stream: StreamConfig,
      writer: FrameWriter[Fr, UFr],
      reader: FrameReader[Fr]
   )(
      implicit
      actorSystem: ActorSystem,
      fr$tag: ClassTag[Fr],
      uf$tag: ClassTag[UFr]
   ): Future[TCPClientRef[Fr, UFr]] = {

      val props = Director.props(name, tcp, stream, writer, reader)

      val actorRef = actorSystem.actorOf(props, name)

      val ref = new TCPClientRefImpl[Fr, UFr](
         actorRef,
         actorSystem.dispatcher,
         60 seconds
      )

      ref.waitForConnection()
   }

}


class TCPClientRefImpl[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] (
   val ref: ActorRef,
   executionContext: ExecutionContextExecutor,
   timeout: FiniteDuration
)(
   implicit
   fr$tag: ClassTag[Fr],
   uf$tag: ClassTag[UFr]
) extends TCPClientRef[Fr, UFr] {

   implicit private val ec = executionContext
   implicit private val akkaTimeout = akka.util.Timeout(timeout)

   def getState (): Future[Director.State] = {
      ask(ref, Director.Command.GetState).map {
         case st: Director.State => st
         case t => throw new RuntimeException(s"Unexpected Response for 'GetState': ${t}")
      }
   }

   def waitForConnection (): Future[this.type] = {
      def loop = getState().flatMap {
         case Director.State.Connecting => getState()
         case Director.State.Connected => Future successful Director.State.Connected
         case t => throw new RuntimeException(s"Invalid State from director for 'Wait To Connect': ${t}")
      }
      loop.map(_ => this)
   }

   override def isActive (): Future[Boolean] = getState().map {
      case Director.State.Connected => true
      case _ => false
   }

   override def send (rq: UFr): Future[Fr] = {
      ask(ref, Director.Command.Send(rq)).map {
         case Director.Event.CantSend => throw new RuntimeException("Can't send message! Problem with connection")
         case Director.Event.Received(rs) if fr$tag.unapply(rs).isDefined => rs.asInstanceOf[Fr]
         case unexp => throw new RuntimeException(s"Unexpected Response for 'Send': ${unexp}")
      }
   }

   override def close (): Future[Unit] = {
      ask(ref, Director.Command.Disconnect).map {
         case unit: Unit => unit
         case unexp => throw new RuntimeException(s"Unexpected Response for 'Disconnect': ${unexp}")
      }
   }

   override def call[Rq, Rs, Er] (rq: Rq)(
      implicit
      rqKey: TypeKey[Rq],
      rsKey: TypeKey[Rs],
      erKey: TypeKey[Er],
      rqWriter: Writer[Rq, Fr, UFr],
      rsReader: Reader[Rs, Fr],
      erReader: Reader[Er, Fr]
   ): AsyncResult[Er, Rs] = {

      val rsl = this.send(rqWriter.write(rq)).map { frame =>
         if (frame.dispatchKey.typeKey == rsKey.typeKey) StdRight(rsReader.read(frame))
         else if (frame.dispatchKey.typeKey == erKey.typeKey) StdLeft(erReader.read(frame))
         else throw new RuntimeException(s"Invalid Response (Dispatch Key: ${frame.dispatchKey})")
      }

      AsyncResult.fromFuture(rsl)
   }
}