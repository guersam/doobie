package doobie.util

import doobie.free.preparedstatement.{ PreparedStatementIO, setNull }
import doobie.free.resultset.{ ResultSetIO, wasNull, updateNull, raw }
import doobie.enum.nullability._
import doobie.util.meta._
import doobie.util.invariant._

import java.sql.ResultSet

import scalaz.{ InvariantFunctor, Maybe }
import scalaz.syntax.applicative._
import scalaz.syntax.std.boolean._

import scala.annotation.implicitNotFound

/** Module defining `NULL`-aware column mappings; the next step "up" from `Meta`. */
object atom {

  /**
   * A `Meta` together with a `Nullability` and `NULL`-aware `get/set/update`. Given an
   * implicit `Meta[A]` you get derived `Atom[A]` and `Atom[Option[A]]`.
   */
  @implicitNotFound("Could not find or construct Atom[${A}]; ensure that ${A} has a Meta instance.")
  sealed trait Atom[A] { outer =>
    val set: (Int, A) => PreparedStatementIO[Unit]
    val update: (Int, A) => ResultSetIO[Unit]
    val get: Int => ResultSetIO[A] = n => raw(rs => unsafeGet(rs, n))
    val unsafeGet: (ResultSet, Int) => A
    val meta: (Meta[_], NullabilityKnown)
    def xmap[B](f: A => B, g: B => A): Atom[B] =
      new Atom[B] {
        val set = (n: Int, b: B) => outer.set(n, g(b))
        val update = (n: Int, b: B) => outer.update(n, g(b))
        val unsafeGet = (r: ResultSet, n: Int) => f(outer.unsafeGet(r, n))
        val meta = outer.meta
      }
  }
  object Atom {

    def apply[A](implicit A: Atom[A]): Atom[A] = A

    implicit def fromScalaType[A](implicit A: Meta[A]): Atom[A] =
      new Atom[A] {
        val unsafeGet = { (r: ResultSet, n: Int) => 
          val (a, b) = (A.unsafeGet(r, n), r.wasNull)
          if (b) throw NonNullableColumnRead(n, A.jdbcTarget.head) else a
        }
        val set = (n: Int, a: A) => if (a == null) throw NonNullableParameter(n, A.jdbcTarget.head) else A.set(n, a)
        val update = (n: Int, a: A) => if (a == null) throw NonNullableColumnUpdate(n, A.jdbcTarget.head) else A.update(n, a)
        val meta = (A, NoNulls)
      }

    implicit def fromScalaTypeOption[A](implicit A: Meta[A]): Atom[Option[A]] =
      new Atom[Option[A]] {
        val unsafeGet = (r: ResultSet, n: Int) => {
          val (a, b) = (A.unsafeGet(r, n), r.wasNull)
          if (!b) Some(a) else None
        }
        val set = (n: Int, a: Option[A]) => a.fold(A.setNull(n))(A.set(n, _))
        val update = (n: Int, a: Option[A]) => a.fold(updateNull(n))(A.update(n, _))
        val meta = (A, Nullable)
      }

    implicit def fromScalaTypeMaybe[A](implicit A: Meta[A]): Atom[Maybe[A]] =
      new Atom[Maybe[A]] {
        val unsafeGet = (r: ResultSet, n: Int) => {
          val (a, b) = (A.unsafeGet(r, n), r.wasNull)
          if (b) Maybe.empty[A] else Maybe.just(a)
        }
        val set = (n: Int, a: Maybe[A]) => a.cata(A.set(n, _), A.setNull(n))
        val update = (n: Int, a: Maybe[A]) => a.cata(A.update(n, _), updateNull(n))
        val meta = (A, Nullable)
      }

    implicit val atomInvariantFunctor: InvariantFunctor[Atom] =
      new InvariantFunctor[Atom] {
        def xmap[A, B](ma: Atom[A], f: A => B, g: B => A): Atom[B] =
          ma.xmap(f, g)
      }

  }

}
