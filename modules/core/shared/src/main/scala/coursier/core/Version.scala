package coursier.core

import scala.annotation.tailrec
import coursier.core.compatibility._

/**
 *  Used internally by Resolver.
 *
 *  Same kind of ordering as aether-util/src/main/java/org/eclipse/aether/util/version/GenericVersion.java
 */
final case class Version(repr: String) extends Ordered[Version] {
  lazy val items: List[Version.Item] = Version.items(repr)
  def compare(other: Version) = Version.listCompare(items, other.items)
  def isEmpty = items.forall(_.isEmpty)
}

object Version {

  private[coursier] val zero = Version("0")

  sealed abstract class Item extends Ordered[Item] {
    def compare(other: Item): Int =
      (this, other) match {
        case (Number(a), Number(b)) => a.compare(b)
        case (BigNumber(a), BigNumber(b)) => a.compare(b)
        case (Number(a), BigNumber(b)) => -b.compare(a)
        case (BigNumber(a), Number(b)) => a.compare(b)
        case (a @ Tag(_), b @ Tag(_)) => a.compareTag(b)
        case (BuildMetadata(_), BuildMetadata(_)) =>
          // Semver § 10: two versions that differ only in the build metadata, have the same precedence.
          // Might introduce some non-determinism though :-/
          0

        case _ =>
          val rel0 = compareToEmpty
          val rel1 = other.compareToEmpty

          if (rel0 == rel1) order.compare(other.order)
          else rel0.compare(rel1)
      }

    def order: Int
    def isEmpty: Boolean = compareToEmpty == 0
    def compareToEmpty: Int = 1
  }

  sealed abstract class Numeric extends Item {
    def repr: String
    def next: Numeric
  }
  final case class Number(value: Int) extends Numeric {
    val order = 0
    def next: Number = Number(value + 1)
    def repr: String = value.toString
    override def compareToEmpty = value.compare(0)
  }
  final case class BigNumber(value: BigInt) extends Numeric {
    val order = 0
    def next: BigNumber = BigNumber(value + 1)
    def repr: String = value.toString
    override def compareToEmpty = value.compare(0)
  }

  /**
   * Tags represent prerelease tags, typically appearing after - for SemVer compatible versions.
   */
  final case class Tag(value: String) extends Item {
    val order = -1
    private val otherLevel = -5
    lazy val level: Int =
      value match {
        case "ga" | "final" | "" => 0 // 1.0.0 equivalent
        case "snapshot"          => -1
        case "rc" | "cr"         => -2
        case "beta" | "b"        => -3
        case "alpha" | "a"       => -4
        case "dev"               => -6
        case "sp" | "bin"        => 1
        case _                   => otherLevel
      }

    override def compareToEmpty = level.compare(0)
    def isPreRelease: Boolean = level < 0
    def compareTag(other: Tag): Int = {
      val levelComp = level.compare(other.level)
      if (levelComp == 0 && level == otherLevel) value.compareToIgnoreCase(other.value)
      else levelComp
    }
  }
  final case class BuildMetadata(value: String) extends Item {
    val order = 1
    override def compareToEmpty = 0
  }

  case object Min extends Item {
    val order = -8
    override def compareToEmpty = -1
  }
  case object Max extends Item {
    val order = 8
  }

  val empty = Number(0)

  private val alphaQualifier = Tag("alpha")
  private val betaQualifier = Tag("beta")
  private val milestoneQualifier = Tag("milestone")

  object Tokenizer {
    sealed abstract class Separator
    case object Dot extends Separator
    case object Hyphen extends Separator
    case object Underscore extends Separator
    case object Plus extends Separator
    case object None extends Separator

