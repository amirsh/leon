/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package solvers.z3

import leon.utils._
import z3.scala._
import solvers._
import purescala.Common._
import purescala.Definitions._
import purescala.Trees._
import purescala.TypeTreeOps._
import xlang.Trees._
import purescala.TreeOps._
import purescala.TypeTrees._
import scala.collection.mutable.{Map => MutableMap}
import scala.collection.mutable.{Set => MutableSet}
import leon.purescala.Extractors._
import invariant.factories._

// This is just to factor out the things that are common in "classes that deal
// with a Z3 instance"
trait AbstractZ3Solver
  extends Solver
     with AssumptionSolver
     with IncrementalSolver 
     with Interruptible {

  val context : LeonContext
  val program : Program

  protected[z3] val reporter : Reporter = context.reporter

  context.interruptManager.registerForInterrupts(this)

  private[this] var freed = false
  val traceE = new Exception()

  override def finalize() {
    if (!freed) {
      println("!! Solver "+this.getClass.getName+"["+this.hashCode+"] not freed properly prior to GC:")
      traceE.printStackTrace()
      free()
    }
  }

  class CantTranslateException(t: Z3AST) extends Exception("Can't translate from Z3 tree: " + t)  

  protected[leon] val z3cfg : Z3Config
  protected[leon] var z3 : Z3Context    = null

  override def free() {
    freed = true
    if (z3 ne null) {
      z3.delete()
      z3 = null;
    }
  }

  protected[z3] var interrupted = false;

  override def interrupt() {
    interrupted = true
    if(z3 ne null) {
      z3.interrupt
    }
  }

  override def recoverInterrupt() {
    interrupted = false
  }

  def functionDefToDecl(tfd: TypedFunDef): Z3FuncDecl = {
    functions.toZ3OrCompute(tfd) {
      val sortSeq    = tfd.params.map(vd => typeToSort(vd.tpe))
      val returnSort = typeToSort(tfd.returnType)

      z3.mkFreshFuncDecl(tfd.id.uniqueName, sortSeq, returnSort)
    }
  }

  def genericValueToDecl(gv: GenericValue): Z3FuncDecl = {
    generics.toZ3OrCompute(gv) {
      z3.mkFreshFuncDecl(gv.tp.toString+"#"+gv.id+"!val", Seq(), typeToSort(gv.tp))
    }
  }

  object LeonType {
    def unapply(a: Z3Sort): Option[(TypeTree)] = {
      sorts.getLeon(a).map(tt => (tt))      
    }
  }

  class Bijection[A, B] {
    var leonToZ3 = Map[A, B]()
    var z3ToLeon = Map[B, A]()

    def +=(a: A, b: B): Unit = {
      leonToZ3 += a -> b
      z3ToLeon += b -> a
    }

    def +=(t: (A,B)): Unit = {
      this += (t._1, t._2)
    }


    def clear(): Unit = {
      z3ToLeon = Map()
      leonToZ3 = Map()
    }

    def getZ3(a: A): Option[B] = leonToZ3.get(a)
    def getLeon(b: B): Option[A] = z3ToLeon.get(b)

    def toZ3(a: A): B = getZ3(a).get
    def toLeon(b: B): A = getLeon(b).get

    def toZ3OrCompute(a: A)(c: => B) = {
      getZ3(a).getOrElse {
        val res = c
        this += a -> res
        res
      }
    }

    def toLeonOrCompute(b: B)(c: => A) = {
      getLeon(b).getOrElse {
        val res = c
        this += res -> b
        res
      }
    }

    def containsLeon(a: A): Boolean = leonToZ3 contains a
    def containsZ3(b: B): Boolean = z3ToLeon contains b
  }

  // Bijections between Leon Types/Functions/Ids to Z3 Sorts/Decls/ASTs
  protected[leon] var functions = new Bijection[TypedFunDef, Z3FuncDecl]
  protected[leon] var generics  = new Bijection[GenericValue, Z3FuncDecl]
  protected[leon] var sorts     = new Bijection[TypeTree, Z3Sort]
  protected[leon] var variables = new Bijection[Expr, Z3AST]

  // Meta decls and information used by several sorts
  case class ArrayDecls(cons: Z3FuncDecl, select: Z3FuncDecl, length: Z3FuncDecl)
  case class TupleDecls(cons: Z3FuncDecl, selects: Seq[Z3FuncDecl])

  protected[leon] var unitValue: Z3AST = null
  protected[leon] var intSetMinFun: Z3FuncDecl = null
  protected[leon] var intSetMaxFun: Z3FuncDecl = null

  protected[leon] var arrayMetaDecls: Map[TypeTree, ArrayDecls] = Map.empty
  protected[leon] var tupleMetaDecls: Map[TypeTree, TupleDecls] = Map.empty
  protected[leon] var setCardDecls: Map[TypeTree, Z3FuncDecl] = Map.empty

  protected[leon] var adtTesters: Map[CaseClassType, Z3FuncDecl] = Map.empty
  protected[leon] var adtConstructors: Map[CaseClassType, Z3FuncDecl] = Map.empty
  protected[leon] var adtFieldSelectors: Map[(CaseClassType, Identifier), Z3FuncDecl] = Map.empty

  protected[leon] var reverseADTTesters: Map[Z3FuncDecl, CaseClassType] = Map.empty
  protected[leon] var reverseADTConstructors: Map[Z3FuncDecl, CaseClassType] = Map.empty
  protected[leon] var reverseADTFieldSelectors: Map[Z3FuncDecl, (CaseClassType,Identifier)] = Map.empty
  protected[leon] var reverseTupleConstructors: Map[Z3FuncDecl, TupleType] = Map.empty
  protected[leon] var reverseTupleSelectors: Map[Z3FuncDecl, (TupleType, Int)] = Map.empty

  protected[leon] val mapRangeSorts: MutableMap[TypeTree, Z3Sort] = MutableMap.empty
  protected[leon] val mapRangeSomeConstructors: MutableMap[TypeTree, Z3FuncDecl] = MutableMap.empty
  protected[leon] val mapRangeNoneConstructors: MutableMap[TypeTree, Z3FuncDecl] = MutableMap.empty
  protected[leon] val mapRangeSomeTesters: MutableMap[TypeTree, Z3FuncDecl] = MutableMap.empty
  protected[leon] val mapRangeNoneTesters: MutableMap[TypeTree, Z3FuncDecl] = MutableMap.empty
  protected[leon] val mapRangeValueSelectors: MutableMap[TypeTree, Z3FuncDecl] = MutableMap.empty

  private var counter = 0
  private object nextIntForSymbol {
    def apply(): Int = {
      val res = counter
      counter = counter + 1
      res
    }
  }

  var isInitialized = false
  protected[leon] def initZ3() {
    if (!isInitialized) {
      val initTime     = new Timer().start
      counter = 0

      z3 = new Z3Context(z3cfg)

      functions.clear()
      generics.clear()
      sorts.clear()
      variables.clear()

      arrayMetaDecls = Map()
      tupleMetaDecls = Map()
      setCardDecls   = Map()

      prepareSorts

      isInitialized = true

      initTime.stop
      context.timers.get("Z3Solver init") += initTime
    }
  }

  protected[leon] def restartZ3() {
    isInitialized = false

    initZ3()
  }

  protected[leon] def mapRangeSort(toType : TypeTree) : Z3Sort = mapRangeSorts.get(toType) match {
    case Some(z3sort) => z3sort
    case None => {
      import Z3Context.{ADTSortReference, RecursiveType, RegularSort}

      val z3info = z3.mkADTSorts(
        Seq(
          (
            toType.toString + "Option",
            Seq(toType.toString + "Some", toType.toString + "None"),
            Seq(
              Seq(("value", RegularSort(typeToSort(toType)))),
              Seq()
            )
          )
        )
      )

      z3info match {
        case Seq((optionSort, Seq(someCons, noneCons), Seq(someTester, noneTester), Seq(Seq(valueSelector), Seq()))) =>
          mapRangeSorts += ((toType, optionSort))
          mapRangeSomeConstructors += ((toType, someCons))
          mapRangeNoneConstructors += ((toType, noneCons))
          mapRangeSomeTesters += ((toType, someTester))
          mapRangeNoneTesters += ((toType, noneTester))
          mapRangeValueSelectors += ((toType, valueSelector))
          optionSort
      }
    }
  }

  case class UntranslatableTypeException(msg: String) extends Exception(msg)

  def rootType(ct: ClassType): ClassType = ct.parent match {
    case Some(p) => rootType(p)
    case None => ct
  }

  def declareADTSort(ct: ClassType): Z3Sort = {
    import Z3Context.{ADTSortReference, RecursiveType, RegularSort}

    def getHierarchy(ct: ClassType): (ClassType, Seq[CaseClassType]) = ct match {
      case act: AbstractClassType =>
        (act, act.knownCCDescendents)
      case cct: CaseClassType =>
        cct.parent match {
          case Some(p) =>
            getHierarchy(p)
          case None =>
            (cct, List(cct))
        }
    }

    var newHierarchiesMap = Map[ClassType, Seq[CaseClassType]]()

    def findDependencies(ct: ClassType): Unit = {
      val (root, sub) = getHierarchy(ct)

      if (!(newHierarchiesMap contains root) && !(sorts containsLeon root)) {
        newHierarchiesMap += root -> sub

        // look for dependencies
        for (ct <- root +: sub; f <- ct.fields) f.tpe match {
          case fct: ClassType =>
            findDependencies(fct)
          case _ =>
        }
      }
    }

    // Populates the dependencies of the ADT to define.
    findDependencies(ct)

    val newHierarchies = newHierarchiesMap.toSeq

    val indexMap: Map[ClassType, Int] = Map()++newHierarchies.map(_._1).zipWithIndex

    def typeToSortRef(tt: TypeTree): ADTSortReference = tt match {
      case ct: ClassType if sorts containsLeon rootType(ct) =>
        RegularSort(sorts.toZ3(rootType(ct)))

      case act : AbstractClassType =>
        // It has to be here
        RecursiveType(indexMap(act))

      case cct: CaseClassType => cct.parent match {
        case Some(p) =>
          typeToSortRef(p)
        case None =>
          RecursiveType(indexMap(cct))
      }

      case _=>
        RegularSort(typeToSort(tt))
    }     

    // Define stuff
    val defs = for ((root, childrenList) <- newHierarchies) yield {
      (
       root.id.uniqueName,
       childrenList.map(ccd => ccd.id.uniqueName),
       childrenList.map(ccd => ccd.fields.map(f => (f.id.uniqueName, typeToSortRef(f.tpe))))
      )
    }

    /*for ((n, sub, cstrs) <- defs) {
      println(n+":")
      for ((s,css) <- sub zip cstrs) {
        println("  "+s)
        println("    -> "+css)
      }
    }*/
       
    val resultingZ3Info = z3.mkADTSorts(defs)

    for ((z3Inf, (root, childrenList)) <- (resultingZ3Info zip newHierarchies)) {
      sorts += (root -> z3Inf._1)
      assert(childrenList.size == z3Inf._2.size)
      for ((child, (consFun, testFun)) <- childrenList zip (z3Inf._2 zip z3Inf._3)) {
        adtTesters += (child -> testFun)
        reverseADTTesters += (testFun -> child)
        adtConstructors += (child -> consFun)
        reverseADTConstructors += (consFun -> child)
      }
      for ((child, fieldFuns) <- childrenList zip z3Inf._4) {
        assert(child.fields.size == fieldFuns.size)
        for ((fid, selFun) <- (child.fields.map(_.id) zip fieldFuns)) {
          adtFieldSelectors += ((child, fid) -> selFun)
          reverseADTFieldSelectors += (selFun -> (child, fid))
        }
      }
    }

    sorts.toZ3(ct)
  }

  // Prepares some of the Z3 sorts, but *not* the tuple sorts; these are created on-demand.
  private def prepareSorts: Unit = {
    import Z3Context.{ADTSortReference, RecursiveType, RegularSort}

    val Seq((us, Seq(unitCons), Seq(unitTester), _)) = z3.mkADTSorts(
      Seq(
        (
          "Unit",
          Seq("Unit"),
          Seq(Seq())
        )
      )
    )

    sorts += Int32Type -> z3.mkIntSort
    sorts += RealType -> z3.mkRealSort
    sorts += BooleanType -> z3.mkBoolSort
    sorts += UnitType -> us

    unitValue = unitCons()

    val intSetSort = typeToSort(SetType(Int32Type))
    val intSort    = typeToSort(Int32Type)

    intSetMinFun = z3.mkFreshFuncDecl("setMin", Seq(intSetSort), intSort)
    intSetMaxFun = z3.mkFreshFuncDecl("setMax", Seq(intSetSort), intSort)

    // Empty everything
    adtTesters = Map.empty
    adtConstructors = Map.empty
    adtFieldSelectors = Map.empty
    reverseADTTesters = Map.empty
    reverseADTConstructors = Map.empty
    reverseADTFieldSelectors = Map.empty
  }

  def normalizeType(t: TypeTree): TypeTree = {
    bestRealType(t)
  }

  // assumes prepareSorts has been called....
  protected[leon] def typeToSort(oldtt: TypeTree): Z3Sort = normalizeType(oldtt) match {
    case Int32Type | BooleanType | UnitType | RealType =>
      sorts.toZ3(oldtt)

    case act: AbstractClassType =>
      sorts.toZ3OrCompute(act) {
        declareADTSort(act)
      }

    case cct: CaseClassType =>
      sorts.toZ3OrCompute(cct) {
        declareADTSort(cct)
      }

    case tt @ SetType(base) =>
      sorts.toZ3OrCompute(tt) {
        val newSetSort = z3.mkSetSort(typeToSort(base))

        val card = z3.mkFreshFuncDecl("card", Seq(newSetSort), typeToSort(Int32Type))
        setCardDecls += tt -> card

        newSetSort
      }

    case tt @ MapType(fromType, toType) =>
      sorts.toZ3OrCompute(tt) {
        val fromSort = typeToSort(fromType)
        val toSort = mapRangeSort(toType)

        z3.mkArraySort(fromSort, toSort)
      }

    case tt @ ArrayType(base) =>
      sorts.toZ3OrCompute(tt) {
        val intSort = typeToSort(Int32Type)
        val toSort = typeToSort(base)
        val as = z3.mkArraySort(intSort, toSort)
        val tupleSortSymbol = z3.mkFreshStringSymbol("Array")
        val (ats, atcons, Seq(atsel, atlength)) = z3.mkTupleSort(tupleSortSymbol, as, intSort)

        arrayMetaDecls += tt -> ArrayDecls(atcons, atsel, atlength)

        ats
      }
    case tt @ TupleType(tpes) =>
      sorts.toZ3OrCompute(tt) {
        val tpesSorts = tpes.map(typeToSort)
        val sortSymbol = z3.mkFreshStringSymbol("Tuple")
        val (tupleSort, consTuple, projsTuple) = z3.mkTupleSort(sortSymbol, tpesSorts: _*)

        tupleMetaDecls += tt -> TupleDecls(consTuple, projsTuple)
        reverseTupleConstructors += (consTuple -> tt)
        tupleSort        
      }

    case tt @ TypeParameter(id) =>
      sorts.toZ3OrCompute(tt) {
        val symbol = z3.mkFreshStringSymbol(id.name)
        val newTPSort = z3.mkUninterpretedSort(symbol)

        newTPSort
      }

    case other =>
      sorts.toZ3OrCompute(other) {
        reporter.warning(other.getPos, "Resorting to uninterpreted type for : " + other)
        val symbol = z3.mkIntSymbol(nextIntForSymbol())
        z3.mkUninterpretedSort(symbol)
      }
  }

  protected[leon] def toZ3Formula(expr: Expr, initialMap: Map[Identifier,Z3AST] = Map.empty) : Option[Z3AST] = {

    class CantTranslateException extends Exception

    val varsInformula: Set[Identifier] = variablesOf(expr)

    var z3Vars: Map[Identifier,Z3AST] = if(!initialMap.isEmpty) {
      initialMap
    } else {
      // FIXME TODO pleeeeeeeease make this cleaner. Ie. decide what set of
      // variable has to remain in a map etc.
      variables.leonToZ3.filter(p => p._1.isInstanceOf[Variable]).map(p => (p._1.asInstanceOf[Variable].id -> p._2))
    }

    def rec(ex: Expr): Z3AST = ex match {
      case tu @ Tuple(args) =>
        typeToSort(tu.getType) // Make sure we generate sort & meta info
        val meta = tupleMetaDecls(normalizeType(tu.getType))

        meta.cons(args.map(rec(_)): _*)

      case ts @ TupleSelect(tu, i) =>
        typeToSort(tu.getType) // Make sure we generate sort & meta info
        val meta = tupleMetaDecls(normalizeType(tu.getType))

        meta.selects(i-1)(rec(tu))

      case Let(i, e, b) => {
        val re = rec(e)
        z3Vars = z3Vars + (i -> re)
        val rb = rec(b)
        z3Vars = z3Vars - i
        rb
      }
      case LetTuple(ids, e, b) => {
        var ix = 1
        z3Vars = z3Vars ++ ids.map((id) => {
          val entry = (id -> rec(TupleSelect(e, ix)))
          ix += 1
          entry
        })
        val rb = rec(b)
        z3Vars = z3Vars -- ids
        rb
      }
      case Waypoint(_, e) => rec(e)
      case e @ Error(_) => {
        val tpe = e.getType
        val newAST = z3.mkFreshConst("errorValue", typeToSort(tpe))
        // Might introduce dupplicates (e), but no worries here
        variables += (e -> newAST)
        newAST
      }
      case v @ Variable(id) => z3Vars.get(id) match {
        case Some(ast) => ast
        case None => {
          val newAST = z3.mkFreshConst(id.uniqueName, typeToSort(v.getType))
          z3Vars = z3Vars + (id -> newAST)
          variables += (v -> newAST)
          newAST
        }
      }

      case ite @ IfExpr(c, t, e) => z3.mkITE(rec(c), rec(t), rec(e))
      case And(exs) => z3.mkAnd(exs.map(rec(_)): _*)
      case Or(exs) => z3.mkOr(exs.map(rec(_)): _*)
      case Implies(l, r) => z3.mkImplies(rec(l), rec(r))
      case Iff(l, r) =>
        val rl = rec(l)
        val rr = rec(r)
        z3.mkIff(rl, rr)

      case Not(Iff(l, r)) => z3.mkXor(rec(l), rec(r))
      case Not(Equals(l, r)) => z3.mkDistinct(rec(l), rec(r))
      case Not(e) => z3.mkNot(rec(e))
      case IntLiteral(v) => z3.mkInt(v, typeToSort(Int32Type))
      case RealLiteral(num,denom) => z3.mkReal(num, denom)
      case BooleanLiteral(v) => if (v) z3.mkTrue() else z3.mkFalse()
      case UnitLiteral() => unitValue
      case Equals(l, r) => z3.mkEq(rec( l ), rec( r ) )
      case Plus(l, r) => z3.mkAdd(rec(l), rec(r))
      case Minus(l, r) => z3.mkSub(rec(l), rec(r))
      case Times(l, r) => z3.mkMul(rec(l), rec(r))
      case Division(l, r) => z3.mkDiv(rec(l), rec(r))
      case Modulo(l, r) => z3.mkMod(rec(l), rec(r))
      case UMinus(e) => z3.mkUnaryMinus(rec(e))
      case LessThan(l, r) => z3.mkLT(rec(l), rec(r))
      case LessEquals(l, r) => z3.mkLE(rec(l), rec(r))
      case GreaterThan(l, r) => z3.mkGT(rec(l), rec(r))
      case GreaterEquals(l, r) => z3.mkGE(rec(l), rec(r))
      case c @ CaseClass(ct, args) =>
        typeToSort(ct) // Making sure the sort is defined
        val constructor = adtConstructors(ct)
        constructor(args.map(rec(_)): _*)

      case c @ CaseClassSelector(cct, cc, sel) =>
        typeToSort(cct) // Making sure the sort is defined
        val selector = adtFieldSelectors(cct, sel)
        selector(rec(cc))

      case c @ CaseClassInstanceOf(cct, e) =>
        typeToSort(cct) // Making sure the sort is defined
        val tester = adtTesters(cct)
        tester(rec(e))

      case f @ FunctionInvocation(tfd, args) =>
        z3.mkApp(functionDefToDecl(tfd), args.map(rec(_)): _*)

      case SetEquals(s1, s2) => z3.mkEq(rec(s1), rec(s2))
      case ElementOfSet(e, s) => z3.mkSetSubset(z3.mkSetAdd(z3.mkEmptySet(typeToSort(e.getType)), rec(e)), rec(s))
      case SubsetOf(s1, s2) => z3.mkSetSubset(rec(s1), rec(s2))
      case SetIntersection(s1, s2) => z3.mkSetIntersect(rec(s1), rec(s2))
      case SetUnion(s1, s2) => z3.mkSetUnion(rec(s1), rec(s2))
      case SetDifference(s1, s2) => z3.mkSetDifference(rec(s1), rec(s2))
      case f @ FiniteSet(elems) => elems.foldLeft(z3.mkEmptySet(typeToSort(f.getType.asInstanceOf[SetType].base)))((ast, el) => z3.mkSetAdd(ast, rec(el)))
      case SetCardinality(s) =>
        val rs = rec(s)
        setCardDecls(s.getType)(rs)

      case SetMin(s) => intSetMinFun(rec(s))
      case SetMax(s) => intSetMaxFun(rec(s))
      case f @ FiniteMap(elems) => f.getType match {
        case tpe@MapType(fromType, toType) =>
          typeToSort(tpe) //had to add this here because the mapRangeNoneConstructors was not yet constructed...
          val fromSort = typeToSort(fromType)
          val toSort = typeToSort(toType)
          elems.foldLeft(z3.mkConstArray(fromSort, mapRangeNoneConstructors(toType)())){ case (ast, (k,v)) => z3.mkStore(ast, rec(k), mapRangeSomeConstructors(toType)(rec(v))) }
        case errorType => scala.sys.error("Unexpected type for finite map: " + (ex, errorType))
      }
      case mg @ MapGet(m,k) => m.getType match {
        case MapType(fromType, toType) =>
          val selected = z3.mkSelect(rec(m), rec(k))
          mapRangeValueSelectors(toType)(selected)
        case errorType => scala.sys.error("Unexpected type for map: " + (ex, errorType))
      }
      case MapUnion(m1,m2) => m1.getType match {
        case MapType(ft, tt) => m2 match {
          case FiniteMap(ss) =>
            ss.foldLeft(rec(m1)){
              case (ast, (k, v)) => z3.mkStore(ast, rec(k), mapRangeSomeConstructors(tt)(rec(v)))
            }
          case _ => scala.sys.error("map updates can only be applied with concrete map instances")
        }
        case errorType => scala.sys.error("Unexpected type for map: " + (ex, errorType))
      }
      case MapIsDefinedAt(m,k) => m.getType match {
        case MapType(ft, tt) => z3.mkDistinct(z3.mkSelect(rec(m), rec(k)), mapRangeNoneConstructors(tt)())
        case errorType => scala.sys.error("Unexpected type for map: " + (ex, errorType))
      }
      case fill @ ArrayFill(length, default) =>
        val at @ ArrayType(base) = fill.getType
        typeToSort(at)
        val meta = arrayMetaDecls(normalizeType(at))

        val ar = z3.mkConstArray(typeToSort(base), rec(default))
        val res = meta.cons(ar, rec(length))
        res

      case ArraySelect(a, index) =>
        typeToSort(a.getType)
        val ar = rec(a)
        val getArray = arrayMetaDecls(normalizeType(a.getType)).select
        val res = z3.mkSelect(getArray(ar), rec(index))
        res

      case ArrayUpdated(a, index, newVal) =>
        typeToSort(a.getType)
        val ar = rec(a)
        val meta = arrayMetaDecls(normalizeType(a.getType))

        val store = z3.mkStore(meta.select(ar), rec(index), rec(newVal))
        val res = meta.cons(store, meta.length(ar))
        res

      case ArrayLength(a) =>
        typeToSort(a.getType)
        val ar = rec(a)
        val meta = arrayMetaDecls(normalizeType(a.getType))
        val res = meta.length(ar)
        res

      case arr @ FiniteArray(exprs) => {
        val ArrayType(innerType) = arr.getType
        val arrayType = arr.getType
        val a: Expr = ArrayFill(IntLiteral(exprs.length), simplestValue(innerType)).setType(arrayType)
        val u = exprs.zipWithIndex.foldLeft(a)((array, expI) => ArrayUpdated(array, IntLiteral(expI._2), expI._1).setType(arrayType))
        rec(u)
      }
      case Distinct(exs) => z3.mkDistinct(exs.map(rec(_)): _*)

      case gv @ GenericValue(tp, id) =>
        z3.mkApp(genericValueToDecl(gv))

      case h @ Hole(o) =>
        rec(OracleTraverser(o, h.getType, program).value)

      case _ => {
        reporter.warning(ex.getPos, "Can't handle this in translation to Z3: " + ex)
        throw new CantTranslateException
      }
    }

    try {
      val res = Some(rec(expr))
      res
    } catch {
      case e: CantTranslateException => None
    }
  }
   
  var encodedAsBV = false
  var bvSize = 0
  var maxval = 0
  var maxBits = 800
  
  def computationBits(expr: Expr, varsize : Int) : Int = {
    
    def max(x: Int, y: Int) = if(x >= y) x else y

    def bitsInVal(x: Int): Int = {
      var count = 0;
      var value = x
      while (value > 0) {
        count += 1
        value = value >> 1;
      }
      count
    }

    def rec(expr: Expr): Int = {
      val bits = expr match {
        case IntLiteral(v) => bitsInVal(v)
        //here assuming that denominator is 1
        case RealLiteral(num,dnum) => bitsInVal(num)
        case BooleanLiteral(_) => 1
        case t : Terminal => varsize
        case Plus(a1,a2) =>  max(rec(a1), rec(a2)) + 1
        case Minus(a1,a2) =>  max(rec(a1), rec(a2)) + 1
        case Times(a1,a2) =>  rec(a1) + rec(a2)        
        case UnaryOperator(a, _) => rec(a)
        case BinaryOperator(a1, a2, _) => max(rec(a1), rec(a2))
        case NAryOperator(args, _) => args.tail.foldLeft(rec(args.head))((acc, arg) => max(acc, rec(arg)))
      }
      if(bits > maxBits) {
        //println("Computation requires more than 16bits: "+ expr)
        maxBits        
      } else 
    	 bits
    }
    rec(expr)
  }
  
  /**
   * This converts Integers/reals in the expr to bit vectors of a specified size and 
   * converts the arithmetic operations accordingly
   */  
  protected[leon] def toBitVectorFormula(expr: Expr, bitvecSize: Int) : Option[Z3AST] = {   
    class CantTranslateException extends Exception
   
    val varsInformula: Set[Identifier] = variablesOf(expr)

    var z3Vars: Map[Identifier,Z3AST] = {
      variables.leonToZ3.filter(p => p._1.isInstanceOf[Variable]).map(p => (p._1.asInstanceOf[Variable].id -> p._2))
    }
    
    //set the bit vector flags for later use
    encodedAsBV = true
    bvSize = bitvecSize
    maxval = (1 << (bitvecSize - 1)) - 1
    var lb = IntLiteral(-maxval)
    var ub = IntLiteral(maxval)
    var boundedExpr = And(varsInformula.foldLeft(Seq(expr))((acc, id) => 
      acc ++ Seq(LessEquals(lb, id.toVariable),LessEquals(id.toVariable, ub)))) 
      
   //over-approximate the bits required for the computation
   //this is necessary for soundness
   val cbits = computationBits(expr, bitvecSize)
   reporter.info("Bits required for the computation: "+cbits)
   var bvsort = z3.mkBVSort(cbits)   

    def rec(ex: Expr): Z3AST = { 
      //println("Stacking up call for:")
      //println(ex)
      val recResult = (ex match {        
        case v @ Variable(id) => z3Vars.get(id) match {
          case Some(ast) => {           
            ast
          }
          case None => {
            if (id.getType == Int32Type || id.getType == RealType) {              
              val newAST = z3.mkFreshConst(id.uniqueName, bvsort)
              z3Vars = z3Vars + (id -> newAST)
              variables += (v -> newAST)                            
              //println("Creating a bitvector sort for: "+id+" sort: "+newAST.getSort)
              newAST
            } else {
              reporter.warning("non-numerical variable: "+v)
              throw new CantTranslateException  
            }            
          }
        }
        //logical operations
        case ite @ IfExpr(c, t, e) => z3.mkITE(rec(c), rec(t), rec(e))
        case And(exs) => z3.mkAnd(exs.map(rec(_)): _*)
        case Or(exs) => z3.mkOr(exs.map(rec(_)): _*)
        case Implies(l, r) => z3.mkImplies(rec(l), rec(r))
        case Iff(l, r) => {
          val rl = rec(l)
          val rr = rec(r)
          // z3.mkIff used to trigger a bug
          // z3.mkAnd(z3.mkImplies(rl, rr), z3.mkImplies(rr, rl))
          z3.mkIff(rl, rr)
        }
        case Not(Iff(l, r)) => z3.mkXor(rec(l), rec(r))
        case Not(Equals(l, r)) => z3.mkDistinct(rec(l), rec(r))
        case Not(e) => z3.mkNot(rec(e))
        
        //arithmetic operations
        case IntLiteral(v) => z3.mkNumeral(v.toString, bvsort)
        case rl@RealLiteral(num,denom) => if(denom == 1) {
          val ast = z3.mkNumeral(num.toString, bvsort)
          //println("Converted: "+num+" to "+ast)
          ast
        } else {
          reporter.warning("denominator not one: "+rl)
          throw new CantTranslateException
        }
        case BooleanLiteral(v) => if (v) z3.mkTrue() else z3.mkFalse()        
        case Equals(l, r) => z3.mkEq(rec( l ), rec( r ) )
        case Plus(l, r) => z3.mkBVAdd(rec(l), rec(r))
        case Minus(l, r) => z3.mkBVSub(rec(l), rec(r))
        case Times(l, r) => z3.mkBVMul(rec(l), rec(r))
        case Division(l, r) => z3.mkBVSdiv(rec(l), rec(r))        
        case UMinus(e) => z3.mkBVSub(z3.mkNumeral("0", bvsort), rec(e))
        case LessThan(l, r) => z3.mkBVSlt(rec(l), rec(r))
        case LessEquals(l, r) => z3.mkBVSle(rec(l), rec(r))
        case GreaterThan(l, r) => z3.mkBVSgt(rec(l), rec(r))
        case GreaterEquals(l, r) => z3.mkBVSge(rec(l), rec(r))
          
        case _ => {
          reporter.warning("Can't handle this in translation to Z3: " + ex)
          throw new CantTranslateException
        }
      })
      recResult
    }

    try {
      val res = Some(rec(boundedExpr))
      res
    } catch {
      case e: CantTranslateException => None
    }
  }
  
  //Maximum value of the integers in the extracted model. 
  //Note that this cannot be added as a range  in the presence of real values as the numerators and denominators
  //can be arbitrarily large.
  val maxNumeralVal = Int.MaxValue
  val minNumeralVal = Int.MinValue  
    
  def containedInRange(x: BigInt) : Boolean = {
    (x >= minNumeralVal && x <= maxNumeralVal)
  }   
  
  protected[leon] def fromZ3Formula(model: Z3Model, tree : Z3AST) : Expr = {
    def rec(t: Z3AST): Expr = {
      val kind = z3.getASTKind(t)
      val sort = z3.getSort(t)      

      kind match {
        case Z3NumeralIntAST(None) => {
          val il = IntLiteral(maxNumeralVal)
          il.setOverflow
          il
          //throw IllegalStateException("Encountered Overflow while translation from z3 to Leon AST: value = "+t)
        }
        case Z3NumeralIntAST(Some(v)) => {
          //println("Int AST: "+t+" value: "+v)
          if (encodedAsBV) {
            //need to sign extend the value
            val signMask = maxval + 1
            if ((v & signMask) > 0) {
              //here we need to fill up the higher order bits by '1'
              val newv = v | ~(maxval)
              IntLiteral(newv)

            } else IntLiteral(v)
          } else {
            IntLiteral(v)
          }
        }
        case Z3NumeralRealAST(num: BigInt, dem: BigInt) => {          
          val rl = RealLiteral(num.intValue, dem.intValue)
          if (!containedInRange(num) || !containedInRange(dem)) {                          
              rl.setOverflow(num,dem)              
          }
          rl          
        } 
        case Z3AppAST(decl, args) =>
          val argsSize = args.size
          if(argsSize == 0 && (variables containsZ3 t)) {
            variables.toLeon(t)
          } else if(functions containsZ3 decl) {
            val tfd = functions.toLeon(decl)
            assert(tfd.params.size == argsSize)
            FunctionInvocation(tfd, args.map(rec))
          } else if(argsSize == 1 && (reverseADTTesters contains decl)) {
            val cct = reverseADTTesters(decl)
            CaseClassInstanceOf(cct, rec(args(0)))
          } else if(argsSize == 1 && (reverseADTFieldSelectors contains decl)) {
            val (cct, fid) = reverseADTFieldSelectors(decl)
            CaseClassSelector(cct, rec(args(0)), fid)
          } else if(reverseADTConstructors contains decl) {
            val cct = reverseADTConstructors(decl)
            assert(argsSize == cct.fields.size)
            CaseClass(cct, args.map(rec))
          } else if (generics containsZ3 decl)  {
            generics.toLeon(decl)
          } else if(reverseTupleConstructors contains decl) {
              val TupleType(subTypes) = reverseTupleConstructors(decl)
              val rargs = args.map(rec)
              Tuple(rargs)
          } 
          else {
            sort match {              
              case LeonType(tp: TupleType) =>
                val rargs = args.map(rec)
                Tuple(rargs)
                
              case LeonType(tp: TypeParameter) =>
                val id = t.toString.split("!").last.toInt
                GenericValue(tp, id)              

              case LeonType(at @ ArrayType(dt)) =>
                assert(args.size == 2)
                val IntLiteral(length) = rec(args(1))
                model.getArrayValue(args(0)) match {
                  case None => throw new CantTranslateException(t)
                  case Some((map, elseZ3Value)) =>
                    val elseValue = rec(elseZ3Value)
                    var valuesMap = map.map { case (k,v) =>
                      val IntLiteral(index) = rec(k)
                      (index -> rec(v))
                    }

                    FiniteArray(for (i <- 1 to length) yield {
                      valuesMap.getOrElse(i, elseValue)
                    }).setType(at)
                }

              case LeonType(tpe @ MapType(kt, vt)) =>
                model.getArrayValue(t) match {
                  case None => throw new CantTranslateException(t)
                  case Some((map, elseZ3Value)) =>
                    var values = map.toSeq.map { case (k, v) => (k, z3.getASTKind(v)) }.collect {
                      case (k, Z3AppAST(cons, arg :: Nil)) if cons == mapRangeSomeConstructors(vt) =>
                        (rec(k), rec(arg))
                    }

                    FiniteMap(values).setType(tpe)
                }

              case LeonType(tpe @ SetType(dt)) =>
                model.getSetValue(t) match {
                  case None => throw new CantTranslateException(t)
                  case Some(set) =>
                    val elems = set.map(e => rec(e))
                    FiniteSet(elems.toSeq).setType(tpe)
                }

              case LeonType(UnitType) =>
                UnitLiteral()

              case _ =>
                import Z3DeclKind._
                val rargs = args.map(rec(_))
                z3.getDeclKind(decl) match {
                  case OpTrue =>    BooleanLiteral(true)
                  case OpFalse =>   BooleanLiteral(false)
                  case OpEq =>      Equals(rargs(0), rargs(1))
                  case OpITE =>     IfExpr(rargs(0), rargs(1), rargs(2))
                  case OpAnd =>     And(rargs)
                  case OpOr =>      Or(rargs)
                  case OpIff =>     Iff(rargs(0), rargs(1))
                  case OpXor =>     Not(Iff(rargs(0), rargs(1)))
                  case OpNot =>     Not(rargs(0))
                  case OpImplies => Implies(rargs(0), rargs(1))
                  case OpLE =>      LessEquals(rargs(0), rargs(1))
                  case OpGE =>      GreaterEquals(rargs(0), rargs(1))
                  case OpLT =>      LessThan(rargs(0), rargs(1))
                  case OpGT =>      GreaterThan(rargs(0), rargs(1))
                  case OpAdd =>     Plus(rargs(0), rargs(1))
                  case OpSub =>     Minus(rargs(0), rargs(1))
                  case OpUMinus =>  UMinus(rargs(0))
                  case OpMul =>     Times(rargs(0), rargs(1))
                  case OpDiv =>     Division(rargs(0), rargs(1))
                  case OpIDiv =>    Division(rargs(0), rargs(1))
                  case OpMod =>     Modulo(rargs(0), rargs(1))
                  case other =>
                    System.err.println("Don't know what to do with this sort : " + sort)
                    System.err.println("The arguments are : " + args)
                    throw new CantTranslateException(t)
                }
            }
          }
        case _ =>
          System.err.println("Can't handle "+t+" kind: "+kind+" sort: "+sort)
          throw new CantTranslateException(t)
      }
    }
    rec(tree)
  }
  
  // Tries to convert a Z3AST into a *ground* Expr. Doesn't try very hard, because
  //   1) we assume Z3 simplifies ground terms, so why match for +, etc, and
  //   2) we use this precisely in one context, where we know function invocations won't show up, etc.
  protected[leon] def asGround(tree : Z3AST) : Option[Expr] = {
    val e = new Exception("Not ground.")

    def rec(t : Z3AST) : Expr = z3.getASTKind(t) match {
      case Z3AppAST(decl, args) => {
        val argsSize = args.size
        if(functions containsZ3 decl) {
          val tfd = functions.toLeon(decl)
          FunctionInvocation(tfd, args.map(rec))
        } else if(argsSize == 1 && reverseADTTesters.isDefinedAt(decl)) {
          val cct = reverseADTTesters(decl)
          CaseClassInstanceOf(cct, rec(args(0)))
        } else if(argsSize == 1 && reverseADTFieldSelectors.isDefinedAt(decl)) {
          val (cct, fid) = reverseADTFieldSelectors(decl)
          CaseClassSelector(cct, rec(args(0)), fid)
        } else if(reverseADTConstructors.isDefinedAt(decl)) {
          val cct = reverseADTConstructors(decl)
          CaseClass(cct, args.map(rec))
        } else {
          z3.getSort(t) match {
            case LeonType(t : TupleType) =>
              Tuple(args.map(rec))

            case _ =>
              import Z3DeclKind._
              z3.getDeclKind(decl) match {
                case OpTrue => BooleanLiteral(true)
                case OpFalse => BooleanLiteral(false)
                case _ => throw e
              }
          }
        }
      }
      case Z3NumeralIntAST(Some(v)) => IntLiteral(v)
      case _ => throw e
    }

    try {
      Some(rec(tree))
    } catch {
      case e : Exception => None
    }
  }

  protected[leon] def softFromZ3Formula(model: Z3Model, tree : Z3AST) : Option[Expr] = {
    try {
      Some(fromZ3Formula(model, tree))
    } catch {
      case e: CantTranslateException => None
    }
  }

  def idToFreshZ3Id(id: Identifier): Z3AST = {
    z3.mkFreshConst(id.uniqueName, typeToSort(id.getType))
  }

}
