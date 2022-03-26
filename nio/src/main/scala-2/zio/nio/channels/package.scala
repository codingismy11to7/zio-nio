package zio.nio

import zio.{Scope, ZIO, ZTraceElement}

import java.io.IOException

package object channels {

  def useNioBlocking[R, E >: IOException, A, C <: BlockingChannel](managed: ZIO[Scope, IOException, C])(
    f: (C, C#BlockingOps) => ZIO[R, E, A]
  )(implicit trace: ZTraceElement): ZIO[R, E, A] =
    ZIO.scoped[R] {
      managed.flatMap(c => c.useBlocking(f(c, _)))
    }

  def useNioBlockingOps[R, E >: IOException, A, C <: BlockingChannel](managed: ZIO[Scope, IOException, C])(
    f: C#BlockingOps => ZIO[R, E, A]
  )(implicit trace: ZTraceElement): ZIO[R, E, A] =
    useNioBlocking[R, E, A, C](managed)((_, ops) => f(ops))

  /*
  implicit final class ManagedBlockingNioOps[-R, +C <: BlockingChannel](
    private val underlying: ZIO[R with Scope, IOException, C]
  ) extends AnyVal {

    def useNioBlocking[R1, E >: IOException, A](
      f: (C, C#BlockingOps) => ZIO[R1, E, A]
    )(implicit trace: ZTraceElement): ZIO[R with R1 with Any, E, A] =
      ZIO.scoped[R with R1] {
        underlying.flatMap(c => c.useBlocking(f(c, _)))
      }

    def useNioBlockingOps[R1, E >: IOException, A](
      f: C#BlockingOps => ZIO[R1, E, A]
    )(implicit trace: ZTraceElement): ZIO[R with R1 with Any, E, A] = useNioBlocking((_, ops) => f(ops))

  }
   */

  implicit final class ManagedNonBlockingNioOps[-R, +C <: SelectableChannel](
    private val underlying: ZIO[R with Scope, IOException, C]
  ) extends AnyVal {

    def useNioNonBlocking[R1, E >: IOException, A](f: (C, C#NonBlockingOps) => ZIO[R1, E, A])(implicit
      trace: ZTraceElement
    ): ZIO[R with R1, E, A] = ZIO.scoped[R with R1] {
      underlying.flatMap(c => c.useNonBlocking(f(c, _)))
    }

    def useNioNonBlockingOps[R1, E >: IOException, A](f: C#NonBlockingOps => ZIO[R1, E, A])(implicit
      trace: ZTraceElement
    ): ZIO[R with R1, E, A] =
      useNioNonBlocking((_, ops) => f(ops))

  }

}
