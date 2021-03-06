package com.bisphone.sarf.implv2.tcpclient

import scala.concurrent.{ Await, ExecutionContextExecutor, Future, Promise }
import scala.reflect.ClassTag
import akka.actor.{ ActorRef, ActorSystem, PoisonPill }
import com.bisphone.launcher.Module
import com.bisphone.sarf._
import com.bisphone.std._
import akka.pattern.ask
import com.bisphone.sarf.implv2.tcpclient
import com.bisphone.util.AsyncResult

import scala.concurrent.duration.FiniteDuration

object TCPClient {
    def apply [T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        config: Director.Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        actorSystem: ActorSystem,
        executionContext: ExecutionContextExecutor
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): TCPClient[T, U] = {
        new TCPClient(name, config, writer, reader, actorSystem, executionContext)
    }

}

class TCPClient[T <: TrackedFrame, U <: UntrackedFrame[T]] private (
    val name: String,
    config: Director.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    actorSystem: ActorSystem,
    implicit val executionContext: ExecutionContextExecutor
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends com.bisphone.sarf.TCPClientRef[T, U] with Module {

    protected val logger = loadLogger

    protected val readyness = Promise[ActorRef]()
    protected val directorName = s"${name}.director"

    protected val directorProps = Director.props(
        directorName, config, writer, reader, {
            case StdSuccess(ref) =>
                readyness success (ref)
            case StdFailure(cause) =>
                readyness failure cause
        }, { _ => },
        new tcpclient.ReConnectingPolicy.SimpleHandler(s"${name}.reconnecting-policy", config.retryDelay)
    )
    protected val director = actorSystem actorOf (directorProps, name)

    protected val proxy = Await.result(readyness.future, config.initTimeout)

    protected def now = System.currentTimeMillis()

    override def isActive() = Future successful true

    override def send(rq: U) = {

        // ask(proxy, Proxy.Send(rq, now)).map {
        /*
        [error] /home/reza/workspace/SARF/src/main/scala/com/bisphone/sarf/implv2/tcpclient/Proxy.scala:43:32: pattern type is incompatible with expected type;
        [error]  found   : U
        [error]  required: com.bisphone.sarf.UntrackedFrame[com.bisphone.sarf.TrackedFrame]
        [error] Note: T <: com.bisphone.sarf.TrackedFrame (and U <: com.bisphone.sarf.UntrackedFrame[T]), but trait UntrackedFrame is invariant in type Fr.
        [error] You may wish to define Fr as +Fr instead. (SLS 4.5)
        [error]         case Proxy.Send(frame: U, time) =>
         */

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
}
