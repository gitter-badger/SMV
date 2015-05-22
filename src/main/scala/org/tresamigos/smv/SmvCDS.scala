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

import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.dsl.plans._

import org.apache.spark.sql.catalyst.expressions._

private[smv] case class CDSSubGroup(
  currentSchema: SmvSchema, 
  crossSchema: SmvSchema,
  currentRow: Row,
  crossRows: Iterable[Row]) extends Serializable
  
/**
 * SmvCDS - SMV Custom Data Selector
 **/
 
abstract class SmvCDS extends Serializable {
  def from(that: SmvCDS): SmvCDS = CombinedCDS(this, that)
  def mapping(input: CDSSubGroup): CDSSubGroup
  
  def createIteratorMap(currentSchema: SmvSchema,  crossSchema: SmvSchema) = { 
    (curr: Row, it: Iterable[Row]) => 
      mapping(CDSSubGroup(currentSchema, crossSchema, curr, it)).crossRows
  }
}

private[smv] case class CombinedCDS(cds1: SmvCDS, cds2: SmvCDS) extends SmvCDS {
  def mapping(input: CDSSubGroup) = {
    cds1.mapping(cds2.mapping(input))
  }
}

/**
 * NoOpCDS: pass down the iterator
 **/
private[smv] case object NoOpCDS extends SmvCDS {
  def mapping(input: CDSSubGroup) = input
}

/**
 * 
 **/
private[smv] class SmvCDSAsGDO(cds: SmvCDS) extends SmvGDO {
  def inGroupKeys = Nil
  def createOutSchema(inSchema: SmvSchema) = inSchema
  def createInGroupMapping(smvSchema:SmvSchema): Iterable[Row] => Iterable[Row] = { it =>
    //TODO: Should consider to pass the last row as current row
    val input = CDSSubGroup(null, smvSchema, null, it)
    cds.mapping(input).crossRows
  }
}

/**
 * SmvCDSAggColumn wraps around a Column to suppot keyword "from"
 **/
case class SmvCDSAggColumn(aggExpr: Expression, cds: SmvCDS = NoOpCDS) {
  
  def from(otherCds: SmvCDS): SmvCDSAggColumn = 
    new SmvCDSAggColumn(aggExpr, cds.from(otherCds))
  
  def as(n: String): SmvCDSAggColumn = 
    new SmvCDSAggColumn(Alias(aggExpr, n)(), cds)
    
  def isAgg(): Boolean = aggExpr match {
    case Alias(e: AggregateExpression, n) => true
    case _: NamedExpression => false
    case _ => throw new IllegalArgumentException(s"${aggExpr.toString} need  to be a NamedExpression") 
  }
}
  
/** 
 * SmvSingleCDSAggs 
 *   - Different aggregation expressions with the same CDS are capsulated
 *   - Resolve the expressions on a given input schema
 *   - Provide executor creater 
 **/
private[smv] case class SmvSingleCDSAggs(cds: SmvCDS, aggExprs: Seq[NamedExpression]){
  def resolvedExprs(inSchema: SmvSchema) = 
    SmvLocalRelation(inSchema).resolveAggExprs(aggExprs: _*)
  
  def createExecuter(toBeComparedSchema: SmvSchema, inSchema: SmvSchema): (Row, Iterable[Row]) => Seq[Any] = {
    val cum = SmvLocalRelation(inSchema).bindAggExprs(aggExprs: _*).map{_.newInstance()}
    val itMap = cds.createIteratorMap(toBeComparedSchema, inSchema)
    
    {(toBeCompared, it) =>
      itMap(toBeCompared, it).foreach{r => cum.foreach(c => c.update(r))}
      cum.map{c => c.eval(null)}
    }
  }
}

/** 
 * Provide functions shared by multiple agg operations 
 **/
private[smv] object SmvCDS {
  /** 
   * The list of column agg/runAgg takes could be a mix of real aggregations or columns to be kept
   * from the original record. Real aggregations should always be something like
   *   
   *   sum(...) [from cds ] as name
   **/
  def findAggCols(cols: Seq[SmvCDSAggColumn]): Seq[SmvCDSAggColumn] =  cols.filter{_.isAgg()}
    
  /** Anything other than real aggregations should be the columns to be kept from original rec */
  def findKeptCols(cols: Seq[SmvCDSAggColumn]): Seq[String] = 
    cols.filter{! _.isAgg()}.map{c => c.aggExpr.asInstanceOf[NamedExpression].name}
    
  /** Put all aggregations with the same CDS chain together */
  def combineCDS(aggCols: Seq[SmvCDSAggColumn]): Seq[SmvSingleCDSAggs] = {
    aggCols.groupBy(_.cds).
      mapValues{vl => vl.map(_.aggExpr.asInstanceOf[NamedExpression])}.
      toSeq.map{case (k,v) => SmvSingleCDSAggs(k, v)}
  }
}

/** 
 * SmvCDSAggGDO
 *   Create a SmvGDO on a group of SmvCDSAggColum, which can be applied by agg operation on SmvGroupedData
 **/
