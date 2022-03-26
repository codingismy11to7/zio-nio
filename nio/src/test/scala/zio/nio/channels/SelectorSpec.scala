package zio.nio.channels

import zio.nio.channels.SelectionKey.Operation
import zio.nio.{BaseSpec, Buffer, ByteBuffer, SocketAddress}
import zio.test.Assertion._
import zio.test.{Live, TestClock, TestConsole, TestRandom, TestSystem, live, _}
import zio.{Clock, Random, durationInt, _}

import java.io.IOException
import java.nio.channels.CancelledKeyException

object SelectorSpec extends BaseSpec {

  override def spec: Spec[
    Annotations with Live with Sized with TestClock with TestConfig with TestConsole with TestRandom with TestSystem with Clock with zio.Console with zio.System with Random,
    TestFailure[Any],
    TestSuccess
  ] =
    suite("SelectorSpec")(
      test("read/write") {
        for {
          started     <- Promise.make[Nothing, SocketAddress]
          serverFiber <- ZIO.scoped(server(started)).fork
          addr        <- started.await
          clientFiber <- client(addr).fork
          _           <- serverFiber.join
          message     <- clientFiber.join
        } yield assert(message)(equalTo("Hello world"))
      },
      test("select is interruptible") {
        live {
          ZIO.scoped {
            Selector.open.flatMap { selector =>
              for {
                fiber <- selector.select.fork
                _     <- ZIO.sleep(500.milliseconds)
                exit  <- fiber.interrupt
              } yield assert(exit)(isInterrupted)
            }
          }
        }
      }
    )

  def byteArrayToString(array: Array[Byte]): String = array.takeWhile(_ != 10).map(_.toChar).mkString.trim

  def safeStatusCheck(statusCheck: IO[CancelledKeyException, Boolean])(implicit
    trace: ZTraceElement
  ): IO[Nothing, Boolean] =
    statusCheck.fold(_ => false, identity)

  def server(
    started: Promise[Nothing, SocketAddress]
  )(implicit trace: ZTraceElement): ZIO[Scope with Clock, Exception, Unit] = {
    def serverLoop(scope: Scope, selector: Selector, buffer: ByteBuffer)(implicit
      trace: ZTraceElement
    ): ZIO[Any, Exception, Unit] =
      for {
        _ <- selector.select
        _ <- selector.foreachSelectedKey { key =>
               key.matchChannel { readyOps =>
                 {
                   case channel: ServerSocketChannel if readyOps(Operation.Accept) =>
                     for {
                       maybeClient <- scope.extend(channel.useNonBlockingManaged(_.accept))
                       _ <- IO.whenCase(maybeClient) { case Some(client) =>
                              client.configureBlocking(false) *> client.register(selector, Set(Operation.Read))
                            }
                     } yield ()
                   case channel: SocketChannel if readyOps(Operation.Read) =>
                     channel.useNonBlocking { client =>
                       for {
                         _ <- client.read(buffer)
                         _ <- buffer.flip
                         _ <- client.write(buffer)
                         _ <- buffer.clear
                         _ <- channel.close
                       } yield ()
                     }
                 }
               }
                 .as(true)
             }
        _ <- selector.selectedKeys.filterOrDieMessage(_.isEmpty)("Selected key set should be empty")
      } yield ()

    for {
      scope    <- Scope.make
      selector <- Selector.open
      channel  <- ServerSocketChannel.open
      _ <- for {
             _      <- channel.bindAuto()
             _      <- channel.configureBlocking(false)
             _      <- channel.register(selector, Set(Operation.Accept))
             buffer <- Buffer.byte(256)
             addr   <- channel.localAddress
             _      <- started.succeed(addr)

             /*
              *  we need to run the server loop twice:
              *  1. to accept the client request
              *  2. to read from the client channel
              */
             _ <- serverLoop(scope, selector, buffer).repeat(Schedule.once)
           } yield ()
    } yield ()
  }

  def client(address: SocketAddress)(implicit trace: ZTraceElement): IO[IOException, String] = {
    val bytes = Chunk.fromArray("Hello world".getBytes)
    for {
      buffer <- Buffer.byte(bytes)
      text <- useNioBlockingOps(SocketChannel.open(address)) { client =>
                for {
                  _     <- client.write(buffer)
                  _     <- buffer.clear
                  _     <- client.read(buffer)
                  array <- buffer.array
                  text   = byteArrayToString(array)
                  _     <- buffer.clear
                } yield text
              }
    } yield text
  }
}
