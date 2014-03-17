package leon
package invariant

import scala.util.Random
import z3.scala._
import purescala.Common._
import purescala.Definitions._
import purescala.Trees._
import purescala.TreeOps._
import purescala.Extractors._
import purescala.TypeTrees._
import solvers.{ Solver, TimeoutSolver }
import solvers.z3.FairZ3Solver
import leon.evaluators._
import java.io._
import leon.solvers.z3.UninterpretedZ3Solver
import leon.LeonContext
import leon.LeonOptionDef
import leon.LeonPhase
import leon.LeonValueOption
import leon.ListValue
import leon.Reporter
import leon.verification.DefaultTactic
import leon.verification.ExtendedVC
import leon.verification.Tactic
import leon.verification.VerificationReport
import leon.solvers.SimpleSolverAPI
import leon.solvers.z3.UIFZ3Solver
import leon.invariant._
import leon.purescala.UndirectedGraph
import scala.util.control.Breaks._
import leon.solvers._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import leon.purescala.ScalaPrinter
import leon.plugin.NonlinearityEliminationPhase

class AxiomFactory(ctx : LeonContext, program : Program) {                       
  
  val tru = BooleanLiteral(true)
  //Add more axioms here, if necessary
  var commuCalls = Set[Call]()
  def hasUnaryAxiom(call: Call) : Boolean = {
    //important: here we need to avoid applying commutativity on the calls produced by axioms instantiation
    (FunctionInfoFactory.isCommutative(call.fi.funDef) && !commuCalls.contains(call)) 
  }
  
  var distriCalls = Set[Call]()
  def hasBinaryAxiom(call: Call) : Boolean = {
	FunctionInfoFactory.isMonotonic(call.fi.funDef) ||
		(FunctionInfoFactory.isDistributive(call.fi.funDef) && !distriCalls.contains(call))
  }
  
  def unaryAxiom(call: Call) : Expr = {
    val callee = call.fi.funDef
    if(FunctionInfoFactory.isCommutative(callee)) {
      //note: commutativity is defined only for binary operations
      val Seq(a1, a2) = call.fi.args
      val newret = TVarFactory.createTemp("cm").toVariable
      val newfi = FunctionInvocation(callee,Seq(a2,a1))
      val newcall = Call(newret,newfi)
      
      //note: calls added by this axiom cannot be again considered by this axiom
      commuCalls += newcall
      
      And(newcall.toExpr, Equals(newret, call.retexpr))
    } else 
      throw IllegalStateException("Call does not have unary axiom: "+call)
  }

  def binaryAxiom(call1: Call, call2: Call): Expr = {

    if (call1.fi.funDef.id != call2.fi.funDef.id)
      throw IllegalStateException("Instantiating binary axiom on calls to different functions: " + call1 + "," + call2)

    if (!hasBinaryAxiom(call1))
      throw IllegalStateException("Call does not have binary axiom: " + call1)

    val callee = call1.fi.funDef    
    //monotonicity
    val axiom1 = if (FunctionInfoFactory.isMonotonic(callee)) {
      monotonizeCalls(call1, call2)
    } else tru
    
    //distributivity
    val axiom2 = if (FunctionInfoFactory.isDistributive(callee)) {
      undistributeCalls(call1, call2)
    } else tru
    
    And(Seq(axiom1, axiom2))
  }
  
  def monotonizeCalls(call1: Call, call2: Call): Expr = {    
    val ant = (call1.fi.args zip call2.fi.args).foldLeft(Seq[Expr]())((acc, pair) => {
      val lesse = LessEquals(pair._1, pair._2)
      lesse +: acc
    })
    val conseq = LessEquals(call1.retexpr, call2.retexpr)
    Implies(And(ant), conseq)    
  }
   
  //this is applicable only to binary operations
  def undistributeCalls(call1: Call, call2: Call): Expr = {    
    val fd = call1.fi.funDef
    val Seq(a1,b1) = call1.fi.args
    val Seq(a2,b2) = call2.fi.args
    val r1 = call1.retexpr
    val r2 = call2.retexpr
    
    val dret1 = TVarFactory.createTemp("dt").toVariable
    val dret2 = TVarFactory.createTemp("dt").toVariable
    val dcall1 = Call(dret1, FunctionInvocation(fd,Seq(a2,Plus(b1,b2))))
    val dcall2 = Call(dret2, FunctionInvocation(fd,Seq(Plus(a1,a2),b2)))
    
    distriCalls ++= Set(dcall1, dcall2)    
    
    val axiom1 = Implies(LessEquals(a1,a2), And(LessEquals(Plus(r1,r2),dret1), dcall1.toExpr)) 
    val axiom2 = Implies(LessEquals(b1,b2), And(LessEquals(Plus(r1,r2),dret2), dcall2.toExpr))
    And(axiom1,axiom2) 
  }
}