package smartstock

import java.nio.file.{Files, Path, Paths}

object ProjectPaths {
  def root: Path =
    sys.env.get("SMARTSTOCK_HOME").map(Paths.get(_).toAbsolutePath.normalize()).getOrElse {
      val artifact =
        Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath
      val starts = Vector(artifact, Paths.get("").toAbsolutePath)
      starts.iterator
        .flatMap(p =>
          Iterator
            .iterate(Option(p))(_.flatMap(x => Option(x.getParent)))
            .takeWhile(_.nonEmpty)
            .flatten
        )
        .find(p => Files.isDirectory(p.resolve("code/smartstock-agent/config")))
        .getOrElse(
          throw new IllegalArgumentException(
            "SMARTSTOCK_HOME requis : racine du projet introuvable"
          )
        )
    }
  def resolve(value: String, root: Path): Path = {
    val path = Paths.get(value)
    (if (path.isAbsolute) path else root.resolve(path)).normalize()
  }
}
