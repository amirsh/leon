import leon.annotation._
import leon.lang._

object SparseVector {
  sealed abstract class SparseVector
  case class  Cons(index: Int, value : Int, tail: SparseVector) extends SparseVector
  case object Nil extends SparseVector

  sealed abstract class Option
  case class Some(v: Int) extends Option
  case object None extends Option

  def size(sv: SparseVector): Int = {
    sv match {
      case Cons(i, v, t) =>
        1 + size(t)
      case Nil =>
        0
    }
  } ensuring { _ >= 0 }

  def isDefined(o: Option) = o match {
    case Some(v) => true
    case None => false
  }

  def valueOf(o: Option) = o match {
    case Some(v) => v
    case None => -42
  }

  def values(sv: SparseVector): Set[Int] = sv match {
    case Cons(i, v, t) =>
      values(t) ++ Set(v)
    case Nil =>
      Set()
  }

  def indices(sv: SparseVector): Set[Int] = sv match {
    case Cons(i, v, t) =>
      indices(t) ++ Set(i)
    case Nil =>
      Set()
  }

  def invariant(sv: SparseVector): Boolean = sv match {
    case Cons(i1, v1, t1 @ Cons(i2, v2, t2)) =>
      (i1 < i2) && invariant(t1)
    case _ => true
  }

  def set(sv: SparseVector, at: Int, newV: Int): SparseVector = choose {
    (res: SparseVector) => invariant(sv) && (values(res) contains newV) && invariant(res) && (indices(res) == indices(sv) ++ Set(at))
  }

  def delete(sv: SparseVector, at: Int): SparseVector = choose {
    (res: SparseVector) => invariant(sv) && (size(res) <= size(sv)) && invariant(res) && (indices(res) == indices(sv) -- Set(at))
  }

  def get(sv: SparseVector, at: Int): Option = choose {
    (res: Option) => invariant(sv) && ((indices(sv) contains at) == isDefined(res)) && (!isDefined(res) || (values(sv) contains valueOf(res)))
  }
}
