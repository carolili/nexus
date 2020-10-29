package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID

import akka.persistence.query.Offset
import cats.implicits._
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCommand.{CreateProject, DeprecateProject, UpdateProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{OwnerPermissionsFailed, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectsDummy.{ProjectsCache, ProjectsJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Lens, Organizations, ProjectResource, Projects}
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Projects implementation
  * @param journal       the journal to store events
  * @param cache         the cache to store resources
  * @param semaphore     a semaphore for serializing write operations on the journal
  * @param organizations an Organizations instance
  */
final class ProjectsDummy private (
    journal: ProjectsJournal,
    cache: ProjectsCache,
    semaphore: IOSemaphore,
    organizations: Organizations,
    acls: Acls,
    ownerPermissions: Set[Permission],
    serviceAccount: Identity.Subject
)(implicit base: BaseUri, clock: Clock[UIO], uuidf: UUIDF)
    extends Projects {

  override def create(ref: ProjectRef, fields: ProjectFields)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(
      CreateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        caller
      )
    ) <* applyOwnerPermissions(ref, caller)

  private def applyOwnerPermissions(ref: ProjectRef, subject: Identity.Subject): IO[OwnerPermissionsFailed, Unit] = {
    val projectAddress = AclAddress.Project(ref.organization, ref.project)

    def applyMissing(collection: AclCollection) = {
      val currentPermissions = collection.value.foldLeft(Set.empty[Permission]) { case (acc, (_, acl)) =>
        acc ++ acl.value.permissions
      }

      if (ownerPermissions.subsetOf(currentPermissions))
        IO.unit
      else {
        val rev = collection.value.get(projectAddress).fold(0L)(_.rev)
        acls.append(projectAddress, Acl(subject -> ownerPermissions), rev)(serviceAccount) >> IO.unit
      }
    }

    acls.fetchWithAncestors(projectAddress).flatMap(applyMissing).leftMap(OwnerPermissionsFailed(ref, _))
  }

  override def update(ref: ProjectRef, rev: Long, fields: ProjectFields)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(
      UpdateProject(
        ref,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(ref),
        fields.vocabOrGenerated(ref),
        rev,
        caller
      )
    )

  override def deprecate(ref: ProjectRef, rev: Long)(implicit
      caller: Identity.Subject
  ): IO[ProjectRejection, ProjectResource] =
    eval(DeprecateProject(ref, rev, caller))

  override def fetch(ref: ProjectRef): UIO[Option[ProjectResource]] = cache.fetch(ref)

  override def fetchAt(ref: ProjectRef, rev: Long): IO[ProjectRejection.RevisionNotFound, Option[ProjectResource]] =
    journal
      .stateAt(ref, rev, Initial, Projects.next, ProjectRejection.RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))

  override def fetch(uuid: UUID): UIO[Option[ProjectResource]] = cache.fetchBy(p => p.uuid == uuid)

  override def list(
      pagination: Pagination.FromPagination,
      params: SearchParams.ProjectSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[ProjectResource]] =
    cache.list(pagination, params)

  private def eval(cmd: ProjectCommand): IO[ProjectRejection, ProjectResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState(cmd.ref, Initial, Projects.next).map(_.getOrElse(Initial))
        event <- Projects.evaluate(organizations.fetch)(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Projects.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.ref)))
        _     <- cache.setToCache(res)
      } yield res
    }

  override def events(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[ProjectEvent]] =
    journal.currentEvents(offset)
}

object ProjectsDummy {

  type ProjectsJournal = Journal[ProjectRef, ProjectEvent]
  type ProjectsCache   = ResourceCache[ProjectRef, Project]

  implicit val idLens: Lens[ProjectEvent, ProjectRef] = (event: ProjectEvent) =>
    ProjectRef(event.organizationLabel, event.label)

  /**
    * Creates a project dummy instance
    *
    * @param organizations an Organizations instance
    */
  def apply(
      organizations: Organizations,
      acls: Acls,
      ownerPermissions: Set[Permission],
      serviceAccount: Identity.Subject
  )(implicit base: BaseUri, clock: Clock[UIO], uuidf: UUIDF): UIO[ProjectsDummy] =
    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[ProjectRef, Project]
      sem     <- IOSemaphore(1L)
    } yield new ProjectsDummy(journal, cache, sem, organizations, acls, ownerPermissions, serviceAccount)
}
