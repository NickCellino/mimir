package mimir;

import java.io._

import scala.collection.mutable.ListBuffer

import org.rogach.scallop._

import mimir.parser._
import mimir.sql._
import mimir.util.ExperimentalOptions
import mimir.util.TimeUtils
import net.sf.jsqlparser.statement.Statement
//import net.sf.jsqlparser.statement.provenance.ProvenanceStatement
import net.sf.jsqlparser.statement.select.Select
import mimir.gprom.algebra.OperatorTranslation
import org.gprom.jdbc.jna.GProMWrapper
import py4j.GatewayServer
import mimir.provenance.Provenance
import net.sf.jsqlparser.statement.select.SelectBody
import mimir.algebra._
import mimir.optimizer.InlineVGTerms
import mimir.ctables.VGTerm
import mimir.models.Model

/**
 * The primary interface to Mimir.  Responsible for:
 * - Parsing and processing command line arguments.
 * - Initializing internal state (Database())
 * - Providing a command-line prompt (if appropriate)
 * - Invoking MimirJSqlParser and dispatching the 
 *   resulting statements to Database()
 *
 * Database() handles all of the logical dispatching,
 * Mimir provides a friendly command-line user 
 * interface on top of Database()
 */
object MimirVizier {

  var conf: MimirConfig = null;
  var db: Database = null;
  var usePrompt = true;
  var pythonMimirCallListeners = Seq[PythonMimirCallInterface]()

  def main(args: Array[String]) {
    conf = new MimirConfig(args);

    // Prepare experiments
    ExperimentalOptions.enable(conf.experimental())
    
    // Set up the database connection(s)
    db = new Database(/*new SparkSQLBackend())*/new JDBCBackend(conf.backend(), conf.dbname()))//new GProMBackend(conf.backend(), conf.dbname(), -1))    
    db.backend.open()

    db.initializeDBForMimir();

    if(ExperimentalOptions.isEnabled("INLINE-VG")){
        db.backend.asInstanceOf[JDBCBackend/*SparkSQLBackend*/].enableInlining(db)
      }
    
    runServerForViztrails()
    
    db.backend.close()
    if(!conf.quiet()) { println("\n\nDone.  Exiting."); }
  }
  
  def runServerForViztrails() : Unit = {
    val server = new GatewayServer(this, 33388)
     server.addListener(new py4j.GatewayServerListener(){
        def connectionError(connExept : java.lang.Exception) = {
          println("Python GatewayServer connectionError: " + connExept)
        }
  
        def connectionStarted(conn : py4j.Py4JServerConnection) = {
          println("Python GatewayServer connectionStarted: " + conn)
        }
        
        def connectionStopped(conn : py4j.Py4JServerConnection) = {
          println("Python GatewayServer connectionStopped: " + conn)
        }
        
        def serverError(except: java.lang.Exception) = {
          println("Python GatewayServer serverError")
        }
        
        def serverPostShutdown() = {
           println("Python GatewayServer serverPostShutdown")
        }
        
        def serverPreShutdown() = {
           println("Python GatewayServer serverPreShutdown")
        }
        
        def serverStarted() = {
           println("Python GatewayServer serverStarted")
        }
        
        def serverStopped() = {
           println("Python GatewayServer serverStopped")
        }
     })
     server.start()
     
     while(true){
       Thread.sleep(90000)
       if(pythonCallThread != null){
         //println("Python Call Thread Stack Trace: ---------v ")
         //pythonCallThread.getStackTrace.foreach(ste => println(ste.toString()))
       }
       pythonMimirCallListeners.foreach(listener => {
       
          //println(listener.callToPython("knock knock, jvm here"))
         })
     }
     
    
  }
  
  //-------------------------------------------------
  //Python package defs
  ///////////////////////////////////////////////
  var pythonCallThread : Thread = null
  def loadCSV(file : String) : String = {
    pythonCallThread = Thread.currentThread()
    val timeRes = time {
      println("loadCSV: From Vistrails: [" + file + "]") ;
      val csvFile = new File(file)
      val tableName = (csvFile.getName().split("\\.")(0) + "_RAW").toUpperCase
      if(db.getAllTables().contains(tableName)){
        println("loadCSV: From Vistrails: Table Already Exists: " + tableName)
      }
      else{
        db.loadTable(csvFile)
      }
      tableName 
    }
    println(s"loadCSV Took: ${timeRes._2}")
    timeRes._1
  }
  
