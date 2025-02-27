package zio.nio

import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{Chunk, IO}

import java.nio.{
  Buffer => JBuffer,
  BufferOverflowException,
  ByteBuffer => JByteBuffer,
  CharBuffer => JCharBuffer,
  DoubleBuffer => JDoubleBuffer,
  FloatBuffer => JFloatBuffer,
  IntBuffer => JIntBuffer,
  LongBuffer => JLongBuffer,
  ReadOnlyBufferException,
  ShortBuffer => JShortBuffer
}
import scala.reflect.ClassTag

object BufferSpec extends BaseSpec {

  def spec: Spec[TestEnvironment, TestFailure[Exception], TestSuccess] =
    suite("BufferSpec")(
      commonBufferTests(
        "ByteBuffer",
        Buffer.byte,
        Buffer.byte,
        JByteBuffer.allocate,
        _.toByte
      ),
      commonBufferTests(
        "CharBuffer",
        Buffer.char,
        Buffer.char,
        JCharBuffer.allocate,
        _.toChar
      ),
      commonBufferTests(
        "DoubleBuffer",
        Buffer.double,
        Buffer.double,
        JDoubleBuffer.allocate,
        _.toDouble
      ),
      commonBufferTests(
        "FloatBuffer",
        Buffer.float,
        Buffer.float,
        JFloatBuffer.allocate,
        _.toFloat
      ),
      commonBufferTests(
        "IntBuffer",
        Buffer.int,
        Buffer.int,
        JIntBuffer.allocate,
        identity
      ),
      commonBufferTests(
        "LongBuffer",
        Buffer.long,
        Buffer.long,
        JLongBuffer.allocate,
        _.toLong
      ),
      commonBufferTests(
        "ShortBuffer",
        Buffer.short,
        Buffer.short,
        JShortBuffer.allocate,
        _.toShort
      )
    )

