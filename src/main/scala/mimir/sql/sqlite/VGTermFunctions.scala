package mimir.sql.sqlite

import java.sql.SQLException
import com.typesafe.scalalogging.slf4j.LazyLogging

import mimir.algebra._
import mimir.ctables._
import mimir.Database
import mimir.sql.inlining.InliningFunctions


class BestGuessVGTerm(db:Database)
  extends MimirFunction
  with LazyLogging
{
  override def xFunc(): Unit = 
  {
    try {
      val modelName = value_text(0).toUpperCase
      val idx = value_int(1)
      val guess = InliningFunctions.bestGuessVGTerm(db, value_mimir)(modelName, idx)
      return_mimir(guess)
    } catch {
      case e:Throwable => {
        println(e)
        e.printStackTrace
        throw new SQLException("ERROR IN BEST_GUESS_VGTERM()", e)
      }
    }
  }

}

object VGTermFunctions 
{

  def bestGuessVGTermFn = "BEST_GUESS_VGTERM"

  def register(db: Database, conn: java.sql.Connection): Unit =
  {
    org.sqlite.Function.create(conn, bestGuessVGTermFn, new BestGuessVGTerm(db))
    FunctionRegistry.registerNative(
      bestGuessVGTermFn, 
      (args) => { throw new SQLException("Mimir Cannot Execute VGTerm Functions Internally") },
      (_) => TAny()
    )
  }

  def specialize(e: Expression): Expression = {
    e match {
      case VGTerm(model, idx, args, hints) => 
        Function(
          bestGuessVGTermFn, 
          List(StringPrimitive(model.name), IntPrimitive(idx))++
            args.map(specialize(_))++
            hints.map(specialize(_))
        )
      case _ => e.recur(specialize(_))
    }
  }

  def specialize(o: Operator): Operator =
    o.recur(specialize(_)).recurExpressions(specialize(_))
}