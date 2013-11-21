package leon
package smtlib

import purescala._
import Common._
import Trees._
import Extractors._
import TreeOps._
import TypeTrees._
import Definitions._
import SExprs._
import leon.invariant.ExpressionTransformer

/** This pretty-printer prints an SMTLIB2 representation of the Purescala program */
class HornClausePrinter(pgm: Program) {

  private var errorConstants: Set[(SSymbol, SExpr)] = Set()
  private var classDecls = Seq[SExpr]()  
  private var funDecls = Seq[SExpr]()
  
  //a mapping from tuple types to ADTs
  private var tts = Map[TupleType, CaseClassDef]()
  //a mapping from functions to relational version of the function
  private var funs = Map[FunDef, FunDef]()

  def toSmtLib: String = {
    errorConstants = Set()

    //get all user defined classes
    val defs: Seq[ClassTypeDef] = pgm.definedClasses
    
    //partition them into parent, children
    val partition: Seq[(ClassTypeDef, Seq[CaseClassDef])] = {
      val parents: Seq[ClassTypeDef] = defs.filter(!_.hasParent)
      parents.map(p => (p, defs.filter(_.isInstanceOf[CaseClassDef]).filter(c => c.parent match {
        case Some(p2) => p == p2
        case None => p == c //here parent and children are the same class
      }).asInstanceOf[Seq[CaseClassDef]]))
    }
    //create a smtlib class decl for each parent-children pair
    partition.foreach{ case (parent, children) => classDeclToSmtlibDecl(parent, children)}
    
    //create new function declarations 
    //More new functions can be added while processing functions
    pgm.definedFunctions.foreach((fd) => {
      if(fd.returnType == BooleanType) funs += (fd -> fd)
      else {
       if(fd.returnType == UnitType) 
         throw new IllegalStateException("Return Type of function is unit: "+fd.id)
       
       val newfd = new FunDef(FreshIdentifier(fd.id.name,true),BooleanType, 
           fd.args :+ VarDecl(FreshIdentifier("res", true).setType(fd.returnType), fd.returnType))
       funs += (fd -> newfd)
      }
    })  
        
    //new tuple types could be discovered in the program while processing functions    
    val convertedFunDefs = pgm.definedFunctions.foldLeft(Seq[SExpr]())((acc, fd) => acc ++ fd2sexp(fd))
       
    val sortDecls = classDecls.map((clsdec) => SList(SSymbol("declare-datatypes"), SList(), clsdec))
    //println(sortDecls)
    val funDecls = funs.values.map(getFunDecl(_))
    
    val sortErrors: List[SExpr] = errorConstants.map(p => SList(SSymbol("declare-const"), p._1, p._2)).toList  

    (
      SComment("! THEORY=1") +:
      SComment("Automatically Generated") +:
      ( sortDecls ++
        funDecls ++
        sortErrors ++
        convertedFunDefs ++
        Seq(SList(SSymbol("check-sat")))
      )
    ).map(SExprs.toString(_)).mkString("\n")

  }
  
  def getFunDecl(fd: FunDef) : SExpr ={
    val name = id2sym(fd.id)
    val returnSort = tpe2sort(fd.returnType)
    val varDecls: List[(SSymbol, SExpr)] = fd.args.map(vd => (id2sym(vd.id), tpe2sort(vd.tpe))).toList
    SList(SSymbol("declare-fun"), name, SList(varDecls.map(_._2) ::: List(returnSort)), SSymbol("Bool"))
  }

  /**
   * Given the parent classdef (abstractClassDef) and the children classDef (CaseClassDef)
   * adds a smtlib class def to the classDecl  
   */
  private def classDeclToSmtlibDecl(parent: ClassTypeDef, children: Seq[CaseClassDef])  = {
    val name = id2sym(parent.id)
    val constructors: List[SExpr] = children.map(child => {
      val fields: List[SExpr] = child.fields.map { case VarDecl(id, tpe) => SList(id2sym(id), tpe2sort(tpe)) }.toList
      if (fields.isEmpty) id2sym(child.id) else SList(id2sym(child.id) :: fields)
    }).toList

    classDecls :+= SList(name :: constructors)
  }


  private def id2sym(id: Identifier) = SSymbol(id.uniqueName)

  /**
   * Create a new smtlib datatype for the input tuple.
   * This will add the newly created type to classDecls
   */  
  private def tupleTypeToCaseClassDef(tt: TupleType) : CaseClassDef = {
    if(tts.contains(tt)) tts(tt)
    else {
      //create a tuple field name for each field      
      val tupleFields = tt.bases.map((tpe: TypeTree) => VarDecl(FreshIdentifier("tuple-field", true).setType(tpe), tpe))                
      val ccd = new CaseClassDef(FreshIdentifier("Tuple", true), None)
      ccd.fields = tupleFields
      
      //add ccd to smtlib decls
      classDeclToSmtlibDecl(ccd, Seq(ccd))
      
      //add ccd to tts
      tts += (tt -> ccd)
      ccd
    }
  }

