package util

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.ByteString
import com.bisphone.sarf.implv1.util.{ StreamConfig, TCPConfigForServer }
import com.bisphone.sarf.{ FailureHandler, Func, IOCommand }
import com.bisphone.sarf.implv1.{ Service, TCPServer }
import com.bisphone.util.AsyncResult
import util.SayProtocol._
import com.bisphone.std._

import scala.concurrent.Future

object Server {

    def apply (name: String)(
        implicit system: ActorSystem
    ) = {

        val failureHandler = new FailureHandler {
            def apply (
                cause       : Throwable,
                bytes       : ByteString
            ): Future[IOCommand] = {
                Future successful IOCommand.Close
            }
        }

        val x = Func[SayBye] { sayBye => Error(s"Ops from '${name}'").asyncLeft }

        new Service.Builder[Tracked, Untracked](
            system.dispatcher,
            failureHandler,
            reader,
            writer,
            None // Some(stat)
            // Compiler Error for This: "serveFunc{ (sayHello: SayHello) => ... }"
        ).serveFunc[SayHello] { sayHello =>
            AsyncResult right Hello(s"'${sayHello.to}' from '${name }'")
            // AsyncResult right Bye(rq.to)
        }.serveFunc(x).result.get
    }

    def tcp(name: String, tcpConfig: TCPConfigForServer, streamConfig: StreamConfig)(
        implicit
        system: ActorSystem
    ) = {
        TCPServer(
            s"${name}-server",
            tcpConfig, streamConfig, true
        ){ apply(name) }
    }

}
