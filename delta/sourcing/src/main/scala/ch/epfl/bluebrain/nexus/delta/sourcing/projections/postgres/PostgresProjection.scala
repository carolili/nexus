package ch.epfl.bluebrain.nexus.delta.sourcing.projections.postgres

import akka.persistence.query.{NoOffset, Offset, Sequence}
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClassUtils, ClasspathResourceUtils}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Severity.{Failure, Warning}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.postgres.PostgresProjection._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Postgres implementation of [[Projection]]
  */
private[projections] class PostgresProjection[A: Encoder: Decoder] private (
    xa: Transactor[Task],
    empty: => A,
    throwableToString: Throwable => String
)(implicit clock: Clock[UIO])
    extends Projection[A] {

  private val insertErrorQuery =
    """INSERT INTO projections_errors (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
                                   |value, severity, error_type, message)
                                   |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                   |ON CONFLICT DO NOTHING""".stripMargin

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit] =
    instant.flatMap { timestamp =>
      sql"""INSERT into projections_progress(projection_id, akka_offset, timestamp, processed, discarded, warnings, failed, value)
           |VALUES(${id.value}, ${offsetToSequence(progress.offset)}, ${timestamp.toEpochMilli}, ${progress.processed},
           |${progress.discarded}, ${progress.warnings}, ${progress.failed}, ${progress.value.asJson.noSpaces})
           |ON CONFLICT (projection_id) DO UPDATE SET akka_offset=EXCLUDED.akka_offset, timestamp=EXCLUDED.timestamp,
           |processed=EXCLUDED.processed, discarded=EXCLUDED.discarded, warnings=EXCLUDED.warnings, failed=EXCLUDED.failed, value=EXCLUDED.value""".stripMargin.update.run
        .transact(xa)
        .void
    }

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): Task[ProjectionProgress[A]] =
    sql"SELECT akka_offset, timestamp, processed, discarded, warnings, failed, value FROM projections_progress WHERE projection_id = ${id.value}"
      .query[(Option[Long], Long, Long, Long, Long, Long, String)]
      .option
      .transact(xa)
      .flatMap {
        case Some((offset, timestamp, processed, discarded, warnings, failed, value)) =>
          Task.fromEither(decode[A](value)).map { v =>
            ProjectionProgress(
              offset.fold[Offset](NoOffset)(Sequence),
              Instant.ofEpochMilli(timestamp),
              processed,
              discarded,
              warnings,
              failed,
              v
            )
          }
        case None                                                                     => Task.pure(NoProgress(empty))
      }

  private def batchErrors(id: ProjectionId, timestamp: Instant, messages: Vector[Message[A]]): Vector[ErrorParams] =
    messages.mapFilter {
      case c: CastFailedMessage                        =>
        Some(
          ErrorParams(
            id,
            c.offset,
            timestamp,
            c.persistenceId,
            c.sequenceNr,
            None,
            Severity.Failure,
            Some("ClassCastException"),
            c.errorMessage
          )
        )
      case f: FailureMessage[A]                        =>
        Some(
          ErrorParams(
            id,
            f.offset,
            timestamp,
            f.persistenceId,
            f.sequenceNr,
            Some(f.value.asJson),
            Severity.Failure,
            Some(ClassUtils.simpleName(f.throwable)),
            throwableToString(f.throwable)
          )
        )
      case w: SuccessMessage[A] if w.warnings.nonEmpty =>
        Some(
          ErrorParams(
            id,
            w.offset,
            timestamp,
            w.persistenceId,
            w.sequenceNr,
            Some(w.value.asJson),
            Severity.Warning,
            None,
            w.warningMessage
          )
        )
      case _                                           => None
    }

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param messages       the error messages to persist
    */
  override def recordErrors(
      id: ProjectionId,
      messages: Vector[Message[A]]
  ): Task[Unit] =
    for {
      timestamp <- instant
      batch      = batchErrors(id, timestamp, messages)
      updates   <-
        Task.when(batch.nonEmpty)(
          Update[ErrorParams](insertErrorQuery).updateMany(batch).transact(xa).void >>
            Task.delay(logger.error(s"Recording {} errors during projection {} at offset {}", batch.size, id.value))
        )
    } yield updates

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def errors(id: ProjectionId): fs2.Stream[Task, ProjectionError[A]] =
    sql"""SELECT value, akka_offset, timestamp, persistence_id, sequence_nr, severity, error_type, message from projections_errors WHERE projection_id = ${id.value} ORDER BY ordering"""
      .query[(Option[String], Option[Long], Long, String, Long, String, Option[String], String)]
      .stream
      .transact(xa)
      .map { case (value, offset, timestamp, persistenceId, sequenceNr, severity, errorType, message) =>
        val akkaOffset = offset.fold[Offset](NoOffset)(Sequence)
        val valueA     = value.flatMap(decode[A](_).toOption)
        val instant    = Instant.ofEpochMilli(timestamp)
        Severity.fromString(severity) match {
          case Warning =>
            ProjectionWarning(
              akkaOffset,
              instant,
              message,
              persistenceId,
              sequenceNr,
              valueA
            )
          case Failure =>
            ProjectionFailure(
              akkaOffset,
              instant,
              message,
              persistenceId,
              sequenceNr,
              valueA,
              errorType.getOrElse("Unknown")
            )
        }

      }
}

private class PostgresProjectionInitialization(xa: Transactor[Task]) {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  def initialize(): Task[Unit] =
    for {
      ddl   <- ClasspathResourceUtils
                 .ioContentOf("/scripts/postgres.ddl")
                 .hideErrorsWith(err => new IllegalArgumentException(err.toString))
      update = Fragment.const(ddl).update
      _     <- update.run.transact(xa)
    } yield ()
}

object PostgresProjection {
  private val logger: Logger = Logger[PostgresProjection.type]

  private def offsetToSequence(offset: Offset): Option[Long] =
    offset match {
      case Sequence(value) => Some(value)
      case _               => None
    }

  final private[postgres] case class ErrorParams(
      projectionId: String,
      offset: Option[Long],
      timestamp: Long,
      persistenceId: String,
      sequenceNr: Long,
      value: Option[String],
      severity: String,
      errorType: Option[String],
      message: String
  )

  private[postgres] object ErrorParams {

    def apply(
        projectionId: ProjectionId,
        offset: Offset,
        timestamp: Instant,
        persistenceId: String,
        sequenceNr: Long,
        value: Option[Json],
        severity: Severity,
        errorType: Option[String],
        message: String
    ): ErrorParams = ErrorParams(
      projectionId.value,
      offsetToSequence(offset),
      timestamp.toEpochMilli,
      persistenceId,
      sequenceNr,
      value.map(_.noSpaces),
      severity.toString,
      errorType,
      message
    )

  }

  def apply[A: Encoder: Decoder](
      xa: Transactor[Task],
      empty: => A,
      throwableToString: Throwable => String
  )(implicit clock: Clock[UIO]): Task[PostgresProjection[A]] = {
    new PostgresProjectionInitialization(xa).initialize().as(new PostgresProjection(xa, empty, throwableToString))
  }
}