  def tpe2sort(tpe: TypeTree): SExpr = tpe match {
    case Int32Type => SSymbol("Int")
    case BooleanType => SSymbol("Bool")
    case ArrayType(baseTpe) => SList(SSymbol("Array"), SSymbol("Int"), tpe2sort(baseTpe))
    case AbstractClassType(abs) => id2sym(abs.id)
    case CaseClassType(cc) => id2sym(cc.id)
    case tt@TupleType(_) => {
      val cc = tupleTypeToCaseClassDef(tt)
      id2sym(cc.id)
    }
    case _ => sys.error("TODO tpe2sort: " + tpe)
  }

  //return a series of declarations, an expression that defines the function, 
  //and the seq of asserts for pre/post-conditions
  private def fd2sexp(fd: FunDef): Seq[SExpr] = {
    val resid = FreshIdentifier("res",true).setType(fd.returnType)
    val resvar = resid.toVariable
    var implications = Seq[Expr]()
    //flattenBody         
    if (fd.hasBody) {
      //convert the body to a horn expression with an auxiliary set of implications 
      val (hornBody, imps) = leonToHorn(Equals(resvar, fd.body.get))
      implications ++= imps      
      //create an implication that (hornBody => fdRel)
      val bodyRel = FunctionInvocation(funs(fd),fd.args.map(_.id.toVariable) :+ resvar)
      implications :+= Implies(hornBody, bodyRel)
      
      if(fd.hasPostcondition) {
        val (r,pexpr) = fd.postcondition.get
        val postExpr = replace(Map(r.toVariable -> resvar), pexpr)
        
        //create relations for post-conditon as in the case of body
        val (hornPost, imps) = leonToHorn(postExpr)
        implications ++= imps      
        //create an implication that (hornPost => postRel)
        val postFd = createNewRelation(funs(fd).args.map(_.tpe))
        val postRel = FunctionInvocation(postFd,fd.args.map(_.id.toVariable) :+ resvar)
        implications :+= Implies(hornPost, postRel)
        
        if(fd.hasPrecondition) {
          //do the same as body and post
          val (hornPre, imps) = leonToHorn(fd.precondition.get)
          implications ++= imps      
          //create an implication that (hornPost => postRel)
          val preFd = createNewRelation(fd.args.map(_.tpe))
          val preRel = FunctionInvocation(preFd,fd.args.map(_.id.toVariable))
          implications :+= Implies(hornPre, preRel)
          
          //add pre ^ body => post
          implications :+= Implies(And(preRel, bodyRel), postRel)
        } else {
          //add body => post
          implications :+= Implies(bodyRel, postRel)
        }        
      }      
      //convert leon implications to SExprs
      implications.map((imp) => {
        //convert implication to sexpr
        val impbody = exp2sexp(imp)
        //quantify over all free variables in 'imp'
        val freesyms = variablesOf(imp).map((id) => SList(List(id2sym(id), tpe2sort(id.getType)))).toList        
        SList(SSymbol("forall"), SList(freesyms), impbody)
      })
      
    } else {
      //no body
      throw new IllegalArgumentException("Warning no body found for : "+fd.id)
    }    
  }

  
  private def createNewRelation(types : Seq[TypeTree]) : FunDef = {
    val newRel = new FunDef(FreshIdentifier("P",true), BooleanType, 
        types.map((tpe) => VarDecl(FreshIdentifier("arg",true).setType(tpe),tpe)))
    funs += (newRel -> newRel)
    newRel
  }

  //converts an arbitrary leon expression to a horn clause
  private def leonToHorn(expr: Expr) : (Expr, Seq[Expr]) = {
    val simpExpr = matchToIfThenElse(expr)
    //here we are again pushing nots introduced by the normalizeExpr call inside.
    val flatExpr = ExpressionTransformer.TransformNot(ExpressionTransformer.normalizeExpr(simpExpr))
    //replace all the function calls by predicates given by 'funs'
    val rel = simplePostTransform((e: Expr) => e match {
      case Equals(r @ Variable(_), fi @ FunctionInvocation(fd, args)) => FunctionInvocation(funs(fd), args :+ r)
      case _ => e
    })(flatExpr)    
    formulaToHorn(rel)
  }
  
  //converts a flattened, nnf formula to Horn clauses by creating new realtions for disjunctions
  private def formulaToHorn(expr: Expr) : (Expr, Seq[Expr]) ={
    
    var implications = Seq[Expr]() 
    val hornBody = simplePostTransform((e: Expr) => e match {
      case Or(args) => {
        //get free variables of args
        val freevars = args.foldLeft(Seq[Identifier]())((acc,arg) => acc ++ variablesOf(arg))
        //create new Relation
        val  newRel = createNewRelation(freevars.map(_.getType))
        val relApp = FunctionInvocation(newRel, freevars.map(_.toVariable))
        
        //here, create a bunch of implications
        implications ++= args.map((arg) => Implies(arg, relApp))                
        relApp
      }
      case _ => e
    })(expr)
    (hornBody, implications)
  }
  
