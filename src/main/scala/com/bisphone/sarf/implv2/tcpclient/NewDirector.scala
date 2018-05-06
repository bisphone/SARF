package com.bisphone.sarf.implv2.tcpclient

// import com.bisphone.sarf.implv2.tcpclient
import scala.collection.mutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect.ClassTag
import akka.actor._
import com.bisphone.launcher._
import com.bisphone.sarf._
import com.bisphone.std._
import akka.stream.ActorMaterializer
import com.bisphone.util.ReliabilityState

object NewDirector {

    case class Config(
        connections: Seq[Connection.Config],
        greenStateLimit: Int,
        endureRedState: Duration,
        initTimeout: FiniteDuration,
        requestTimeout: FiniteDuration,
        reconnectingDelay: FiniteDuration
    )

    case object GetState


    case class Renew(ref: ConnectionRef)

    trait Interface {
        def actor: ActorRef
        def proxy: ActorRef
        def state: ReliabilityState
        def connections: Seq[ConnectionRef]
    }

    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        config: Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        fnState: NewDirector.Interface => Unit
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): Props = Props { new NewDirector(name, config, writer, reader, fnState) }

}

class NewDirector[T <: TrackedFrame, U <: UntrackedFrame[T]](
    override val name: String,
    config: NewDirector.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    fnState: NewDirector.Interface => Unit
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends Actor with Module with NewDirector.Interface {

    override protected def logger = loadLogger

    private def mkProxy = {
        val proxyName = s"${name}.proxy"
        val proxyProps = Proxy.props(proxyName, self, writer)
        context actorOf (proxyProps, proxyName)
    }

    def actor = self

    override val proxy = mkProxy

    private val materializer = ActorMaterializer()

    private var _establisheds = 0
    protected def establisheds() = _establisheds
    protected def plusEstablished() = { _establisheds += 1; this }
    protected def minusEstablished() = { _establisheds -= 1; this }

    private var _count = 0
    private def count() = {
        _count += 1
        _count
    }


    private var _state: ReliabilityState = ReliabilityState(System.currentTimeMillis())
    def state() = _state

    protected def updateState(): ReliabilityState = {

        import ReliabilityState._
        val now = System.currentTimeMillis()

        val newState = establisheds() match {
            case 0 => Red.from(now, state())
            case all if all < config.greenStateLimit => Yellow.from(now, state())
            case _ => Green.from(now, state())
        }

        val finalState = checkState(newState)

        val noChange = state() sameAs finalState

        logger.debug(s"UpdateState, New: ${finalState}, Last: ${_state}, Changed: ${!noChange}")

        _state = finalState
        if (!noChange) fnState(this)

        state()
    }

    protected def checkState(state: ReliabilityState): ReliabilityState = {

        import ReliabilityState._

        val newState = config.endureRedState match {
            case inf:Duration.Infinite => state // Ignore !
            case dur:FiniteDuration =>
                val now = System.currentTimeMillis()
                val time = dur.toMillis

                state match {
                    case red: Red[_] if red.since + time < now => state // Ignore !
                    case red: Red[_] => red.shutdown(now)
                }
        }

        newState
    }

    override def preStart: Unit = {
        config.connections.foreach { i => connect(i) }
    }

    def normal: Receive = {
        case Terminated(ref) if all(ref).state.isInstanceOf[Connection.State.Established] =>

            val conn = all(ref)
            minusEstablished()
            logger warn s"Disconnected, ${stringOfRef(conn)}, ${stringOfState}"

            if (updateState().isShutdown) shutdown() else tryAgain(conn)

        case Terminated(ref) if all contains ref =>

            val conn = all(ref)
            logger warn s"Unstablished, ${stringOfRef(conn)}, ${stringOfState}"

            if (updateState().isShutdown) shutdown() else tryAgain(conn)

        case st: Connection.State.Established =>

            plusEstablished()
            logger info s"Connected, ${stringOfSt(st)}, ${stringOfState}"

            val tmp = add(sender, st)
            proxy ! Proxy.NewConnection(
                tmp.id, tmp.config.name,
                s"${tmp.config.tcp.host}:${tmp.config.tcp.port}",
                tmp.state.ref
            )

            updateState()

        case NewDirector.Renew(ref) => connect(ref.config)

        case NewDirector.GetState =>
            if (updateState().isShutdown) shutdown()
            sender ! state()
    }

    override def receive: Receive = normal

    val all = mutable.HashMap.empty[ActorRef, ConnectionRef]

    override def connections(): Seq[ConnectionRef] = all.values.toSeq

    protected def connect(cfg: Connection.Config) = {

        logger debug s"Connecting, ${stringOfConf(cfg)}, ${stringOfState}"

        val id = count
        val props = Connection.props(self, proxy, id, cfg, writer, reader, materializer, context.dispatcher)
        val actor = context actorOf props
        context watch actor
        val state = Connection.State.Trying(id, actor, cfg, System.currentTimeMillis())
        all(actor) = ConnectionRef(id, cfg, state, retryCount = -1)
        this
    }

    def add(ref: ActorRef, state: Connection.State.Established) = {
        val newVal = all(ref).copy(retryCount = 0, state = state)
        all(ref) = newVal
        newVal
    }

    protected def tryAgain(ref: ConnectionRef): Unit = {
        context.system.scheduler.scheduleOnce(
            config.reconnectingDelay, self, NewDirector.Renew(ref)
        )(context.dispatcher)
    }

    protected def remove(ref: ActorRef) = {
        all.remove(ref).get
        this
    }

    protected def renew(ref: ActorRef) = {
        val tmp = (all remove ref).get
        connect(tmp.config)
    }

    protected def stringOfRef(conn: ConnectionRef) = {
        s"ID: ${conn.id}, Name: ${conn.config.name}, Host: ${conn.config.tcp.host}, Port: ${conn.config.tcp.port}"
    }

    protected def stringOfSt(conn: Connection.State.Established) = {
        s"ID: ${conn.id}, Name: ${conn.config.name}, Host: ${conn.config.tcp.host}, Port: ${conn.config.tcp.port}"
    }

    protected def stringOfConf(conf: Connection.Config) = {
        s"Name: ${conf.name}, Host: ${conf.tcp.host}, Port: ${conf.tcp.port}"
    }

    protected def stringOfState = s"All: ${establisheds()}, State: ${state()}"

    protected def shutdown(): Unit = {
        logger warn s"Shutdown, ${stringOfState}"
        context stop self
    }

}