  private def commonBufferTests[T, A: ClassTag, B <: JBuffer, C <: Buffer[A]](
    suiteName: String,
    allocate: Int => IO[Exception, C],
    wrap: Chunk[A] => IO[Exception, C],
    jAllocate: Int => B,
    f: Int => A
  ): ZSpec[TestEnvironment, Exception] = {
    val initialCapacity = 10
    def initialValue    = f(1)
    def initialValues   = Array(1, 2, 3).map(f)
    def zeroValues      = Array(0, 0, 0).map(f)

    suite(suiteName)(
      testM("apply") {
        for {
          allocated <- allocate(3)
          array     <- allocated.array
        } yield assert(array.toList)(hasSameElements(zeroValues.toList))
      },
      testM("wrap backed by an array") {
        for {
          buffer <- wrap(Chunk.fromArray(initialValues))
          array  <- buffer.array
        } yield assert(array.toList)(hasSameElements(initialValues.toList))
      },
      testM("capacity") {
        for {
          allocated <- allocate(initialCapacity)
          capacity   = allocated.capacity
        } yield assert(capacity)(equalTo(jAllocate(initialCapacity).capacity))
      },
      testM("capacity initialized") {
        for {
          allocated <- allocate(initialCapacity)
          capacity   = allocated.capacity
        } yield assert(capacity)(equalTo(initialCapacity))
      },
      testM("position is 0") {
        for {
          allocated <- allocate(initialCapacity)
          position  <- allocated.position
        } yield assert(position)(equalTo(0))
      },
      testM("limit is capacity") {
        for {
          allocated <- allocate(initialCapacity)
          limit     <- allocated.limit
        } yield assert(limit)(equalTo(initialCapacity))
      },
      testM("position set") {
        for {
          buffer   <- Buffer.byte(initialCapacity)
          _        <- buffer.position(3)
          position <- buffer.position
        } yield assert(position)(equalTo(3))
      },
      testM("limit set") {
        for {
          buffer   <- Buffer.byte(initialCapacity)
          limit     = 3
          _        <- buffer.limit(limit)
          newLimit <- buffer.limit
        } yield assert(newLimit)(equalTo(limit))
      },
      testM("position reset") {
        for {
          buffer   <- Buffer.byte(initialCapacity)
          newLimit  = 3
          _        <- buffer.position(newLimit + 1)
          _        <- buffer.limit(newLimit)
          position <- buffer.position
        } yield assert(position)(equalTo(newLimit))
      },
      testM("reset to marked position") {
        for {
          b           <- allocate(initialCapacity)
          _           <- b.position(1)
          _           <- b.mark
          _           <- b.position(2)
          _           <- b.reset
          newPosition <- b.position
        } yield assert(newPosition)(equalTo(1))
      },
      testM("clear") {
        for {
          b        <- allocate(initialCapacity)
          _        <- b.position(1)
          _        <- b.mark
          _        <- b.clear
          position <- b.position
          limit    <- b.limit
        } yield assert(position)(equalTo(0)) && assert(limit)(equalTo(initialCapacity))
      },
      testM("flip") {
        for {
          b        <- allocate(initialCapacity)
          _        <- b.position(1)
          _        <- b.flip
          position <- b.position
          limit    <- b.limit
        } yield assert(position)(equalTo(0)) && assert(limit)(equalTo(1))
      },
      testM("rewind sets position to 0") {
        for {
          b           <- allocate(initialCapacity)
          _           <- b.position(1)
          _           <- b.rewind
          newPosition <- b.position
        } yield assert(newPosition)(equalTo(0))
      },
      testM("heap buffers a backed by an array") {
        for {
          b <- allocate(initialCapacity)
        } yield assert(b.hasArray)(isTrue)
      },
      testM("put writes an element and increments the position") {
        for {
          b        <- allocate(initialCapacity)
          _        <- b.put(initialValue)
          newValue <- b.get(0)
          position <- b.position
        } yield assert(newValue)(equalTo(initialValue)) && assert(position)(equalTo(1))
      },
      testM("failing put if there are no elements remaining") {
        for {
          b      <- allocate(0)
          result <- b.put(initialValue).run
        } yield assert(result)(dies(isSubtype[BufferOverflowException](anything)))
      },
      testM("failing put if this is a read-only buffer") {
        for {
          b         <- allocate(initialCapacity)
          bReadOnly <- b.asReadOnlyBuffer
          result    <- bReadOnly.put(initialValue).run
        } yield assert(result)(dies(isSubtype[ReadOnlyBufferException](anything)))
      },
      testM("put writes an element at a specified index") {
        for {
          b        <- allocate(initialCapacity)
          _        <- b.put(1, initialValue)
          newValue <- b.get(1)
          position <- b.position
        } yield assert(newValue)(equalTo(initialValue)) && assert(position)(equalTo(0))
      },
      testM("failing put if the index is negative") {
        for {
          b      <- allocate(initialCapacity)
          result <- b.put(-1, initialValue).run
        } yield assert(result)(dies(isSubtype[IndexOutOfBoundsException](anything)))
      },
      testM("failing put if the index is not smaller than the limit") {
        for {
          b      <- allocate(initialCapacity)
          result <- b.put(initialCapacity, initialValue).run
        } yield assert(result)(dies(isSubtype[IndexOutOfBoundsException](anything)))
      },
      testM[TestEnvironment, Exception]("0 <= mark <= position <= limit <= capacity") {
        checkM(Gen.int(-1, 10), Gen.int(-1, 10), Gen.int(-1, 10), Gen.int(-1, 10)) {
          (markedPosition: Int, position: Int, limit: Int, capacity: Int) =>
            (for {
              b    <- Buffer.byte(capacity)
              _    <- b.limit(limit)
              _    <- b.position(markedPosition)
              _    <- b.mark
              _    <- b.position(position)
              _    <- b.reset
              mark <- b.position
            } yield assert(mark)(isWithin(0, position)) && assert(limit)(
              isWithin(position, capacity)
            )).catchSomeDefect { case _: IllegalArgumentException | _: IllegalStateException =>
              IO.effectTotal(assertCompletes)
            }
        }
      }
    )
  }
}
