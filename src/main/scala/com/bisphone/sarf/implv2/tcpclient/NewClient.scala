package com.bisphone.sarf.implv2.tcpclient

import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import com.bisphone.launcher.Module
import com.bisphone.sarf.implv2.tcpclient.NewClient.FnState
import com.bisphone.sarf._
import com.bisphone.std.{ StdLeft, StdRight, Try }
import com.bisphone.util.{ AsyncResult, ReliabilityState }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, ExecutionContextExecutor, Future, Promise }
import scala.reflect.ClassTag
import akka.pattern.ask
import com.typesafe.scalalogging.Logger

object NewClient {

    class FnState(
        val onReadyness: Promise[ActorRef],
        val onShutdown: Promise[Unit],
        val logger: Logger
    ) extends (NewDirector.Interface => Unit) {

        var lastState: ReliabilityState = null

        override def apply (ref: NewDirector.Interface): Unit = {

            logger.debug(s"FnState.Change, newState: ${ref.state}, lastState: ${lastState}")

            if (lastState == null && (ref.state.isGreen || ref.state.isYello)) {
                onReadyness.trySuccess(ref.proxy)
            }

            if (ref.state.isShutdown) {
                // last-call
                onShutdown.trySuccess()
            }

            lastState = ref.state
        }
    }

    def apply[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        config: NewDirector.Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        actorSystem: ActorSystem,
        executionContext: ExecutionContextExecutor
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): NewClient[T, U] = {
        new NewClient(name, config, writer, reader, actorSystem, executionContext)
    }
}

class NewClient[T <: TrackedFrame, U <: UntrackedFrame[T]](
    val name: String,
    config: NewDirector.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    actorSystem: ActorSystem,
    implicit val executionContext: ExecutionContextExecutor
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends com.bisphone.sarf.TCPClientRef[T, U] with Module { self =>

    protected val logger = loadLogger

    protected val onReadyness = Promise[ActorRef]()
    protected val onShutdown = Promise[Unit]()

    protected val directorName = s"${name}.new-director"

    private val fnState = new FnState(onReadyness, onShutdown, logger)

    protected val directorProps = NewDirector.props(directorName, config, writer, reader, fnState)

    protected val director = actorSystem actorOf (directorProps, name)

    logger.debug(s"Init, Waiting for Proxy ...")

    protected val proxy = Await.result(fnState.onReadyness.future, config.initTimeout)

    logger.debug(s"Init, Proxy: ${proxy}")

    protected def now = System.currentTimeMillis()

    override def isActive() = Future successful true

    override def send(rq: U) = {

        ask(proxy, Proxy.Send[U](rq, now))(config.requestTimeout).map {

            // case Director.Event.CantSend => throw new RuntimeException("Can't send message! Problem with connection")
            case Proxy.Recieved(rs: T) => rs
            case unexp => throw new RuntimeException(s"Unexpected Response for 'Send': ${unexp}")
        }.recover {
            case cause: java.util.concurrent.TimeoutException =>
                logger error (s"Send, Timeout, Request: ${rq.dispatchKey}, Director: ${director}, Proxy: ${proxy}", cause)
                /*Try(Await.result(getState.map { st =>
                    logger info s"Send, Timeout, GetState: ${st}, Director: ${ref}"
                }, 60 seconds))*/
                throw cause
        }
    }

    override def close() = {
        director ! PoisonPill
        Future successful (())
    }

    override def call[Rq, Rs, Er](rq: Rq)
        (
            implicit rqKey: TypeKey[Rq],
            rsKey: TypeKey[Rs],
            erKey: TypeKey[Er],
            rqWriter: Writer[Rq, T, U],
            rsReader: Reader[Rs, T],
            erReader: Reader[Er, T]
        ) = {

        val rsl = this.send(rqWriter.write(rq)).map { frame =>
            if (frame.dispatchKey.typeKey == rsKey.typeKey) {
                val response = rsReader.read(frame)
                // if (logger.()) logger trace s"Call, Success, ${rq} => ${response}"
                StdRight(response)
            }
            else if (frame.dispatchKey.typeKey == erKey.typeKey) {
                val error = erReader.read(frame)
                // if (logger.isTraceEnabled()) logger trace s"Call, Error, ${rq} => ${error}"
                StdLeft(error)
            }
            else throw new RuntimeException(s"Invalid Response (Dispatch Key: ${frame.dispatchKey})")
        }

        AsyncResult.fromFuture(rsl)
    }

    def healthcheck(desc: String = "How Are You", timeout: FiniteDuration = config.requestTimeout) = Try {
        Await.result(
            ask(proxy, Proxy.HealthCheck(desc))(timeout),
            timeout
        )
    }

    // private val onShutdown = fnState.onShutdown.future.map { _ => self }
    def whenShutdown = onShutdown

}