    def apply(str: String): (Item, Stream[(Separator, Item)]) = {
      def parseItem(s: Stream[Char], prev: Option[Separator]): (Item, Stream[Char]) = {
        if (s.isEmpty) (empty, s)
        else if (s.head.isDigit) {
          def digits(b: StringBuilder, s: Stream[Char]): (String, Stream[Char]) =
            if (s.isEmpty || !s.head.isDigit) (b.result(), s)
            else digits(b += s.head, s.tail)

          val (digits0, rem) = digits(new StringBuilder, s)
          val item =
            if (digits0.length >= 10) BigNumber(BigInt(digits0))
            else Number(digits0.toInt)

          (item, rem)
        } else if (s.head.letter) {
          def letters(b: StringBuilder, s: Stream[Char]): (String, Stream[Char]) =
            if (s.isEmpty || !s.head.letter)
              (b.result().toLowerCase, s) // not specifying a Locale (error with scala js)
            else
              letters(b += s.head, s.tail)

          val (letters0, rem) = letters(new StringBuilder, s)
          val item = letters0 match {
            case "x" if prev == Some(Dot) => Max
            case "min" => Min
            case "max" => Max
            case _     => Tag(letters0)
          }
          (item, rem)
        } else {
          val (sep, _) = parseSeparator(s)
          (prev, sep) match {
            case (_, None) =>
              def other(b: StringBuilder, s: Stream[Char]): (String, Stream[Char]) =
                if (s.isEmpty || s.head.isLetterOrDigit || parseSeparator(s)._1 != None)
                  (b.result().toLowerCase, s)  // not specifying a Locale (error with scala js)
                else
                  other(b += s.head, s.tail)

              val (item, rem0) = other(new StringBuilder, s)
              // treat .* as .max
              if (prev == Some(Dot) && item == "*") (Max, rem0)
              else (Tag(item), rem0)
            // treat .+ as .max
            case (Some(Dot), Plus) => (Max, s)
            case _                 => (empty, s)
          }
        }
      }

      def parseSeparator(s: Stream[Char]): (Separator, Stream[Char]) = {
        assert(s.nonEmpty)

        s.head match {
          case '.' => (Dot, s.tail)
          case '-' => (Hyphen, s.tail)
          case '_' => (Underscore, s.tail)
          case '+' => (Plus, s.tail)
          case _ => (None, s)
        }
      }

      def helper(s: Stream[Char]): Stream[(Separator, Item)] = {
        if (s.isEmpty) Stream()
        else {
          val (sep, rem0) = parseSeparator(s)
          sep match {
            case Plus =>
              Stream((sep, BuildMetadata(rem0.mkString)))
            case _ =>
              val (item, rem) = parseItem(rem0, Some(sep))
              (sep, item) #:: helper(rem)
          }
        }
      }

      val (first, rem) = parseItem(str.toStream, scala.None)
      (first, helper(rem))
    }
  }

  def postProcess(item: Item, tokens0: Stream[(Tokenizer.Separator, Item)]): Stream[Item] = {

    val tokens =
      // drop some '.0' under some conditions ???
      if (isNumeric(item)) {
        val nextNonDotZero = tokens0.dropWhile{case (Tokenizer.Dot, n: Numeric) => n.isEmpty; case _ => false }
        if (nextNonDotZero.headOption.forall { case (sep, t) => sep != Tokenizer.Plus && !isMinMax(t) && !isNumeric(t) })
          nextNonDotZero
        else
          tokens0
      } else
        tokens0

    def ifFollowedByNumberElse(ifFollowedByNumber: Item, default: Item) = {
      val followedByNumber = tokens.headOption
        .exists{ case (Tokenizer.None, num: Numeric) if !num.isEmpty => true; case _ => false }

      if (followedByNumber) ifFollowedByNumber
      else default
    }

    val nextItem = item match {
      case Tag("a") => ifFollowedByNumberElse(alphaQualifier, item)
      case Tag("b") => ifFollowedByNumberElse(betaQualifier, item)
      case Tag("m") => ifFollowedByNumberElse(milestoneQualifier, item)
      case _ => item
    }

    def next =
      if (tokens.isEmpty) Stream()
      else postProcess(tokens.head._2, tokens.tail)

    nextItem #:: next
  }

  def isNumeric(item: Item) = item match { case _: Numeric => true; case _ => false }

  def isMinMax(item: Item) = {
    (item eq Min) || (item eq Max) || item == Tag("min") || item == Tag("max")
  }

  def items(repr: String): List[Item] = {
    val (first, tokens) = Tokenizer(repr)

    postProcess(first, tokens).toList
  }

  @tailrec
  def listCompare(first: List[Item], second: List[Item]): Int = {
    if (first.isEmpty && second.isEmpty) 0
    else if (first.isEmpty) {
      assert(second.nonEmpty)
      -second.dropWhile(_.isEmpty).headOption.fold(0)(_.compareToEmpty)
    } else if (second.isEmpty) {
      assert(first.nonEmpty)
      first.dropWhile(_.isEmpty).headOption.fold(0)(_.compareToEmpty)
    } else {
      val rel = first.head.compare(second.head)
      if (rel == 0) listCompare(first.tail, second.tail)
      else rel
    }
  }

}
