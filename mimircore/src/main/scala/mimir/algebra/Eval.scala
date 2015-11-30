package mimir.algebra;

import mimir.algebra.Type._
import mimir.ctables.{VGTerm, CTables}

object Eval 
{

  val SAMPLE_COUNT = 100

  /**
   * Evaluate the specified expression and cast the result to an Long
   */
  def evalInt(e: Expression) =
    eval(e).asLong
  /**
   * Evaluate the specified expression and cast the result to a String
   */
  def evalString(e: Expression) =
    eval(e).asString
  /**
   * Evaluate the specified expression and cast the result to a Double
   */
  def evalFloat(e: Expression) =
    eval(e).asDouble
  /**
   * Evaluate the specified expression and cast the result to a Boolean
   */
  def evalBool(e: Expression, bindings: Map[String, PrimitiveValue] = Map[String, PrimitiveValue]()): Boolean =
    eval(e, bindings) match {
      case BoolPrimitive(v) => v

      /* TODO Need to check if this is allowed? */
      case v: NullPrimitive => false

      case v => throw new TypeException(TBool, v.exprType, "Cast")
    }
  /**
   * Evaluate the specified expression and return the primitive value
   */
  def eval(e: Expression): PrimitiveValue = 
    eval(e, Map[String, PrimitiveValue]())

  /**
   * Evaluate the specified expression given a set of Var/Value bindings
   * and return the primitive value of the result
   */
  def eval(e: Expression, 
           bindings: Map[String, PrimitiveValue]
  ): PrimitiveValue = 
  {
    if(e.isInstanceOf[PrimitiveValue]){
      return e.asInstanceOf[PrimitiveValue]
    } else {
      e match {
        case Var(v) => bindings.get(v).get
        case Arithmetic(op, lhs, rhs) =>
          applyArith(op, eval(lhs, bindings), eval(rhs, bindings))
        case Comparison(op, lhs, rhs) =>
          applyCmp(op, eval(lhs, bindings), eval(rhs, bindings))
        case CaseExpression(caseWhens, caseElse) =>
          caseWhens.foldLeft(None: Option[PrimitiveValue])( (a, b) =>
            if(a.isDefined){ a }
            else {
              if(eval(b.when, bindings).
                    asInstanceOf[BoolPrimitive].v){
                Some(eval(b.then, bindings));
              } else { None }
            }
          ).getOrElse(eval(caseElse, bindings))
        case Not(NullPrimitive()) => NullPrimitive()
        case Not(c) => BoolPrimitive(!evalBool(c, bindings))
        case p:Proc => {
          p.get(p.getArgs.map(eval(_, bindings)))
        }
        case IsNullExpression(c) => {
          val isNull: Boolean = 
            eval(c, bindings).
            isInstanceOf[NullPrimitive];
          return BoolPrimitive(isNull);
        }
        case Function(op, params) => {
          op match {
            case "JOIN_ROWIDS" => new RowIdPrimitive(params.map(x => eval(x).asString).mkString("."))
            case "DATE" =>
              val date = params.head.asInstanceOf[StringPrimitive].v.split("-").map(x => x.toInt)
              new DatePrimitive(date(0), date(1), date(2))
            case "__LIST_MIN" =>
              new FloatPrimitive(params.map(x => {
                try {
                  eval(x).asDouble
                } catch {
                  case e:Throwable => Double.MaxValue
                }
              }).min) // TODO Generalized Comparator
            case "__LIST_MAX" =>
              new FloatPrimitive(params.map(x => {
                try {
                  eval(x).asDouble
                } catch {
                  case e:Throwable => Double.MinValue
                }
              }).max) // TODO Generalized Comparator
            case "__LEFT_UNION_ROWID" =>
              new RowIdPrimitive(eval(params(0)).asString+".left")
            case "__RIGHT_UNION_ROWID" =>
              new RowIdPrimitive(eval(params(0)).asString+".right")
            case CTables.ROW_PROBABILITY => {
              var count = 0.0
              for(i <- 0 until SAMPLE_COUNT) {
                val bindings = Map[String, IntPrimitive]("__SEED" -> IntPrimitive(i+1))
                if(Eval.evalBool(params(0), bindings))
                  count += 1
              }
              FloatPrimitive(count/SAMPLE_COUNT)
            }
            case CTables.VARIANCE => {
              var variance = 0.0
              try {
                val (sum, samples) = sampleExpression(params(0))
                val mean = sum/SAMPLE_COUNT
                for(i <- samples.keys){
                  variance += (i - mean) * (i - mean) * samples(i)
                }
                FloatPrimitive(variance/SAMPLE_COUNT)
              } catch {
                case e: TypeException => new NullPrimitive()
              }
            }
            case CTables.CONFIDENCE => {
              var variance = 0.0
              try {
                val (_, samples) = sampleExpression(params(0))
                val percentile = params(1).asInstanceOf[PrimitiveValue].asDouble
                val keys = samples.keys.toList.sorted
                var count = 0
                var i = -1
                while(count < percentile){
                  i += 1
                  count += samples(keys(i))
                }
                val med = keys(i)
                for(i <- samples.keys){
                  variance += (i - med) * (i - med) * samples(i)
                }
                val conf = Math.sqrt(variance/SAMPLE_COUNT) * 1.96
                StringPrimitive((med - conf).formatted("%.2f") + " | " + (med + conf).formatted("%.2f"))
              } catch {
                case e: TypeException => new NullPrimitive()
              }
            }
          }
        }
      }
    }
  }

