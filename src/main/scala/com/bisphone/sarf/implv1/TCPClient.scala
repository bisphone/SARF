package com.bisphone.sarf.implv1

import akka.actor.{ ActorRef, ActorSystem }
import akka.util.ByteString
import com.bisphone.sarf.implv1.tcpclient.Director
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.util.{ StreamConfig, TCPConfigForClient }
import com.bisphone.util._
import com.bisphone.std._
import akka.pattern.ask
import org.slf4j.LoggerFactory

import scala.reflect.{ ClassTag, classTag }
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
            10 seconds
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
    private val logger = LoggerFactory getLogger classOf[TCPClientRefImpl[Fr, UFr]]

    def getState (): Future[Director.State] = {
        if (logger.isTraceEnabled()) logger trace s"GetState"
        ask(ref, Director.Command.GetState).map {
            case st: Director.State =>
                if (logger.isTraceEnabled()) logger trace s"GetState, State: ${st}"
                st
            case t => throw new RuntimeException(s"Unexpected Response for 'GetState': ${t}")
        }
    }

    def waitForConnection (): Future[this.type] = {
        if (logger.isDebugEnabled()) logger debug s"WaitForConnection, Director: ${ref}"
        def loop = getState().flatMap {
            case Director.State.Connecting => getState()
            case Director.State.Connected =>
                if (logger.isDebugEnabled()) logger debug s"WaitForConnection, Connected"
                Future successful Director.State.Connected
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
        }.recover {
            case cause: java.util.concurrent.TimeoutException =>
                logger error (s"Send, Timeout, Request: ${rq.dispatchKey}, Director: ${ref}", cause)
                Try(Await.result(getState.map { st =>
                    logger info s"Send, Timeout, GetState: ${st}, Director: ${ref}"
                }, 60 seconds))
                throw cause
        }
    }


    override def close (): Future[Unit] = {
        if (logger.isInfoEnabled()) logger info s"Closing, ..."
        ask(ref, Director.Command.Disconnect).map {
            case unit: Unit =>
                if (logger.isInfoEnabled()) logger info "Closed"
                unit
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
            if (frame.dispatchKey.typeKey == rsKey.typeKey) {
                val response = rsReader.read(frame)
                if (logger.isTraceEnabled()) logger trace s"Call, Success, ${rq} => ${response}"
                StdRight(response)
            }
            else if (frame.dispatchKey.typeKey == erKey.typeKey) {
                val error = erReader.read(frame)
                if (logger.isTraceEnabled()) logger trace s"Call, Error, ${rq} => ${error}"
                StdLeft(error)
            }
            else throw new RuntimeException(s"Invalid Response (Dispatch Key: ${frame.dispatchKey})")
        }

        AsyncResult.fromFuture(rsl)
    }
}