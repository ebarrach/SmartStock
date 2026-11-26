package smartstock.inventory

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}

/** Held for the full service lifetime, including catalogue reconciliation. */
object InventoryProcessLock {
  def withLock[A](path: Path)(run: Path => A): A = {
    val absolute = path.toAbsolutePath.normalize()
    Files.createDirectories(absolute.getParent)
    val canonical = absolute.getParent.toRealPath().resolve(absolute.getFileName)
    val channel = FileChannel.open(
      canonical.resolveSibling(canonical.getFileName.toString + ".lock"),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    try {
      val lock =
        try Option(channel.tryLock())
        catch { case _: java.nio.channels.OverlappingFileLockException => None }
      require(lock.nonEmpty, s"Stockage deja utilise par un autre serveur : $canonical")
      try run(canonical)
      finally lock.foreach(_.release())
    } finally channel.close()
  }
}
