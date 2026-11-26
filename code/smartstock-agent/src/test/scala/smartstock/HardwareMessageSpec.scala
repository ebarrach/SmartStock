package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.events.HardwareMessage._

class HardwareMessageSpec extends AnyWordSpec {
  "Le contrat materiel" should {
    "conserver les unites brutes sans conversion inventee" in {
      assert(
        parse(
          """{"sensor":"weight","event":"exit","value":1000,"delta":-600,"unit":"raw"}"""
        ) == Right(RawWeight("exit", 1000, -600))
      )
    }
    "lire les mesures simples et enveloppees" in {
      assert(
        parse("""{"sensor":"temperature","value":20.5}""") == Right(Measure("temperature", 20.5))
      )
      assert(
        parse("""{"data":{"sensor":"humidity","value":55}}""") == Right(Measure("humidity", 55))
      )
      assert(parse("""{"sensor":"door","value":"open"}""") == Right(Door("open")))
    }
    "refuser donnees invalides et signes incoherents" in {
      Vector(
        "{",
        "{}",
        """{"sensor":"humidity","value":101}""",
        """{"sensor":"weight","event":"entry","value":1,"delta":-2,"unit":"raw"}"""
      ).foreach(s => assert(parse(s).isLeft))
    }
    "ne pas dedupliquer deux observations identiques par leur seul contenu" in {
      val wire = """{"sensor":"temperature","value":20}"""
      assert(Vector(wire, wire).map(parse).count(_.isRight) == 2)
    }
  }
}