  def sampleExpression(exp: Expression): (Double, collection.mutable.Map[Double, Int]) = {
    var sum  = 0.0
    val samples = collection.mutable.Map[Double, Int]()
    for( i <- 0 until SAMPLE_COUNT) {
      val bindings = Map[String, IntPrimitive]("__SEED" -> IntPrimitive(i+1))
      val sample =
        try {
          Eval.eval(exp, bindings).asDouble
        } catch {
          case e: Exception => 0.0
        }
      sum += sample
      if(samples.contains(sample))
        samples(sample) = samples(sample) + 1
      else
        samples += (sample -> 1)
    }
    (sum, samples)
  }

  /**
   * Apply one level of simplification to the passed expression.  Typically
   * this method should not be used directly, but is invoked as part of
   * either inline() method.
   *
   * If the expression is independent of VGTerms or variable references
   * then the expression can be deterministically evaluated outright.
   * In this case, simplify() returns the PrimitiveValue that the 
   * expression evaluates to.  
   *
   * CASE statements are simplified further.  See simplifyCase()
   */
  def simplify(e: Expression): Expression = {
    // println("Simplify: "+e)
    if(ExpressionUtils.getColumns(e).isEmpty && 
       !CTables.isProbabilistic(e)) 
    { 
      try {
        eval(e) 
      } catch {
        case _:MatchError => 
          e.rebuild(e.children.map(simplify(_)))
      }
    } else e match { 
      case CaseExpression(wtClauses, eClause) =>
        simplifyCase(List(), wtClauses, eClause)
      case _ => e.rebuild(e.children.map(simplify(_)))
    }
  }

  /**
   * Recursive method that optimizes case statements.
   *
   * - WHEN clauses that are deterministically false are dropped from 
   *   the case expression. 
   * - WHEN clauses that are deterministically true become else clauses
   *   and all following WHEN and ELSE clauses are dropped.
   * - If the ELSE clause is the only remaining clause in the expression,
   *   it replaces the entire CASE expression.
   */
  def simplifyCase(wtSimplified: List[WhenThenClause], 
                   wtTodo: List[WhenThenClause], 
                   eClause: Expression): Expression =
    wtTodo match {
      case WhenThenClause(w, t) :: wtRest =>
        if(w.isInstanceOf[BoolPrimitive]){
          if(w.asInstanceOf[BoolPrimitive].v){

            // If the when condition is deterministically true, then
            // we can turn the current then statement into an else
            // branch and finish here.  For the sake of keeping all
            // of the reconstruction code in one place, we recur into
            // a terminal leaf.
            simplifyCase(wtSimplified, List(), t)
          } else {

            // If the when condition is deterministically false, then
            // we strip the current clause out of the to-do list and
            // recur as normal.
            simplifyCase(wtSimplified, wtRest, eClause)
          }
        } else {

          // If the when condition is neither deterministically true,
          // nor false, we add it on the "finished" list and then recur
          // as normal.
          simplifyCase(wtSimplified ++ List(WhenThenClause(w,t)), 
                       wtRest, eClause)
        }

      case _ => // empty list

        // If none of the when clauses can possibly be triggered, we
        // always fall through to the else clause.
        if(wtSimplified.isEmpty){ eClause }
        else {
          //otherwise, we rebuild the case statement
          CaseExpression(wtSimplified, eClause)
        }
    }

  /**
   * Thoroughly inline an expression, recursively applying simplify()
   * at levels, to all subtrees of the expression.
   */
  def inline(e: Expression): Expression = 
    inline(e, Map[String, Expression]())
  /**
   * Apply a given variable binding to the specified expression, and then
   * thoroughly inline it, recursively applying simplify() at all levels,
   * to all subtrees of the expression
   */
  def inline(e: Expression, bindings: Map[String, Expression]):
    Expression = 
  {
    e match {
      case Var(v) => bindings.get(v).getOrElse(Var(v))
      case _ => 
        simplify( e.rebuild( e.children.map( inline(_, bindings) ) ) )

    }
  }
  
