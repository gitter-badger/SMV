/*
 * This file is licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tresamigos.smv
import org.apache.spark.sql.functions._

class AggFuncsTest extends SparkTestUtil {
  sparkTest("test OnlineAverage") {
    val ssc = sqlContext; import ssc.implicits._
    val srdd = sqlContext.csvFileWithSchema(testDataDir +  "AggTest/test1.csv")
    val avg = srdd.agg(onlineAverage('a), onlineAverage('b))
    assertDoubleSeqEqual(avg.collect()(0).toSeq, List(2.0, 20.0))
  }
  sparkTest("test OnlineStdDev") {
    val ssc = sqlContext; import ssc.implicits._
    val srdd = sqlContext.csvFileWithSchema(testDataDir +  "AggTest/test1.csv")
    val stddev = srdd.agg(onlineStdDev('a), onlineStdDev('b))
    assertDoubleSeqEqual(stddev.collect()(0).toSeq, List(1.0, 10.0))
  }
  /*d
  */
  sparkTest("test Histogram") {
    val ssc = sqlContext; import ssc.implicits._
    val srdd = sqlContext.csvFileWithSchema(testDataDir +  "AggTest/test2.csv")
    val hist = srdd.agg(histogram('id)).collect()(0)(0).asInstanceOf[Map[String,Long]] //Array[Row(Map[String,Long])]=> Any=Map[..]
    assert(hist === Map("231"->1l,"123"->2l))
  }

  sparkTest("test SmvFirst") {
    val ssc = sqlContext; import ssc.implicits._
    val srdd = createSchemaRdd("k:String; t:Integer; v:Double", "z,1,;z,2,1.4;z,5,2.2;a,1,0.3;")

    val res = srdd.groupBy("k").agg(
      $"k",
      first($"t"),
      first($"v") as "first_v",
      smvFirst($"v") as "smvFirst_v"
    )

    assertUnorderedSeqEqual(res.collect.map(_.toString), Seq(
      "[a,1,0.3,0.3]",
      "[z,1,1.4,null]"))
  }
}
