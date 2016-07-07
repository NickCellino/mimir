package mimir.lenses

import java.sql.SQLException

import mimir.Database
import mimir.algebra.Type.T
import mimir.algebra._
import mimir.ctables._
import mimir.exec.ResultIterator
import mimir.util.TypeUtils

class TypeInferenceLens(name: String, args: List[Expression], source: Operator)
  extends Lens(name, args, source) {

  var orderedSourceSchema: List[(String,Type.T)] = null
  var inferenceModel: Model = null
  var db: Database = null

  val model = new TypeCastModel(this)

  def sourceSchema() = {
    if(orderedSourceSchema == null){
      orderedSourceSchema =
        source.schema.map( _ match { case (n,t) => (n.toUpperCase,t) } )
    }
    orderedSourceSchema
  }

  def schema(): List[(String, Type.T)] =
    inferenceModel.asInstanceOf[TypeInferenceModel].inferredTypeMap.map( x => (x._1, x._2))

  def allKeys() = { sourceSchema.map(_._1) }

  def lensType = "TYPE_INFERENCE"

  /**
   * `view` emits an Operator that defines the Virtual C-Table for the lens
   */
  override def view: Operator = {
    Project(
      allKeys().
        zipWithIndex.
          map{ case (k, i) =>
            ProjectArg(
              k,
              Function(
                "CAST",
                List(Var(k), VGTerm((name, inferenceModel), i, List()))
              )
            )
          },
      source
    )
  }

  /**
   * Initialize the lens' model by building it from scratch.  Typically this involves
   * using `db` to evaluate `source`
   */
  override def build(db: Database): Unit = {
    this.db = db
    val results = db.query(source)

    inferenceModel = new TypeInferenceModel(this)
    inferenceModel.asInstanceOf[TypeInferenceModel].init(results)
  }

  override def createBackingStore: Unit = {}
}

class TypeInferenceModel(lens: TypeInferenceLens) extends Model
{
  var inferredTypeMap = List[(String, Type.T, Double)]()
  var threshold: Double = lens.args.head.asInstanceOf[FloatPrimitive].asDouble

  class TypeInferrer {
    private val votes =
      scala.collection.mutable.Map(Type.TInt -> 0,
                                    Type.TFloat -> 0,
                                    Type.TDate -> 0,
                                    Type.TBool -> 0)
    private var totalVotes = 0
    def detectAndVoteType(v: String): Unit = {
      if(v != null) {
        if(v.matches("(\\+|-)?([0-9]+)"))
          votes(Type.TInt) += 1
        if(v.matches("(\\+|-)?([0-9]*(\\.[0-9]+))"))
          votes(Type.TFloat) += 1
        if(v.matches("[0-9]{4}\\-[0-9]{2}\\-[0-9]{2}"))
          votes(Type.TDate) += 1
        if(v.matches("(?i:true|false)"))
          votes(Type.TBool) += 1
      }
      else {
        votes.foreach{ case (t, v) => votes(t) += 1 }
      }

      totalVotes += 1
    }
    def infer(): (Type.T, Double) = {
      if(totalVotes == 0)
        return (Type.TString, 0)

      val max = votes.maxBy(_._2)
      val ratio: Double = max._2.toFloat / totalVotes
      if(ratio >= threshold) {
        (max._1, ratio)
      } else {
        (Type.TString, 0)
      }
    }
  }


  def init(data: ResultIterator): Unit = {
    inferredTypeMap = learn(lens.sourceSchema(), data.allRows())
  }

  def learn(sch: List[(String, T)],
                 data: List[List[PrimitiveValue]]): List[(String, T, Double)] = {

    /**
     * Count votes for each type
     */
    val inferClasses =
      sch.map{ case(k, t) => (k, new TypeInferrer)}

    data.foreach( (row) =>
      sch.indices.foreach( (i) =>
        inferClasses(i)._2.detectAndVoteType(
          try{
            row(i).asString
          } catch {
            case e:TypeException => ""
          }
        )
      )
    )

    /**
     * Now infer types
     */
    inferClasses.map{
      case(k, inferClass) =>
        val inferred = inferClass.infer()
        (k, inferred._1, inferred._2)
    }
  }

  // Model Implementation
  override def varTypes: List[T] = {
    List.fill(lens.schema().length)(Type.TString)
  }

  override def sample(seed: Long, idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    mostLikelyValue(idx, args)
  }

  override def sampleGenerator(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    mostLikelyValue(idx, args)
  }

  override def mostLikelyValue(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    TypePrimitive(inferredTypeMap(idx)._2)
  }

  override def upperBoundExpr(idx: Int, args: List[Expression]): Expression = {
    new TypeInferenceAnalysis(this, idx, args)
  }

  override def upperBound(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    mostLikelyValue(idx, args)
  }

  override def sampleGenExpr(idx: Int, args: List[Expression]): Expression = {
    new TypeInferenceAnalysis(this, idx, args)
  }

  override def mostLikelyExpr(idx: Int, args: List[Expression]): Expression = {
    new TypeInferenceAnalysis(this, idx, args)
  }

  override def lowerBoundExpr(idx: Int, args: List[Expression]): Expression = {
    new TypeInferenceAnalysis(this, idx, args)
  }

  override def lowerBound(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    mostLikelyValue(idx, args)
  }