  def exp2sexp(tree: Expr): SExpr = tree match {
    case Variable(id) => id2sym(id)
    case LetTuple(ids,d,e) => {
      //convert LetTuple to Lets
      val rootid = FreshIdentifier("lt",true).setType(d.getType)
      val rootvar = rootid.toVariable
      val ts = ids.size
      val subexpr = ids.foldRight(e)((id, acc) => {
        Let(id, TupleSelect(rootvar, ts), acc)
      })      
      exp2sexp(Let(rootid, d, subexpr))
    }
    case Let(b,d,e) => SList(
      SSymbol("let"),
      SList(
        SList(id2sym(b), exp2sexp(d))
      ),
      exp2sexp(e)
    )
    case And(exprs) => SList(SSymbol("and") :: exprs.map(exp2sexp).toList)
    case Or(exprs) => SList(SSymbol("or") :: exprs.map(exp2sexp).toList)
    case Not(expr) => SList(SSymbol("not"), exp2sexp(expr))
    case Equals(l,r) => SList(SSymbol("="), exp2sexp(l), exp2sexp(r))
    case IntLiteral(v) => SInt(v)
    case BooleanLiteral(v) => SSymbol(v.toString) //TODO: maybe need some builtin type here
    case StringLiteral(s) => SString(s)

    case Implies(l,r) => SList(SSymbol("=>"), exp2sexp(l), exp2sexp(r))
    case Iff(l,r) => SList(SSymbol("="), exp2sexp(l), exp2sexp(r))

    case Plus(l,r) => SList(SSymbol("+"), exp2sexp(l), exp2sexp(r))
    case UMinus(expr) => SList(SSymbol("-"), exp2sexp(expr))
    case Minus(l,r) => SList(SSymbol("-"), exp2sexp(l), exp2sexp(r))
    case Times(l,r) => SList(SSymbol("*"), exp2sexp(l), exp2sexp(r))
    case Division(l,r) => SList(SSymbol("div"), exp2sexp(l), exp2sexp(r))
    case Modulo(l,r) => SList(SSymbol("mod"), exp2sexp(l), exp2sexp(r))
    case LessThan(l,r) => SList(SSymbol("<"), exp2sexp(l), exp2sexp(r))
    case LessEquals(l,r) => SList(SSymbol("<="), exp2sexp(l), exp2sexp(r))
    case GreaterThan(l,r) => SList(SSymbol(">"), exp2sexp(l), exp2sexp(r))
    case GreaterEquals(l,r) => SList(SSymbol(">="), exp2sexp(l), exp2sexp(r))

    case IfExpr(c, t, e) => SList(SSymbol("ite"), exp2sexp(c), exp2sexp(t), exp2sexp(e))

    case FunctionInvocation(fd, args) => SList(id2sym(fd.id) :: args.map(exp2sexp).toList)

    case ArrayFill(length, defaultValue) => SList(
      SList(SSymbol("as"), SSymbol("const"), tpe2sort(tree.getType)),
      exp2sexp(defaultValue)
    )
    case ArrayMake(defaultValue) => SList(
      SList(SSymbol("as"), SSymbol("const"), tpe2sort(tree.getType)),
      exp2sexp(defaultValue)
    )
    case ArraySelect(array, index) => SList(SSymbol("select"), exp2sexp(array), exp2sexp(index))
    case ArrayUpdated(array, index, newValue) => SList(SSymbol("store"), exp2sexp(array), exp2sexp(index), exp2sexp(newValue))

    case CaseClass(ccd, args) => SList(id2sym(ccd.id) :: args.map(exp2sexp(_)).toList)
    case tp@Tuple(args) => {
      val ccd = tupleTypeToCaseClassDef(tp.getType.asInstanceOf[TupleType])
      SList(id2sym(ccd.id) :: args.map(exp2sexp(_)).toList)
    }
    
    case CaseClassSelector(_, arg, field) => SList(id2sym(field), exp2sexp(arg))
    case TupleSelect(arg, index) => {
      val ccd = tupleTypeToCaseClassDef(arg.getType.asInstanceOf[TupleType])
      //get field at index 'index'
      val field = ccd.fieldsIds(index - 1)
      SList(id2sym(field), exp2sexp(arg)) 
    }

    case CaseClassInstanceOf(ccd, arg) => {
      val name = id2sym(ccd.id)
      val testerName = SSymbol("is-" + name.s)
      SList(testerName, exp2sexp(arg))
    }

    case er@Error(_) => {
      val id = id2sym(FreshIdentifier("error_value").setType(er.getType))
      errorConstants += ((id, tpe2sort(er.getType)))
      id
    }

    case o => sys.error("TODO converting to smtlib: " + o)
  }
}
