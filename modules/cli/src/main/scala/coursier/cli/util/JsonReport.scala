package coursier.cli.util

import java.io.File
import java.util.Objects

import cats.Eval
import cats.free.Cofree
import cats.syntax.foldable._

import coursier.core._
import coursier.util.Artifact

import scala.collection.mutable
import scala.collection.immutable.SortedSet

import argonaut._
import Argonaut._

/** Lookup table for files and artifacts to print in the JsonReport.
  */
final case class JsonPrintRequirement(
  fileByArtifact: Map[String, File],
  depToArtifacts: Map[Dependency, Vector[(Publication, Artifact)]]
)

/** Represents a resolved dependency's artifact in the JsonReport.
  * @param coord
  *   String representation of the artifact's maven coordinate.
  * @param file
  *   The path to the file for the artifact.
  * @param dependencies
  *   The dependencies of the artifact.
  */
final case class DepNode(
  coord: String,
  file: Option[String],
  directDependencies: Set[String],
  dependencies: Set[String],
  exclusions: Set[String] = Set.empty
)

final case class ReportNode(
  conflict_resolution: Map[String, String],
  dependencies: Vector[DepNode],
  version: String
)

/** FORMAT_VERSION_NUMBER: Version number for identifying the export file format output. This
  * version number should change when there is a change to the output format.
  *
  * Major Version 1.x.x : Increment this field when there is a major format change Minor Version
  * x.1.x : Increment this field when there is a minor change that breaks backward compatibility for
  * an existing field or a field is removed. Patch version x.x.1 : Increment this field when a minor
  * format change that just adds information that an application can safely ignore.
  *
  * Note format changes in cli/README.md and update the Changelog section.
  */
object ReportNode {
  import argonaut.ArgonautShapeless._
  implicit val encodeJson = EncodeJson.of[ReportNode]
  implicit val decodeJson = DecodeJson.of[ReportNode]
  val version             = "0.1.0"
}

object JsonReport {

  private val printer = PrettyParams.nospace.copy(preserveOrder = true)

  def apply[T](roots: Vector[T], conflictResolutionForRoots: Map[String, String])(
    children: T => Vector[T],
    reconciledVersionStr: T => String,
    requestedVersionStr: T => String,
    getFile: T => Option[String],
    exclusions: T => Set[String]
  ): String = {

    // Addresses the corner case in which any given library may list itself among its dependencies
    // See: https://github.com/coursier/coursier/issues/2316
    def childrenOrEmpty(elem: T): Vector[T] = {
      val elemId = reconciledVersionStr(elem)
      children(elem).filterNot(i => reconciledVersionStr(i) == elemId)
    }

    lazy val depToTransitiveDeps = {
      // Builds a map of flattened dependencies starting at this element
      // The implementation makes use of Cofree[List, T] which is a foldable co-monad
      // and because of that, we can collapse it at each of its nodes and aggregate the results
      def transitiveOf(elem: T): Map[T, Vector[String]] = {        
        // Collapse node builds the list of dependencies by reaching to the leave first
        // and then building back the list by prepending the head
        def collapseNode(node: Cofree[Vector, T]): Eval[Vector[String]] = {
          for {
            tail     <- node.tail
            children <- tail.foldMapM(child => Eval.defer(collapseNode(child))) // collapses the tail until we reach a leave
          } yield children :+ reconciledVersionStr(node.head)
        }

        // Associate each item in the dependency tree, with the aggregated dependencies of its branches
        def collapseIntoMap(node: Cofree[Vector, T]): Eval[Map[T, Vector[String]]] =
          collapseNode(node).map(children => Map(node.head -> children))

        // A dependency tree forms a Cofree[List, T] so we first build the structure
        val depTree: Cofree[Vector, T] = Cofree.unfold(elem)(childrenOrEmpty(_))

        // coflatMap gives us an entire subtree at each node, which we can collapse
        // and finally fold together
        depTree.coflatMap(collapseIntoMap).foldMapM(identity).value
      }

      roots.map(transitiveOf(_))
        .foldMap(identity)
        .map { case (elem, deps) =>
          // The Cofree fold will include the actual node item in its dependencies list
          // so we remove it here
          elem -> (deps.to[SortedSet] - reconciledVersionStr(elem))
        }
    }

    def flattenedDeps(elem: T): Set[String] =
      depToTransitiveDeps.getOrElse(elem, Set.empty[String])

    val rootDeps: Seq[DepNode] = roots.map { r =>
      DepNode(
        reconciledVersionStr(r),
        getFile(r),
        childrenOrEmpty(r).map(reconciledVersionStr(_)).to[SortedSet],
        flattenedDeps(r),
        exclusions(r)
      )
    }

    val report = ReportNode(
      conflictResolutionForRoots,
      rootDeps.toVector.sortBy(_.coord),
      ReportNode.version
    )
    printer.pretty(report.asJson)
  }

}

