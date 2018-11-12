package util

import com.bisphone.util.AsyncResult
import com.bisphone.std._
import org.scalatest.{ FlatSpecLike, Matchers }
import org.scalatest.concurrent.ScalaFutures

trait BaseSuite extends FlatSpecLike with Matchers with ScalaFutures {

    def unexp[T](value: T) = fail(s"Unexpected Response: ${value}")

    def unexp[T](subject: String, value: T) = fail(s"Unexpected Response(${subject}): ${value}")

    def onRightRsl[I,O](asyncResult: AsyncResult[_, I])(fn: I => O) =
        whenReady(asyncResult.asFuture) {
            case StdRight(value) => fn(value)
            case x => unexp(x)
        }

    def onLeftRsl[I,O](asyncResult: AsyncResult[I,_])(fn: I => O) =
        whenReady(asyncResult.asFuture) {
            case StdLeft(value) => fn(value)
            case x => unexp(x)
        }

    implicit class AsyncResultTestOps[L,R](val self: AsyncResult[L,R]) /*extends AnyVal*/ {

        def onRight[T](fn: R => T) = onRightRsl(self)(fn)

        def onLeft[T](fn: L => T) = onLeftRsl(self)(fn)

    }

    val unit = ()

}

