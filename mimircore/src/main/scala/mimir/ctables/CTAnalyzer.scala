package mimir.ctables

import mimir.algebra._

object CTAnalyzer {

  /**
   * Construct a boolean expression that evaluates whether the input 
   * expression is deterministic <b>for a given input row</b>.  
   * Note the corner-case here: CASE (aka branch) statements can
   * create some tuples that are deterministic and others that are
   * non-deterministic (depending on which branch is taken).  
   *
   * Base cases: <ul>
   *   <li>VGTerm is false</li>
   *   <li>Var | Const is true</li>
   * </ul>
   * 
   * Everything else (other than CASE) is an AND of whether the 
   * child subexpressions are deterministic
   */
  def compileDeterministic(expr: Expression): Expression =
    compileDeterministic(expr, Map[String,Expression]())


  def compileDeterministic(expr: Expression, 
                           varMap: Map[String,Expression]): Expression =
  {
    val recur = (x:Expression) => compileDeterministic(x, varMap)
    expr match { 
      
      case CaseExpression(caseClauses, elseClause) =>
        CaseExpression(
          caseClauses.map( (clause) =>
            WhenThenClause(
              clause.when,
              Arith.makeAnd(recur(clause.then), recur(clause.when))
            )
          ),
          recur(elseClause)
        )

      case Arithmetic(Arith.And, l, r) =>
        Arith.makeOr(
          Arith.makeAnd(
            recur(l),
            Not(l)
          ),
          Arith.makeAnd(
            recur(r),
            Not(r)
          )
        )
      
      case Arithmetic(Arith.Or, l, r) =>
        Arith.makeOr(
          Arith.makeAnd(
            recur(l),
            l
          ),
          Arith.makeAnd(
            recur(r),
            r
          )
        )

      case _: VGTerm =>
        BoolPrimitive(false)
      
      case Var(v) => 
        varMap.get(v).getOrElse(BoolPrimitive(true))
      
      case _ => expr.children.
                  map( recur(_) ).
                  fold(
                    BoolPrimitive(true)
                  )( 
                    Arith.makeAnd(_,_) 
                  )
    }
  }

  def compileSample(exp: Expression, seedExp: Expression = null): Expression = {
    exp match {
        case Not(child) => Not(compileSample(child, seedExp))

        case VGTerm((_,model),idx,args) => {
          if(seedExp == null)
            model.sampleGenExpr(idx, args)
          else model.sampleGenExpr(idx, args ++ List(seedExp))
        }

        case CaseExpression(wtClauses, eClause) => {
          var wt = List[WhenThenClause]()
          for(i <- wtClauses.indices){
            val wclause = compileSample(wtClauses(i).when,seedExp)
            val tclause = compileSample(wtClauses(i).then, seedExp)
            wt ::= WhenThenClause(wclause, tclause)
          }
          CaseExpression(wt, compileSample(eClause, seedExp))
        }

        case Arithmetic(o, l, r) => Arithmetic(o, compileSample(l, seedExp), compileSample(r, seedExp))

        case Comparison(o, l, r) => Comparison(o, compileSample(l, seedExp), compileSample(r, seedExp))

        case Var(a) => Var(a)

        case IsNullExpression(child, neg) => IsNullExpression(compileSample(child, seedExp), neg)

        case p: PrimitiveValue => p

        case Function(op, params) => Function(op, params.map( a => compileSample(a, seedExp)))
    }
  }
}