final case class JsonElem(
  dep: Dependency,
  artifacts: Seq[(Dependency, Artifact)] = Seq(),
  jsonPrintRequirement: Option[JsonPrintRequirement],
  resolution: Resolution,
  colors: Boolean,
  printExclusions: Boolean,
  excluded: Boolean,
  overrideClassifiers: Set[Classifier]
) {

  // This is used to printing json output
  // Option of the file path
  lazy val downloadedFile: Option[String] =
    jsonPrintRequirement.flatMap(req =>
      req.depToArtifacts.getOrElse(dep, Seq()).view
        .filter(_._1.classifier == dep.attributes.classifier)
        .map(x => req.fileByArtifact.get(x._2.url))
        .filter(_.isDefined)
        .filter(_.nonEmpty)
        .map(_.get.getPath)
        .headOption
    )

  lazy val reconciledVersion: String = resolution.reconciledVersions
    .getOrElse(dep.module, dep.version)

  // These are used to printing json output
  val reconciledVersionStr = s"${dep.mavenPrefix}:$reconciledVersion"
  val requestedVersionStr  = s"${dep.module}:${dep.version}"

  lazy val exclusions: Set[String] = dep.exclusions.map {
    case (org, name) =>
      s"${org.value}:${name.value}"
  }

  lazy val children: Vector[JsonElem] =
    if (excluded) Vector.empty
    else {
      val dependencies = resolution.dependenciesOf(
        dep,
        withRetainedVersions = false
      ).sortBy { trDep =>
        (trDep.module.organization, trDep.module.name, trDep.version)
      }.map { d =>
        if (overrideClassifiers.contains(dep.attributes.classifier))
          d.withAttributes(d.attributes.withClassifier(dep.attributes.classifier))
        else
          d
      }

      def calculateExclusions = resolution
        .dependenciesOf(
          dep.withExclusions(Set.empty),
          withRetainedVersions = false
        )
        .view
        .sortBy { trDep =>
          (trDep.module.organization, trDep.module.name, trDep.version)
        } 
        .map(_.moduleVersion)
        .filterNot(dependencies.map(_.moduleVersion).toSet).map {
          case (mod, ver) =>
            JsonElem(
              Dependency(mod, ver)
                .withConfiguration(Configuration.empty)
                .withExclusions(Set.empty[(Organization, ModuleName)])
                .withAttributes(Attributes.empty)
                .withOptional(false)
                .withTransitive(false),
              artifacts,
              jsonPrintRequirement,
              resolution,
              colors,
              printExclusions,
              excluded = true,
              overrideClassifiers = overrideClassifiers
            )
        }

      val dependencyElems = mutable.ListBuffer.empty[JsonElem]
      val it = dependencies.iterator
      while (it.hasNext) {
        val dep = it.next()
        val elem = JsonElem(
          dep,
          artifacts,
          jsonPrintRequirement,
          resolution,
          colors,
          printExclusions,
          excluded = false,
          overrideClassifiers = overrideClassifiers
        )
        dependencyElems += elem
      }
      dependencyElems ++ (if (printExclusions) calculateExclusions else Nil)
      dependencyElems.toVector
    }

  /** Override the hashcode to explicitly exclude `children`, because children will result in
    * recursive hash on children's children, causing performance issue. Hash collision should be
    * rare, but when that happens, the default equality check should take of the recursive aspect of
    * `children`.
    */
  override def hashCode(): Int =
    Objects.hash(dep, requestedVersionStr, reconciledVersion, downloadedFile)
}