  def createLens(input : Any, params : Seq[String], _type : String, materialize_input:Boolean) : String = {
    pythonCallThread = Thread.currentThread()
    val timeRes = time {
      println("createLens: From Vistrails: [" + input + "] [" + params.mkString(",") + "] [" + _type + "]"  ) ;
      val paramsStr = params.mkString(",")
      val lenseName = "LENS_" + ((input.toString() + _type + paramsStr + materialize_input).hashCode().toString().replace("-", "") )
      var query:String = null
      db.getView(lenseName) match {
        case None => {
          if(materialize_input){
            val materializedInput = "MATERIALIZED_"+input
            query = s"CREATE LENS ${lenseName} AS SELECT * FROM ${materializedInput} WITH ${_type}(${paramsStr})"  
            if(db.getAllTables().contains(materializedInput)){
                println("createLens: From Vistrails: Materialized Input Already Exists: " + materializedInput)
            }
            else{  
              val inputQuery = s"SELECT * FROM ${input}"
              val oper = db.sql.convert(db.parse(inputQuery).head.asInstanceOf[Select])
              //val virtOper = db.compiler.virtualize(oper, db.compiler.standardOptimizations)
              db.selectInto(materializedInput, oper)//virtOper.query)
            }
          }
          else{
            val inputQuery = s"SELECT * FROM ${input}"
            query = s"CREATE LENS ${lenseName} AS $inputQuery WITH ${_type}(${paramsStr})"  
          }
          println("createLens: query: " + query)
          db.update(db.parse(query).head)    
        }
        case Some(_) => {
          println("createLens: From Vistrails: Lens already exists: " + lenseName)
        }
      }
      lenseName
    }
    println(s"createLens ${_type} Took: ${timeRes._2}")
    timeRes._1
  }
  
  def createView(input : Any, query : String) : String = {
    pythonCallThread = Thread.currentThread()
    val timeRes = time {
      println("createView: From Vistrails: [" + input + "] [" + query + "]"  ) ;
      val inputSubstitutionQuery = query.replaceAll("\\{\\{\\s*input\\s*\\}\\}", input.toString) 
      val viewName = "VIEW_" + ((input.toString() + query).hashCode().toString().replace("-", "") )
      db.getView(viewName) match {
        case None => {
          val viewQuery = s"CREATE VIEW $viewName AS $inputSubstitutionQuery"  
          println("createView: query: " + viewQuery)
          db.update(db.parse(viewQuery).head)
        }
        case Some(_) => {
          println("createView: From Vistrails: View already exists: " + viewName)
        }
      }
      viewName
    }
    println(s"createView Took: ${timeRes._2}")
    timeRes._1
  }
  
  def vistrailsQueryMimir(query : String, includeUncertainty:Boolean, includeReasons:Boolean) : PythonCSVContainer = {
    val timeRes = time {
      println("vistrailsQueryMimir: " + query)
      val oper = db.sql.convert(db.parse(query).head.asInstanceOf[Select])
      if(includeUncertainty && includeReasons)
        operCSVResultsDeterminismAndExplanation(oper)
      else if(includeUncertainty)
        operCSVResultsDeterminism(oper)
      else 
        operCSVResults(oper)
    }
    println(s"vistrailsQueryMimir Took: ${timeRes._2}")
    timeRes._1
  }
  
  def explainCell(query: String, col:Int, row:Int) : String = {
    val timeRes = time {
      println("explainCell: From Vistrails: [" + col + "] [ "+ row +" ] [" + query + "]"  ) ;
      val oper = db.sql.convert(db.parse(query).head.asInstanceOf[Select])
      val cols = oper.schema.map(f => f._1)
      //db.explainCell(oper, RowIdPrimitive(row.toString()), cols(col)).toString()
      db.explainer.explainSubset(oper, Set(cols(col)), false, false).mkString(",\n")
    }
    println(s"explainCell Took: ${timeRes._2}")
    timeRes._1
  }
  
  def explainRow(query: String, row:Int) : String = {
    val timeRes = time {
      println("explainRow: From Vistrails: [ "+ row +" ] [" + query + "]"  ) ;
      val oper = db.sql.convert(db.parse(query).head.asInstanceOf[Select])
      val cols = oper.schema.map(f => f._1)
      db.explainRow(oper, RowIdPrimitive(row.toString())).toString()
    }
    println(s"explainRow Took: ${timeRes._2}")
    timeRes._1
  }
  
  def feedbackCell(query: String, col:Int, row:Int, feedback:String) : String = {
    val timeRes = time {
      println("acknoledgeCell: From Vistrails: [" + col + "] [ "+ row +" ] [" + query + "]"  ) ;
      val oper = db.sql.convert(db.parse(query).head.asInstanceOf[Select])
      val cols = oper.schema.map(f => f._1)
      val token = RowIdPrimitive(row.toString())
      val column = cols(col)
      try {
  		  val (tuple, allExpressions, _) = db.explainer.getProvenance(oper, token)
    		val expr = allExpressions.get(column).get
    		val colType = Typechecker.typeOf(InlineVGTerms(expr), tuple.mapValues( _.getType ))
        val colMap = allExpressions.keys.zip(tuple.keys).toMap
        val feedBackModels = getModelsToFeedbackFor(expr, tuple)
        for(model <- feedBackModels){
          model.feedback(0, List(), tuple(colMap(column)))
        }
      } catch {
				case x:RAException => "could not feedback for cell"
  				
			}
      
     ""
    }
    println(s"explainCell Took: ${timeRes._2}")
    timeRes._1
  }
  
