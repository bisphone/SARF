import java.io.{InputStream, OutputStream}
import java.net.Socket

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import com.bisphone.sarf.implv1.{Service, StatCollector, TCPClient, TCPServer}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient, TCPConfigForServer}
import com.bisphone.sarf._
import com.bisphone.util.ByteOrder
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.PatienceConfiguration._
import org.scalatest.time._
import com.bisphone.std._
import com.bisphone.util._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class ClientAndServerSuite
  extends TestKit(ActorSystem())
    with FlatSpecLike
    with Matchers
    with BeforeAndAfter
    with ScalaFutures {

  object TextFrame {

    val order = ByteOrder.BigEndian

    private val dispatchValue = 0

    implicit val dispatchKey = TypeKey[String](dispatchValue)

    case class Tracked(
      override val bytes: ByteString,
      override val trackingKey: Int
    ) extends TrackedFrame {
      override val dispatchKey = TextFrame.dispatchKey
      def content = bytes.drop(4)
    }
    case class Untracked(str: String) extends UntrackedFrame[Tracked] {
      override val dispatchKey = TextFrame.dispatchKey
    }

    def writer = new FrameWriter[Tracked, Untracked] {
      def writeFrame(uf: Untracked, tk: Int): Tracked = {
        val bytes = ByteString.newBuilder
          .putInt(tk)(order.javaValue)
          .append(ByteString(uf.str))
          .result
        Tracked(bytes, tk)
      }
    }

    val reader = new FrameReader[Tracked] {
      def readFrame(bytes: ByteString): Tracked = {
        val iter = bytes.iterator
        val tk = order.decodeInt(iter, Constant.lenOfLenField)
        Tracked(bytes, tk)
      }
    }

    implicit val stringWriter = new Writer[String, Tracked, Untracked] {
      def write(str: String): Untracked = Untracked(str)
    }

    implicit val stringReader = new Reader[String, Tracked] {
      override def read(frm: Tracked): String = new String(frm.content.toArray)
    }

    val  failureHandler = new FailureHandler {
      def apply (cause: Throwable, bytes: ByteString): Future[IOCommand] = {
        val frame = reader.readFrame(bytes)
        val utr = Untracked(s"Failure - Cause: ${cause.getMessage}")
        val cmd = IOCommand.Send(writer.writeFrame(utr, frame.trackingKey).bytes)
        Future successful cmd
      }
    }

    implicit val statTag = StatTag[String]("String")
  }

  implicit val ec = ExecutionContext.Implicits.global

  val tcpServer = TCPConfigForServer("localhost", 2424, 100)

  val tcpClient = TCPConfigForClient("localhost", 2424, 10 seconds)

  val stream = StreamConfig(2000, TextFrame.order, 10)

  val stat = StatCollector("server-stat", StatCollector.Config(1 minute, 3), LoggerFactory.getLogger("server-stat"))(system)

  val service = {
    import TextFrame._
    new Service.Builder[TextFrame.Tracked, TextFrame.Untracked](
      system.dispatcher,
      TextFrame.failureHandler,
      TextFrame.reader,
      TextFrame.writer,
      Some(stat)
    ).serve[String,String,String]( (str: String) => {
      val rsl = s">> ${str}".stdright[String]
      AsyncResult.fromEither(rsl)
    }).result.get
  }

  val res = for {
    server <-
      TCPServer(
        "echochat-server",
        tcpServer, stream, true
      )(service)
    client <-
      TCPClient[TextFrame.Tracked, TextFrame.Untracked](
        "echochat-client",
        tcpClient,
        stream,
        TextFrame.writer,
        TextFrame.reader,
        true
      )
  } yield (server, client)


  val (server, client) = try Await.result(res, 1 minute) catch {
    case NonFatal(cause) => info(s"Error: ${cause}")
      cause.printStackTrace()
      fail("Can't prepare resources")
  }

  info("Going to run tests ...")

  "EchoChat" must "send hell and retrieve it" in {

    import TextFrame._

    whenReady(client.call[String, String, String]("Hello").asFuture, Timeout(Span(1, Second))) {
      case StdLeft(err) => fail(s"Invalid response: error: ${err}")
      case StdRight(rs) => rs should equal (">> Hello")
    }


  }

}
