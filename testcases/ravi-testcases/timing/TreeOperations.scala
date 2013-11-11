import leon.Utils._

object TreeOperations {

  sealed abstract class List
  case class Cons(head: Int, tail: List) extends List
  case class Nil() extends List

  sealed abstract class Tree
  case class Node(left: Tree, value: Int, right: Tree) extends Tree
  case class Leaf() extends Tree

  def listSize(l: List): Int = (l match {
    case Nil() => 0
    case Cons(_, t) => 1 + listSize(t)
  })

  def size(t: Tree): Int = {
    t match {
      case Leaf() => 0
      case Node(l, x, r) => {
        size(l) + size(r) + 1
      }
    }
  }

  def height(t: Tree): Int = {
    t match {
      case Leaf() => 0
      case Node(l, x, r) => {
        val hl = height(l)
        val hr = height(r)
        if (hl > hr) hl + 1 else hr + 1
      }
    }
  } 

  def insert(elem: Int, t: Tree): Tree = {
    t match {
      case Leaf() => Node(Leaf(), elem, Leaf())
      case Node(l, x, r) => if (x <= elem) Node(l, x, insert(elem, r))
      else Node(insert(elem, l), x, r)
    }
  } ensuring (res => height(res) <= height(t) + 1 template((a,b) => time <= a*height(t) + b))

  def mult(x : Int, y : Int) : Int = {
      if(x == 0 || y == 0) 0
      else
    	  mult(x-1,y-1) +  x + y - 1
  } 
  
  //cannot prove time bound for  this :-(
  def addAll(l: List, t: Tree): Tree = {
    l match {
      case Nil() => t
      case Cons(x, xs) =>{
        val newt = insert(x, t)
        addAll(xs, newt)
      } 
    }
  }  

  def remove(elem: Int, t: Tree): Tree = {
    t match {
      case Leaf() => Leaf()
      case Node(l, x, r) => {

        if (x < elem) Node(l, x, remove(elem, r))
        else if (x > elem) Node(remove(elem, l), x, r)
        else {
          t match {            
            case Node(Leaf(), x, Leaf()) => Leaf()
            case Node(Leaf(), x, Node(_, rx, _)) => Node(Leaf(), rx, remove(rx, r))
            case Node(Node(_, lx, _), x, r) => Node(remove(lx, l), lx, r)
            case _ => Leaf()
          }
        }
      }
    }
  } ensuring (res => true template ((a, b, c) => time <= a*height(t) + b))

  //cannot prove time bound for  this :-(
  def removeAll(l: List, t: Tree): Tree = {
    l match {
      case Nil() => t
      case Cons(x, xs) => removeAll(xs, remove(x, t))
    }
  }

  def contains(elem : Int, t : Tree) : Boolean = {
    t match {
      case Leaf() => false
      case Node(l, x, r) =>
        if(x == elem) true
        else if (x < elem) contains(elem, r)
        else contains(elem, l)
    }
  } ensuring (res => true template((a,b) => time <= a*height(t) + b))
} 