  def getModelsToFeedbackFor(expr: Expression, tuple: Map[String,PrimitiveValue]) : Seq[Model] = {
    expr match {
			case v: VGTerm => Seq(v.model)
			
			case Conditional(c, t, e) =>
				(
					if(Eval.evalBool(c, tuple)){
						getModelsToFeedbackFor(t, tuple)
					} else {
						getModelsToFeedbackFor(e, tuple)
					}
				) ++ getModelsToFeedbackFor(c, tuple);

			case Arithmetic(op @ (Arith.And | Arith.Or), a, b) =>
				(
					(op, Eval.evalBool(InlineVGTerms.inline(a), tuple)) match {
						case (Arith.And, true) => getModelsToFeedbackFor(b, tuple)
						case (Arith.Or, false) => getModelsToFeedbackFor(b, tuple)
						case _ => Seq()
					}
				) ++ getModelsToFeedbackFor(a, tuple)

			case _ => 
				expr.children.
					map( getModelsToFeedbackFor(_, tuple) ).flatten
		}
  }
  
  def registerPythonMimirCallListener(listener : PythonMimirCallInterface) = {
    println("registerPythonMimirCallListener: From Vistrails: ") ;
    pythonMimirCallListeners = pythonMimirCallListeners.union(Seq(listener))
  }
  
  def getAvailableLenses() : String = {
    db.lenses.lensTypes.keySet.toSeq.mkString(",")
  }
  
  def operCSVResults(oper : mimir.algebra.Operator) : PythonCSVContainer =  {
    val results = db.query(oper)
    val cols = results.schema.map(f => f._1)
    val colsIndexes = results.schema.zipWithIndex.map( _._2)
    val resCSV = cols.mkString(", ") + "\n" + results.mapRows(row => {
      colsIndexes.map( (i) => {
         row(i).toString 
       }).mkString(", ")
    }).mkString("\n")  
    new PythonCSVContainer(resCSV, Array[Array[Boolean]](), Array[Boolean](), Array[Array[String]]())
  }
  
 def operCSVResultsDeterminism(oper : mimir.algebra.Operator) : PythonCSVContainer =  {
     val results = db.query(oper)
     val cols = results.schema.map(f => f._1)
     val colsIndexes = results.schema.zipWithIndex.map( _._2)
     val resCSV = results.mapRows(row => {
       val truples = colsIndexes.map( (i) => {
         (row(i).toString, row.deterministicCol(i)) 
       }).unzip
       (truples._1.mkString(", "), truples._2.toArray, row.deterministicRow())
     }).unzip3
     new PythonCSVContainer(resCSV._1.mkString(cols.mkString(", ") + "\n", "\n", ""), resCSV._2.toArray, resCSV._3.toArray, Array[Array[String]]())
  }
 
 def operCSVResultsDeterminismAndExplanation(oper : mimir.algebra.Operator) : PythonCSVContainer =  {
     val results = db.query(oper)
     val cols = results.schema.map(f => f._1)
     val colsIndexes = results.schema.zipWithIndex.map( _._2)
     val resCSV = results.mapRows(row => {
       val truples = colsIndexes.map( (i) => {
         (row(i).toString, row.deterministicCol(i), if(!row.deterministicCol(i))db.explainCell(oper, row.provenanceToken(), cols(i)).reasons.mkString(",")else"") 
       }).unzip3
       (truples._1.mkString(", "), (truples._2.toArray, row.deterministicRow()), truples._3.toArray)
     }).unzip3
     val detLists = resCSV._2.unzip
     println(resCSV._3.map(s=>s.mkString(",")).mkString(","))
     new PythonCSVContainer(resCSV._1.mkString(cols.mkString(", ") + "\n", "\n", ""), detLists._1.toArray, detLists._2.toArray, resCSV._3.toArray)
  }
 
 def time[F](anonFunc: => F): (F, Long) = {  
      val tStart = System.nanoTime()
      val anonFuncRet = anonFunc  
      val tEnd = System.nanoTime()
      (anonFuncRet, tEnd-tStart)
    }  
}

//----------------------------------------------------------

trait PythonMimirCallInterface {
	def callToPython(callStr : String) : String
}

class PythonCSVContainer(val csvStr: String, val colsDet: Array[Array[Boolean]], val rowsDet: Array[Boolean], val celReasons:Array[Array[String]]){}