  override def reason(idx: Int, args: List[Expression]): (String) = {
    val percentage = (inferredTypeMap(idx)._3 * 100).round

    if(percentage == 0) {
      "I assumed that the type of " + inferredTypeMap(idx)._1 +
        " is string"
    } else {
      "I assumed that the type of " + inferredTypeMap(idx)._1 +
        " is " + Type.toString(inferredTypeMap(idx)._2) +
        " with " + percentage.toString + "% of the data conforming to the expected type"
    }
  }

  override def backingStore(idx: Int): String = ???

  override def createBackingStore(idx: Int): Unit = ???

  override def createBackingStore(): Unit = {}
}

case class TypeInferenceAnalysis(model: TypeInferenceModel,
                                 idx: Int,
                                 args: List[Expression])
extends Proc(args) {

  def get(args: List[PrimitiveValue]): PrimitiveValue = {
    model.mostLikelyValue(idx, args)
  }
  def getType(bindings: List[Type.T]) = model.varTypes(idx)
  def rebuild(c: List[Expression]) = new TypeInferenceAnalysis(model, idx, c)

}

class TypeCastModel(lens: TypeInferenceLens) extends Model {

  def cast(v: String, t: T): PrimitiveValue = {
    try {
      t match {
        case Type.TBool =>
          new BoolPrimitive(v.toBoolean)

        case Type.TDate => {
          val (y, m, d) = parseDate(v)
          new DatePrimitive(y, m, d)
        }

        case Type.TFloat =>
          new FloatPrimitive(v.toDouble)

        case Type.TInt =>
          new IntPrimitive(v.toInt)

        case _ =>
          new StringPrimitive(v)
      }
    } catch {
      case _: Exception => new NullPrimitive
      // TODO More can be done for coercion here
    }
  }

  def parseDate(date: String): (Int, Int, Int) = {
    val split = date.split("-")
    (split(0).toInt, split(1).toInt, split(2).toInt)
  }

  def getValue(idx: Int, rowid: PrimitiveValue): PrimitiveValue =
  {
    val rowValues = lens.db.query(
      CTPercolator.percolate(
        Select(
          Comparison(Cmp.Eq, Var("ROWID_MIMIR"), rowid),
          lens.source
        )
      )
    )
    if(!rowValues.getNext()){
      throw new SQLException("Invalid Source Data ROWID: '" +rowid+"'")
    }

    rowValues(idx+1)
  }

  override def varTypes: List[T] =
    lens.inferenceModel.asInstanceOf[TypeInferenceModel].inferredTypeMap.unzip3._2

  override def sample(seed: Long, idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    if(args.length == 0)
      lens.inferenceModel.mostLikelyValue(idx, args)
    else
      mostLikelyValue(idx, args)
  }

  override def sampleGenerator(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    if(args.length == 0)
      lens.inferenceModel.mostLikelyValue(idx, args)
    else
      mostLikelyValue(idx, args)
  }

  override def mostLikelyValue(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    if(args.isEmpty)
      lens.inferenceModel.mostLikelyValue(idx, args)
    else {
      cast(
        try {
          getValue(idx, args.head).asString
        } catch {
          case e: TypeException => return new NullPrimitive
        },
        TypeUtils.convert(
          args(2).asInstanceOf[StringPrimitive].v
        )
      )
    }
  }

  override def upperBoundExpr(idx: Int, args: List[Expression]): Expression =
    new TypeCastAnalysis(this, idx, args)

  override def upperBound(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    if(args.length == 0)
      lens.inferenceModel.mostLikelyValue(idx, args)
    else
      mostLikelyValue(idx, args)
  }

  override def sampleGenExpr(idx: Int, args: List[Expression]): Expression =
    new TypeCastAnalysis(this, idx, args)

  override def mostLikelyExpr(idx: Int, args: List[Expression]): Expression =
    new TypeCastAnalysis(this, idx, args)

  override def lowerBoundExpr(idx: Int, args: List[Expression]): Expression =
    new TypeCastAnalysis(this, idx, args)

  override def lowerBound(idx: Int, args: List[PrimitiveValue]): PrimitiveValue = {
    if(args.length == 0)
      lens.inferenceModel.mostLikelyValue(idx, args)
    else
      mostLikelyValue(idx, args)
  }

  override def reason(idx: Int, args: List[Expression]): (String) = {

    if(args.isEmpty) {
      lens.inferenceModel.reason(idx, args)
    }
    else {
      val mlv = mostLikelyValue(idx, args.map((x) => Eval.eval(x)))

      if (mlv.isInstanceOf[NullPrimitive])
        "I could not find an appropriate " +
          Type.toString(varTypes(idx)) +
          " value for " +
          getValue(idx, Eval.eval(args.head)) +
          ", so I replaced it with NULL"
      else
        "I cast the value " +
          getValue(idx, Eval.eval(args.head)) +
          " with type string to " +
          mlv + " with type " + Type.toString(varTypes(idx))  
    }
  }

  override def backingStore(idx: Int): String = ???

  override def createBackingStore(idx: Int): Unit = {}

  override def createBackingStore(): Unit = {}
}

case class TypeCastAnalysis(model: TypeCastModel,
                                 idx: Int,
                                 args: List[Expression])
  extends Proc(args) {

  def get(args: List[PrimitiveValue]): PrimitiveValue = {
    model.mostLikelyValue(idx, args)
  }
  def getType(bindings: List[Type.T]) = model.varTypes(idx)
  def rebuild(c: List[Expression]) = new TypeCastAnalysis(model, idx, c)

}