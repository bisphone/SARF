package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable
import akka.actor.ActorRef
import com.bisphone.launcher.Module
import com.bisphone.std._

import scala.concurrent.duration.FiniteDuration

case class ConnectionContext(
    name: String,
    desc: String,
    id: Int,
    ref: ActorRef,
    activatedAt: Long,
    var live: Long,
    var complete: Long,
    var time: Long
    // @todo Implement Success, LogicalError, Timeout, MinTime!
)

case class RequestContext(
    caller: ActorRef,
    connection: ConnectionContext,
    trackingKey: Int,
    sentAt: Long
)

case class ResponseContext(
    caller: ActorRef,
    connection: ConnectionContext,
    trackingKey: Int,
    sentAt: Long,
    deliveredAt: Long
)

class Tracker(
    val name: String
) extends Module {

    val logger = loadLogger

    private var _count = 0
    private var requests = mutable.HashMap.empty[Int,RequestContext]
    private def now = System.currentTimeMillis()

    def live(): Int = requests.size

    def track(caller: ActorRef, conn: ConnectionContext): RequestContext = {

        _count += 1

        conn.live += 1

        val ctx = RequestContext(caller, conn, _count, now)


        requests(_count) = ctx

        ctx
    }

    def resolve(key: Int): Option[ResponseContext] = {
        requests.remove(key).map { req =>
            val c = now
            req.connection.live -= 1
            req.connection.complete += 1
            req.connection.time += c - req.sentAt
            ResponseContext(req.caller, req.connection, req.trackingKey, req.sentAt, c)
        }
    }
}


trait ConnectionBalancer extends Module {

    def all: Seq[ConnectionContext]

    def count: Int

    def pickOne: Option[ConnectionContext]
}

class RoundRobinConnectionBalancer(
    val name: String
) extends ConnectionBalancer {

    val logger = loadLogger

    private var _all = mutable.Queue.empty[ConnectionContext]

    private var _pointer = ConnectionContext

    def all = _all.toSeq

    override def count = _all.size

    def pickOne: Option[ConnectionContext] = {
        if (_all.isEmpty) None
        else {
            val ctx = _all.dequeue
            _all enqueue ctx
            Some(ctx)
        }
    }

    def add(ctx: ConnectionContext): StdTry[Unit] = {
        all.find( _ == ctx ) match {
            case None => StdSuccess { _all enqueue ctx }
            case Some(_) => StdFailure(throw new DuplicatedConnection(ctx))
        }
    }

    def remove(ref: ActorRef): Option[ConnectionContext] = {
        all.find(_.ref == ref).map { ctx =>
            _all = mutable.Queue(all.filter(_ != ctx): _*)
            ctx
        }
    }
}

case class ConnectionRef(
    id: Int,
    config: Connection.Config,
    state: Connection.State,
    retryCount: Int
)

trait ReConnectingPolicy

object ReConnectingPolicy {

    case class Retry(delay: FiniteDuration) extends ReConnectingPolicy
    case object Remove extends ReConnectingPolicy

    trait Handler {
        def handle(ref: ConnectionRef): ReConnectingPolicy
    }

    class SimpleHandler(
        override val name: String,
        retryDelay: FiniteDuration
    ) extends Handler with Module {

        override protected val logger = loadLogger

        logger info s"Init, ClassOf[${getClass.getName}]"

        private def stringOfConnection(conf: Connection.Config) = {
            s"Connection.Config(${conf.name}, ${conf.tcp.host}:${conf.tcp.port})"
        }

        override def handle (ref: ConnectionRef): ReConnectingPolicy = {
            // require(ref.state.isInstanceOf[Connection.State.Unstablished])
            logger warn s"Handle, ${stringOfConnection(ref.config)}, Retry(${retryDelay})"
            Retry(retryDelay)
        }
    }
}

class DuplicatedConnection(conn: ConnectionContext) extends Exception(s"${conn}")