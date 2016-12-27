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
      implicit val javaValueForByteOrder = order.javaValue

      case class Tracked (
         override val dispatchKey: TypeKey[_],
         val content: ByteString,
         override val trackingKey: Int
      ) extends TrackedFrame {
         override val bytes: ByteString =
            ByteString.newBuilder
               .putInt(dispatchKey.typeKey)
               .putInt(trackingKey)
               .append(content)
               .result()
      }

      case class Untracked (
         override val dispatchKey: TypeKey[_],
         bytes: ByteString
      ) extends UntrackedFrame[Tracked]

      def writer = new FrameWriter[Tracked, Untracked] {
         def writeFrame (uf: Untracked, tk: Int): Tracked = {
            Tracked(uf.dispatchKey, uf.bytes, tk)
         }
      }

      val reader = new FrameReader[Tracked] {
         def readFrame (bytes: ByteString): Tracked = {
            val iter = bytes.iterator
            val key = order.decodeInt(iter, 4)
            val tk = order.decodeInt(iter, 4)
            val content = ByteString(iter.toArray)
            Tracked(TypeKey(key), content, tk)
         }
      }

      implicit val stringKey = TypeKey[String](0)

      implicit val stringWriter = new Writer[String, Tracked, Untracked] {
         def write (str: String): Untracked = Untracked(stringKey, ByteString(str))
      }

      implicit val stringReader = new Reader[String, Tracked] {
         override def read (frm: Tracked): String = new String(frm.content.toArray)
      }

      implicit val intKey = TypeKey[Int](1)

      implicit val intWriter = new Writer[Int, Tracked, Untracked] {
         override def write (int: Int): Untracked =
            Untracked(intKey, ByteString.newBuilder.putInt(int).result())
      }

      implicit val intReader = new Reader[Int, Tracked] {
         override def read (frm: Tracked): Int = order.decodeInt(frm.content.iterator, 4)
      }

      val failureHandler = new FailureHandler {
         def apply (cause: Throwable, bytes: ByteString): Future[IOCommand] = {
            val frame = reader.readFrame(bytes)
            val utr = Untracked(intKey, ByteString.newBuilder.putInt(-1).result())
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
      ).serve[String, String, Int]((str: String) => {
         val rsl = str.toLowerCase match {
            case "bye" => 1.stdleft
            case x => s">> ${str}".stdright
         }
         AsyncResult.fromEither(rsl)
      }).result.get
   }

   val res = for {
      server <-
      TCPServer(
         "echosrv",
         tcpServer, stream, true
      )(service)
      client <-
      TCPClient[TextFrame.Tracked, TextFrame.Untracked](
         "echochat",
         tcpClient,
         stream,
         TextFrame.writer,
         TextFrame.reader
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

      whenReady(client.call[String, String, Int]("Hello").asFuture, Timeout(Span(1, Second))) {
         case StdLeft(err) => fail(s"Invalid response: error: ${err}")
         case StdRight(rs) => rs should equal(">> Hello")
      }


   }

}
