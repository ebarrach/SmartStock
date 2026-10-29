package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

class MainSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "Le guardian de demonstration" should {
    "transmettre puis arreter son arbre sans erreur de contexte acteur" in {
      val stopped = createTestProbe[Any]()
      val guardian = spawn(Main.demo)
      stopped.expectTerminated(guardian)
    }
  }
}
