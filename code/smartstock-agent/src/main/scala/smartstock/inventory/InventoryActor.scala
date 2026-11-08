package smartstock.inventory

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Clock
import scala.util.control.NonFatal
import spray.json._
import InventoryJson._

trait InventoryStore {
  def load(): Inventory
  def save(state: Inventory): Unit
}
final class FileInventoryStore(path: Path) extends InventoryStore {
  def load(): Inventory =
    if (Files.exists(path)) {
      val state = Files.readString(path, UTF_8).parseJson.convertTo[Inventory]
      val clock = Clock.systemUTC()
      val checked = state.products
        .foldLeft[Either[DomainError, Inventory]](Right(Inventory())) { (current, product) =>
          current.flatMap(s =>
            InventoryRules
              .execute(s, RegisterProduct(product), java.util.UUID.randomUUID().toString, clock)
          )
        }
        .flatMap { products =>
          state.batches.foldLeft[Either[DomainError, Inventory]](Right(products)) {
            (current, batch) =>
              current.flatMap(s =>
                InventoryRules
                  .execute(s, RegisterBatch(batch), java.util.UUID.randomUUID().toString, clock)
              )
          }
        }
      require(checked.isRight, "Invalid persisted inventory; refusing silent reset")
      state
    } else Inventory()

  def save(state: Inventory): Unit = {
    val directory = path.toAbsolutePath.getParent
    Files.createDirectories(directory)
    val temp = Files.createTempFile(directory, ".inventory-", ".json")
    try {
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE)
      try {
        val bytes = ByteBuffer.wrap(state.toJson.compactPrint.getBytes(UTF_8))
        while (bytes.hasRemaining) channel.write(bytes)
        channel.force(true)
      } finally channel.close()
      Files.move(
        temp,
        path.toAbsolutePath,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    } finally Files.deleteIfExists(temp)
  }
}

object InventoryActor {
  sealed trait Command
  final case class Execute(
      operation: InventoryOperation,
      eventId: String,
      replyTo: ActorRef[Either[DomainError, Inventory]]
  ) extends Command
  final case class Get(replyTo: ActorRef[Inventory]) extends Command

  // Spawn on smartstock.storage-dispatcher: serializes writes away from the router.
  def apply(store: InventoryStore, clock: Clock = Clock.systemUTC()): Behavior[Command] =
    Behaviors.setup { _ =>
      val initial = store.load() // Corrupt storage fails startup, never silently resets stock.
      def active(state: Inventory): Behavior[Command] = Behaviors.receiveMessage {
        case Get(replyTo) => replyTo ! state; Behaviors.same
        case Execute(operation, id, replyTo) =>
          InventoryRules.execute(state, operation, id, clock) match {
            case Left(error) => replyTo ! Left(error); Behaviors.same
            case Right(next) =>
              try {
                if (next != state) store.save(next)
                replyTo ! Right(next)
                active(next)
              } catch {
                case NonFatal(_) =>
                  replyTo ! Left(DomainError("storage_unavailable", "Ecriture non confirmee", 503))
                  Behaviors.same
              }
          }
      }
      active(initial)
    }
}
