in the name of ALLAH

SARF: Simple Abstraction for Remote Function
----

An easy to use!, protocol agnostic (mostly), non-blocking,
and back-pressured RPC-toolkit based on Akka streams and actors.

### Abstractions for Defining a Protocol:

- Types and Functions:‌
every function will be defined with 3 type: Request/Input, Result/Output or Error!
```
case class Get(key: String)            // Request
case class Value(value: String)        // Result
case class Error(message: String)      // Error
```
And the function:
```
Get => AsyncResult[Error, Value]
```
Note: AsyncResult is a wrapper around Future[Either[L,R]] that is defined
in 'bisphone-std' project; something like a monad transformer!
We should! connect the function(Request/Input type) to its
output/error type in a way. In the '0.7.0' version we will
change 'Get' type like this:
 ```
 case class Get(key: String) extends com.bisphone.sarf.Func {
    override type Error = Error
    override type Result = Value
 }
 ```
 Note: In the near future this will be changed!

- TypeKey:
In SARF every type should has a specific code that represent it;
type key is the abstraction that will do this for us.

```
    implicit val typeKeyForGet = TypeKey[Get](1)

    implicit val typeKeyForValue = TypeKey[Value](2)

    implicit val typeKeyForError = TypeKey[Error](3)
```

- Writer & Reader :
For serializing every type we need a writer & reader per type:
```
    def writer[T](fn: T => Untracked) = new Writer[T, Tracked, Untracked] {
        override def write(t: T) = fn(t)
    }

    implicit val writerForGet = writer[Get] { get =>
        Untracked(typeKeyForGet, akka.util.ByteString(get.key))
    }

    implicit val writerForValue = writer[Value] { value =>
        Untracked(typeKeyForValue, akka.util.ByteString(value.value))
    }

    implicit val writerForError = writer[Error] { error =>
        Untracked(typeKeyForError, akka.util.ByteString(error.message))
    }
```
And readers:
```
    def reader[T](fn: Tracked => T) = new Reader[T, Tracked] {
        override def read(f: Tracked) = fn(f)
    }

    implicit val readerForGet = reader[Get] { frame => =>
        Get(new String(frame.connect.toArray))
    }

    implicit val readerForValue = reader[Value] { frame => =>
        Value(new String(frame.connect.toArray))
    }

    implicit val readerForError = reader[Error] { frame => =>
        Error(new String(frame.connect.toArray))
    }

```

- Frames: Untracked & Tracked
OK, but what's Untracked and Tracked types in the above code?
The Untracked type instance contains the (**posiibley) serialized object with
a value of typeKey that refer to that type. Seems that it has required info for
sending the request over network! Yeh? NO. We need another value to track our
request between client and server!
SARF handle lot of queries with a single
connection and for this reason we must be able to track our requests and their
responses! SARF will generate, manage, and add a value (trackingKey) to our packet
to make it trackable (Tracked frame). Also Tracked type has a 'bytes' filed that
will return a ByteString to write on wire.

- FrameWriter:
    To converting the Untracked instance to a Tracked one and writing it on wire
    SARF needs a function to do it: FrameWriter:
    ```
    trait FrameWriter[Fr <: TrackedFrame, UFr <: UntrackedFrame[Fr]] {
       def writeFrame (uf: UFr, trackingKey: Int): Fr
    }
    ```

- FrameReader:
    And how SARF will convert the serialized bytes to a Tracked type:
    ```
    trait FrameReader[Fr <: TrackedFrame] {
       def readFrame (bytes: ByteString): Fr
    }
    ```
    This abstraction will convert a ByteString to a Tracked frame and let other
    client and server work with reader & writers.

### How to USE:

After defining a protocl and its reader/writer instances you are able to write server and use clients to connect:

    ```
        // All typeKeys, readers & writers are available from this scope

        // Server
        val builder: Service.Builder[Tracked, Untracked] =
            new Service.Builder[Tracked, Untracked](
                executionContext,
                failureHandler,
                frameReader,
                frameWriter,
                None
            )
        builder.serveFunc[Get] { get =>
            AsyncResult left Error("Undefined Key: ${get.key}")
        }
        TCPServer(name,tcpConfig, streamConfig, true)(builder.result.get)

        // Client
        val client: TCPClientRef = ???

        client(Get("applicatin.cache")).map(_.value).asFuture{
            case Left(Error(message)) => println(s"Error: ${message}")
            case Right(value) => pritln(s"Value: ${value}")
        }

    ```

for a complete example check the 'SayHelloSuite' in 'test'.

Depencencies
----

 - [bisphone-testkit](https://github.com/reza-samei/bisphone-testkit)
 - [bisphone-std](https://github.com/reza-samei/bisphone-std)
 - [bisphone-akkastreams](https://github.com/reza-samei/bisphone-akkastreams)

Contribution
-----

Comments, contributions and patches are greatly appreciated.

License
-----
The MIT License (MIT).