private[smv] class SmvCDSAggGDO(aggCols: Seq[SmvCDSAggColumn]) extends SmvGDO {
  protected val keptCols = SmvCDS.findKeptCols(aggCols)
  protected val cdsAggsList: Seq[SmvSingleCDSAggs] = SmvCDS.combineCDS(SmvCDS.findAggCols(aggCols)) 
  
  def inGroupKeys = Nil
  
  def createInGroupMapping(smvSchema:SmvSchema): Iterable[Row] => Iterable[Row] = {
    //val executers = cdsAggsList.map{_.createExecuter(smvSchema)} - DOESN'T WORK! need to redefine as below for serialization 
    val executers = cdsAggsList.map{aggs => {(r: Row, it: Iterable[Row]) => aggs.createExecuter(smvSchema, smvSchema)(r, it)}}
    val getKept: Row => Seq[Any] = {r => smvSchema.getIndices(keptCols: _*).map{i => r(i)}}
    
    {rows =>
      val rSeq = rows.toSeq
      val currentRow = rSeq.last
      val kept = getKept(currentRow)
      val out = executers.flatMap{ ex => ex(currentRow, rSeq) }
      Seq(new GenericRow((kept ++ out).toArray))
    }
  }
  
  def createOutSchema(smvSchema: SmvSchema) = {
    val ketpEntries = keptCols.map{n => smvSchema.findEntry(n).get}
    val nes = cdsAggsList.flatMap{aggs => aggs.resolvedExprs(smvSchema)}
    new SmvSchema(ketpEntries ++ nes.map{expr => SchemaEntry(expr.asInstanceOf[NamedExpression].name, expr.dataType)})
  }
}

/** 
 * SmvCDSRunAggGDO
 *   Create a SmvGDO on a group of SmvCDSAggColum, which can be applied by runAgg operation on SmvGroupedData
 **/
private[smv] class SmvCDSRunAggGDO(aggCols: Seq[SmvCDSAggColumn]) extends SmvCDSAggGDO(aggCols) {
  override def createInGroupMapping(smvSchema:SmvSchema): Iterable[Row] => Iterable[Row] = {
    val executers = cdsAggsList.map{aggs => {(r: Row, it: Iterable[Row]) => aggs.createExecuter(smvSchema, smvSchema)(r, it)}}
    val getKept: Row => Seq[Any] = {r => smvSchema.getIndices(keptCols: _*).map{i => r(i)}}
    
    {rows =>
      val rSeq = rows.toSeq
      rSeq.map{currentRow => 
        val kept = getKept(currentRow)
        val out = executers.flatMap{ ex => ex(currentRow, rSeq) }
        new GenericRow((kept ++ out).toArray)
      }
    }
  }
}

/**
 * TODO: SmvCDSPanelAggGDO
 **/
 

/*************** The code below is for CDS developer interface ******/

/**
 * SmvSelfCompareCDS
 * 
 * A concrete class of SelfCompareCDS, which has
 *  - Self-join Schema, with the "toBeCompared" Row with original column names, and 
 *    the "running" Rows with "_"-prefixed names
 *  - Apply the "condition", on the "running" Rows for each "toBeCompared" Row
 * 
 * Example:
 * SmvSelfCompareCDS($"t" >= $"_t" && $"t" < ($"_t" + 3))
 * 
 * For each "toBeCompared" record with column "t", above SmvCDS defines a group of 
 * records which has "_t" in the range of (t-3, t]. 
 **/
 
case class SmvSelfCompareCDS(condition: Expression) extends SmvCDS {
  def mapping(input: CDSSubGroup) = {
    val cond = condition as "condition"
    val inSchema = input.crossSchema
    val combinedSchema = inSchema.selfJoined
    val ex = SmvLocalRelation(combinedSchema).bindExprs(cond)(0)
    
    val outIt = input.crossRows.collect{
      case r if (ex.eval(Row.merge(input.currentRow, r)).asInstanceOf[Boolean]) => r
    }
    
    CDSSubGroup(input.currentSchema, inSchema, input.currentRow, outIt)
  }
}

/**
 * TODO: SmvPanelCompareCDS(condition: Expression) extends FullCompareCDS 
 **/
 
/**
 *  SmvTopNRecsCDS 
 *  Returns the TopN records based on the order keys
 *  (which means it can also return botton N records)
 **/

case class SmvTopNRecsCDS(maxElems: Int, orderCols: Seq[Expression]) extends SmvCDS {
  private val orderKeys = orderCols.map{o => o.asInstanceOf[SortOrder]}
  private val keys = orderKeys.map{k => k.child.asInstanceOf[NamedExpression].name}
  private val directions = orderKeys.map{k => k.direction}
  
  def mapping(input: CDSSubGroup) = {
    val inSchema = input.crossSchema
    val ordinals = inSchema.getIndices(keys: _*)
    val ordering = (keys zip directions).map{case (k, d) =>
      val normColOrdering = inSchema.findEntry(k).get.asInstanceOf[NativeSchemaEntry].ordering.asInstanceOf[Ordering[Any]]
      if (d == Ascending) normColOrdering.reverse else normColOrdering
    }

    // create an implicit instance of Ordering[Row] so that it will be picked up by
    // implict order required by BoundedPriorityQueue below.  Therefore, order of row is
    // based on order of specified column.
    implicit object RowOrdering extends Ordering[Row] {
      def compare(a:Row, b:Row) = (ordinals zip ordering).map{case (i, order) => order.compare(a(i),b(i)).signum}
        .reduceLeft((s, i) => s << 1 + i)
    }

    val outIt = {
      val bpq = BoundedPriorityQueue[Row](maxElems)
      input.crossRows.foreach{ r =>
        val v = ordinals.map{i => r(i)}
        if (! v.contains(null))
          bpq += r
      }
      bpq.toList
    }
    
    CDSSubGroup(input.currentSchema, inSchema, input.currentRow, outIt)
  } 
}