  /**
   * Perform arithmetic on two primitive values.
   */
  def applyArith(op: Arith.Op, 
            a: PrimitiveValue, b: PrimitiveValue
  ): PrimitiveValue = {
    if(a.isInstanceOf[NullPrimitive] || 
       b.isInstanceOf[NullPrimitive]){
      NullPrimitive()
    } else {
      (op, Arith.computeType(op, a.exprType, b.exprType)) match { 
        case (Arith.Add, TInt) => 
          IntPrimitive(a.asLong + b.asLong)
        case (Arith.Add, TFloat) => 
          FloatPrimitive(a.asDouble + b.asDouble)
        case (Arith.Sub, TInt) => 
          IntPrimitive(a.asLong - b.asLong)
        case (Arith.Sub, TFloat) => 
          FloatPrimitive(a.asDouble - b.asDouble)
        case (Arith.Mult, TInt) => 
          IntPrimitive(a.asLong * b.asLong)
        case (Arith.Mult, TFloat) => 
          FloatPrimitive(a.asDouble * b.asDouble)
        case (Arith.Div, (TFloat|TInt)) => 
          FloatPrimitive(a.asDouble / b.asDouble)
        case (Arith.And, TBool) => 
          BoolPrimitive(
            a.asInstanceOf[BoolPrimitive].v &&
            b.asInstanceOf[BoolPrimitive].v
          )
        case (Arith.Or, TBool) => 
          BoolPrimitive(
            a.asInstanceOf[BoolPrimitive].v ||
            b.asInstanceOf[BoolPrimitive].v
          )
      }
    }
  }

  /**
   * Perform a comparison on two primitive values.
   */
  def applyCmp(op: Cmp.Op, 
            a: PrimitiveValue, b: PrimitiveValue
  ): PrimitiveValue = {
    if(a.isInstanceOf[NullPrimitive] || 
       b.isInstanceOf[NullPrimitive]){
      NullPrimitive()
    } else {
      Cmp.computeType(op, a.exprType, b.exprType)
      op match { 
        case Cmp.Eq => 
          BoolPrimitive(a.payload.equals(b.payload))
        case Cmp.Neq => 
          BoolPrimitive(!a.payload.equals(b.payload))
        case Cmp.Gt => 
          Arith.escalateNumeric(a.exprType, b.exprType) match {
            case TInt => BoolPrimitive(a.asLong > b.asLong)
            case TFloat => BoolPrimitive(a.asDouble > b.asDouble)
            case TDate => 
              BoolPrimitive(
                a.asInstanceOf[DatePrimitive].
                 compare(b.asInstanceOf[DatePrimitive])<0
              )
          }
        case Cmp.Gte => 
          Arith.escalateNumeric(a.exprType, b.exprType) match {
            case TInt => BoolPrimitive(a.asLong >= b.asLong)
            case TFloat => BoolPrimitive(a.asDouble >= b.asDouble)
            case TDate => 
              BoolPrimitive(
                a.asInstanceOf[DatePrimitive].
                 compare(b.asInstanceOf[DatePrimitive])<=0
              )
            case TBool => BoolPrimitive(a match {
              case BoolPrimitive(true) => true
              case BoolPrimitive(false) => {
                b match {
                  case BoolPrimitive(true) => false
                  case _ => true
                }
              }
            })
          }
        case Cmp.Lt => 
          Arith.escalateNumeric(a.exprType, b.exprType) match {
            case TInt => BoolPrimitive(a.asLong < b.asLong)
            case TFloat => BoolPrimitive(a.asDouble < b.asDouble)
            case TDate => 
              BoolPrimitive(
                a.asInstanceOf[DatePrimitive].
                 compare(b.asInstanceOf[DatePrimitive])>0
              )
          }
        case Cmp.Lte => 
          Arith.escalateNumeric(a.exprType, b.exprType) match {
            case TInt => BoolPrimitive(a.asLong <= b.asLong)
            case TFloat => BoolPrimitive(a.asDouble <= b.asDouble)
            case TDate => 
              BoolPrimitive(
                a.asInstanceOf[DatePrimitive].
                 compare(b.asInstanceOf[DatePrimitive])>=0
              )
          }
      }
    }
  }

  def getVGTerms(e: Expression): List[VGTerm] = {
    getVGTerms(e, Map(), List())
  }

  def getVGTerms(e: Expression,
                 bindings: Map[String, PrimitiveValue],
                 l: List[VGTerm]): List[VGTerm] = {
    if(e.isInstanceOf[PrimitiveValue]){
      l
    } else {
      l ++ (
        e match {
          case Var(v) => getVGTerms(bindings.get(v).get, bindings, l)
          case Arithmetic(_, lhs, rhs) =>
            getVGTerms(lhs, bindings, l) ++ getVGTerms(rhs, bindings, l)
          case Comparison(_, lhs, rhs) =>
            getVGTerms(lhs, bindings, l) ++ getVGTerms(rhs, bindings, l)
          case v: VGTerm => (v :: l) ++ v.args.flatMap(arg => getVGTerms(arg, bindings, l))
          case CaseExpression(caseWhens, caseElse) =>
            caseWhens.foldLeft(None: Option[List[VGTerm]])((a, b) =>
              if (a.isDefined) {
                a
              }
              else {
                if (eval(b.when, bindings).asInstanceOf[BoolPrimitive].v) {
                  Some(getVGTerms(b.when, bindings, l) ++ getVGTerms(b.then, bindings, l))
                } else {
                  None
                }
              }
            ).getOrElse(getVGTerms(caseElse, bindings, l))

          case Not(c) => getVGTerms(c, bindings, l)
          case IsNullExpression(c) => getVGTerms(c, bindings, l)
          case _ => List()
        }
      )
    }
  }
}