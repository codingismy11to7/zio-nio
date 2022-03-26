package zio.nio.file

import zio.ZIO
import zio.nio.BaseSpec
import zio.test.Assertion._
import zio.test._

import java.io.IOException
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE

object WatchServiceSpec extends BaseSpec {

  override def spec: Spec[Any, TestFailure[IOException], TestSuccess] =
    suite("WatchServiceSpec")(
      test("Watch Service register")(ZIO.scoped {
        FileSystem.default.newWatchService.flatMap { watchService =>
          for {
            watchKey <- Path("nio/src/test/resources").register(watchService, ENTRY_CREATE)
            watchable = watchKey.watchable
          } yield assert(watchable)(equalTo(Path("nio/src/test/resources")))
        }
      })
    )
}
