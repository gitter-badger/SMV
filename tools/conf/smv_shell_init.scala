import org.apache.spark.sql.functions._
import org.tresamigos.smv._

// create the init object "i" rather than create initialization at top level
// because shell would launch a separate command for each evalutaion which
// slows down startup considerably.
// keeping object name short to make the contents easy to access.
SmvApp.init(Seq("-m", "None").toArray, Option(sc))

object i {
  import org.apache.spark.sql.DataFrame
  import org.apache.spark.rdd.RDD
  import java.io.{File, PrintWriter}

  val app = SmvApp.app
  val sqlContext = app.sqlContext

  //-------- some helpful functions
  def smvSchema(df: DataFrame) = SmvSchema.fromDataFrame(df)

  def df(ds: SmvDataSet) = {
    app.resolveRDD(ds)
  }

  // deprecated, should use df instead!!!
  def s(ds: SmvDataSet) = df(ds)

  /** open file using full path */
  def open(path: String, ca: CsvAttributes = null) ={
    /** isFullPath = true to avoid prepending data_dir */
    val file = SmvCsvFile(path, ca, None, true)
    file.rdd
  }

  implicit class ShellSrddHelper(df: DataFrame) {
    def save(path: String) = {
      // TODO: why are we creating SmvDFHelper explicitly here?
      var helper = new org.tresamigos.smv.SmvDFHelper(df)
      helper.saveAsCsvWithSchema(path, CsvAttributes.defaultCsvWithHeader)
    }

    def savel(path: String) = {
      var res = df.collect.map{r => r.mkString(",")}.mkString("\n")
      val pw = new PrintWriter(new File(path))
      pw.println(res)
      pw.close()
    }
  }

  implicit class ShellRddHelper(rdd: RDD[String]) {
    def savel(path: String) = {
      var res = rdd.collect.mkString("\n")
      val pw = new PrintWriter(new File(path))
      pw.println(res)
      pw.close()
    }
  }

  def discoverSchema(path: String, n: Int = 100000, ca: CsvAttributes = CsvAttributes.defaultCsvWithHeader) = {
    implicit val csvAttributes=ca
    val schema=sqlContext.discoverSchemaFromFile(path, n)
    val outpath = SmvSchema.dataPathToSchemaPath(path) + ".toBeReviewed"
    schema.saveToHDFSFile(outpath)
  }

  // TODO: this should just be a direct helper on ds as it is probably common.
  def dumpEdd(ds: SmvDataSet) = i.s(ds).edd.addBaseTasks().dump
}

import i._