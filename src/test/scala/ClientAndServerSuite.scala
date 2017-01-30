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
import util.TextFrame

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
