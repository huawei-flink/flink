/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.runtime.aggregate

import org.apache.flink.api.common.state.{ ListState, ListStateDescriptor }
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.state.{ FunctionInitializationContext, FunctionSnapshotContext }
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.table.functions.{ Accumulator, AggregateFunction }
import org.apache.flink.types.Row
import org.apache.flink.util.{ Collector, Preconditions }
import org.apache.flink.api.common.state.ValueState
import org.apache.flink.api.common.state.ValueStateDescriptor
import scala.util.control.Breaks._
import org.apache.flink.api.java.tuple.{ Tuple2 => JTuple2 }
import org.apache.flink.api.common.state.MapState
import org.apache.flink.api.common.state.MapStateDescriptor
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.ListTypeInfo
import java.util.{ ArrayList, LinkedList, List => JList }
import org.apache.flink.api.common.typeinfo.BasicTypeInfo

/**
 * Process Function used for the aggregate in bounded proc-time OVER window
 * [[org.apache.flink.streaming.api.datastream.DataStream]]
 *
 * @param aggregates the list of all [[org.apache.flink.table.functions.AggregateFunction]]
 *                   used for this aggregation
 * @param aggFields  the position (in the input Row) of the input value for each aggregate
 * @param forwardedFieldCount Is used to indicate fields in the current element to forward
 * @param rowTypeInfo Is used to indicate the field schema
 * @param precedingTimeBoundary Is used to indicate the processing time boundaries
 * @param inputType It is used to mark the Row type of the input
 */
class ProcTimeBoundedProcessingOverProcessFunction(
  private val aggregates: Array[AggregateFunction[_]],
  private val aggFields: Array[Int],
  private val forwardedFieldCount: Int,
  private val rowTypeInfo: RowTypeInfo,
  private val precedingTimeBoundary: Long,
  private val inputType: TypeInformation[Row])
    extends ProcessFunction[Row, Row] {

  Preconditions.checkNotNull(aggregates)
  Preconditions.checkNotNull(aggFields)
  Preconditions.checkArgument(aggregates.length == aggFields.length)

  private var output: Row = _
  private var accumulatorState: ValueState[Row] = _
  private var lastProcessedProcTime: ValueState[Long] = _
  private var rowMapState: MapState[Long, JList[Row]] = _

  override def open(config: Configuration) {
    output = new Row(forwardedFieldCount + aggregates.length)

    // We keep the elements received in a MapState indexed based on their ingestion time 
    val rowListTypeInfo: TypeInformation[JList[Row]] =
      new ListTypeInfo[Row](inputType).asInstanceOf[TypeInformation[JList[Row]]]
    val mapStateDescriptor: MapStateDescriptor[Long, JList[Row]] =
      new MapStateDescriptor[Long, JList[Row]]("rowmapstate",
        BasicTypeInfo.LONG_TYPE_INFO.asInstanceOf[TypeInformation[Long]], rowListTypeInfo)
    rowMapState = getRuntimeContext.getMapState(mapStateDescriptor)

    val stateDescriptor: ValueStateDescriptor[Row] =
      new ValueStateDescriptor[Row]("overState", rowTypeInfo)
    accumulatorState = getRuntimeContext.getState(stateDescriptor)
    
     val stateDescriptorProcTime: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("lastProcessedTime", 
          BasicTypeInfo.LONG_TYPE_INFO.asInstanceOf[TypeInformation[Long]])
    lastProcessedProcTime = getRuntimeContext.getState(stateDescriptorProcTime)
  }

  override def processElement(
    input: Row,
    ctx: ProcessFunction[Row, Row]#Context,
    out: Collector[Row]): Unit = {

    val currentTime = ctx.timerService().currentProcessingTime()
    //buffer the event incoming event

    var i = 0

    //initialize the accumulators 
    var accumulators = accumulatorState.value()

    if (null == accumulators) {
      accumulators = new Row(aggregates.length)
      i = 0
      while (i < aggregates.length) {
        accumulators.setField(i, aggregates(i).createAccumulator())
        i += 1
      }
    }
    
    //initialize the time reference of the last event that was processed 
    var lastProcTime = lastProcessedProcTime.value()

    //set the fields of the last event to carry on with the aggregates
    i = 0
    while (i < forwardedFieldCount) {
      output.setField(i, input.getField(i))
      i += 1
    }

    //in case some other events arrived in the same time, we do not need to retract anymore 
    if (lastProcTime != currentTime) {
    
      //update the elements to be removed and retract them from aggregators
      val limit = currentTime - precedingTimeBoundary
    
      // we iterate through all elements in the window buffer based on timestamp keys
      // when we find timestamps that are out of interest, we retrieve corresponding elements
      // and eliminate them. Multiple elements can be received at the same timestamp
      val iter = rowMapState.keys.iterator
      var markToRemove = new ArrayList[Long]()
      while (iter.hasNext()) {
        val elementKey = iter.next
        if (elementKey < limit) {
          val elementsRemove = rowMapState.get(elementKey)
          var iRemove = 0
          while (iRemove < elementsRemove.size()) {
            i = 0
            while (i < aggregates.length) {
              val accumulator = accumulators.getField(i).asInstanceOf[Accumulator]
              aggregates(i).retract(accumulator, elementsRemove.get(iRemove)
                 .getField(aggFields(i)))
              i += 1
            }
            iRemove += 1
          }
          //mark element for later removal not to modify the iterator over MapState
          markToRemove.add(elementKey)
        }
      }
      //need to remove in 2 steps not to have concurrent access errors via iterator to the MapState
      i = 0
      while (i < markToRemove.size()) {
        rowMapState.remove(markToRemove.get(i))
        i += 1
      }
    }
    
    //add current element to aggregator  
    i = 0
    while (i < aggregates.length) {
      val index = forwardedFieldCount + i
      val accumulator = accumulators.getField(i).asInstanceOf[Accumulator]
      aggregates(i).accumulate(accumulator, input.getField(aggFields(i)))
      output.setField(index, aggregates(i).getValue(accumulator))
      i += 1
    }

    accumulatorState.update(accumulators)
    // add current element to the window list of elements with corresponding timestamp
    var rowList = rowMapState.get(currentTime)
    if (rowList == null) {
      rowList = new ArrayList[Row]()
    }
    rowList.add(input)
    rowMapState.put(currentTime, rowList)
    //keep the value of the time when last processing happened
    lastProcessedProcTime.update(currentTime)

    out.collect(output)
  }
}
