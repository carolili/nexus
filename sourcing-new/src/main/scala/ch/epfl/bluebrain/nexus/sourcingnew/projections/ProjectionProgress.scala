package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.instances._
import io.circe.parser.decode


/**
  * Progression progress for a given view
  * @param offset
  * @param processed
  * @param discarded
  * @param failed
  */
final case class ProjectionProgress(offset: Offset,
                                    processed: Long,
                                    discarded: Long,
                                    failed: Long) {

  def + (message: Message[_]): ProjectionProgress = message match {
    case _ : DiscardedMessage => copy(
      offset = message.offset,
      processed = processed + 1,
      discarded = discarded + 1)
    case _ : ErrorMessage => copy(
      offset = message.offset,
      processed = processed + 1,
      failed = failed + 1)
    case _ => copy(
      offset = message.offset,
      processed = processed + 1)
  }

}

final case class CompositeProjectionProgress(id: ViewProjectionId,
                                             sourceProgress: Map[SourceProjectionId, ProjectionProgress],
                                             viewProgress: Map[CompositeViewProjectionId, ProjectionProgress])

object ProjectionProgress {

  val NoProgress: ProjectionProgress = ProjectionProgress(NoOffset, 0L, 0L, 0L)

  def fromTuple(input: (String, Long, Long, Long)): ProjectionProgress =
    decode[Offset](input._1).toOption.fold(NoProgress) {
      ProjectionProgress(_, input._2, input._3, input._4)
    }

}