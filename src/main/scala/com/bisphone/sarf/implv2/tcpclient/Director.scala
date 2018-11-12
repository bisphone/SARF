package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect.ClassTag
import scala.util.Try
import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.stream.ActorMaterializer
import com.bisphone.launcher.Module
import com.bisphone.sarf.{ FrameReader, FrameWriter, TrackedFrame, UntrackedFrame }
import com.bisphone.std._

object Director {

    case class Config(
        connections: Seq[Connection.Config],
        minumumAdorableConnections: Int,
        maximumTroubleTime: FiniteDuration,
        maxRetry: Int,
        retryDelay: FiniteDuration,
        initTimeout: FiniteDuration,
        requestTimeout: FiniteDuration
    )


    case class RetryRef(
        config: Connection.Config,
        retryCount: Int
    )

    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        config: Director.Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        fnReady: StdTry[ActorRef] => Unit,
        fnUnready: Unit => Unit,
        reConnectingPolicy: ReConnectingPolicy.Handler
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): Props = Props { new Director(name, config, writer, reader, fnReady, fnUnready, reConnectingPolicy) }

}

class Director[T <: TrackedFrame, U <: UntrackedFrame[T]](
    val name: String,
    config: Director.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    fnReady: StdTry[ActorRef] => Unit,
    fnUnready: Unit => Unit,
    reConnectingPolicy: ReConnectingPolicy.Handler
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends Actor with Module {

    override protected def logger = loadLogger

    val proxyName = s"${name}.proxy"
    val proxyProps = Proxy.props(proxyName, self, writer)
    val proxy = context actorOf (proxyProps, proxyName)

    var _count = 0
    def count() = {
        _count += 1
        _count
    }

    var _established = 0

    var _calledFnReady = false

    val materializer = ActorMaterializer()

    val all = mutable.HashMap.empty[ActorRef, ConnectionRef]

    def newConnection(cfg: Connection.Config, retryCount: Int) = {
        logger debug s"Trying, RetryCount: ${retryCount}, ${stringOfConf(cfg)}"
        val id = count
        val props = Connection.props(self, proxy, id, cfg, writer, reader, materializer, context.dispatcher)
        val actor = context actorOf props
        context watch actor
        val state = Connection.State.Trying(id, actor, cfg, System.currentTimeMillis())
        all(actor) = ConnectionRef(id, cfg, state, retryCount)
    }

    def removeConnection(ref: ActorRef) = {
        all remove ref get
    }

    def renewConnection(ref: ActorRef) = {
        val tmp = (all remove ref).get
        val retryCount = tmp.state match {
            case _: Connection.State.Trying => tmp.retryCount + 1
            case _ => 0
        }
        newConnection(tmp.config, retryCount)
    }

    def scheduleForRenewing(ref: ActorRef) = {

        val tmp = removeConnection(ref)

        reConnectingPolicy handle tmp match {
            case ReConnectingPolicy.Retry(delay) =>

                logger debug s"Schedule for Retry Unstablished Connection, ${stringOfRef(tmp)}"

                val retryCount = tmp.state match {
                    case _: Connection.State.Trying => tmp.retryCount + 1
                    case _ => 0
                }

                context.system.scheduler.scheduleOnce(
                    delay, self,
                    Director.RetryRef(tmp.config, retryCount)
                )(context.dispatcher)

            case ReConnectingPolicy.Remove =>
                logger info s"Removed Unstablished Connection, ${stringOfRef(tmp)}"
        }
    }

    def setEstablishedConnection(ref: ActorRef, state: Connection.State.Established) = {
        val newVal = all(ref).copy(retryCount = 0, state = state)
        all(ref) = newVal
        newVal
    }

    def stringOfRef(conn: ConnectionRef) = {
        s"ID: ${conn.id}, Name: ${conn.config.name}, Host: ${conn.config.tcp.host}, Port: ${conn.config.tcp.port}"
    }

    def stringOfSt(conn: Connection.State.Established) = {
        s"ID: ${conn.id}, Name: ${conn.config.name}, Host: ${conn.config.tcp.host}, Port: ${conn.config.tcp.port}"
    }

    def stringOfConf(conf: Connection.Config) = {
        s"Name: ${conf.name}, Host: ${conf.tcp.host}, Port: ${conf.tcp.port}"
    }

    override def preStart: Unit = {
        config.connections.foreach(newConnection(_, 0))
    }

    def normal: Receive = {

        case Terminated(ref) if all(ref).state.isInstanceOf[Connection.State.Established] =>

            val conn = all(ref)
            _established -= 1
            logger warn s"Terminated an Established Connection, ${stringOfRef(conn)}, Total Established: ${_established}"

            scheduleForRenewing(ref)

        case Terminated(ref) if all contains ref =>
            val conn = all(ref)
            logger debug s"Terminated an Unestablished Connection, ${stringOfRef(conn)}"
            scheduleForRenewing(ref)

        case st: Connection.State.Established =>
            _established += 1
            logger debug s"New Established Connection, ${stringOfSt(st)}, Total Established: ${_established}"

            val tmp = setEstablishedConnection(sender, st)
            proxy ! Proxy.NewConnection(
                tmp.id, tmp.config.name,
                s"${tmp.config.tcp.host}:${tmp.config.tcp.port}",
                tmp.state.ref
            )

            if (_established >= config.minumumAdorableConnections && !_calledFnReady) {
                _calledFnReady = true
                logger info s"Get Ready!"
                fnReady(StdSuccess(proxy))
            }

        case Director.RetryRef(cfg, retryCount) =>
            logger debug s"Retrying, RetryCount: ${retryCount}, Config: ${config}"
            newConnection(cfg, retryCount)
    }

    override def receive: Receive = normal
}
