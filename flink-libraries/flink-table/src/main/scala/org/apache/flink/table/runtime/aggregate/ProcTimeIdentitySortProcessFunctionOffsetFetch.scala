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

import org.apache.flink.api.common.state._
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.state.{ FunctionInitializationContext, FunctionSnapshotContext }
import org.apache.flink.streaming.api.functions.ProcessFunction
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
import java.util.Comparator
import java.util.ArrayList
import java.util.Collections
import org.apache.flink.api.common.typeutils.TypeComparator
import org.apache.flink.table.runtime.types.{CRow, CRowTypeInfo}

/**
 * Process Function used for the sort based solely on proctime with offset/fetch
 * [[org.apache.flink.streaming.api.datastream.DataStream]]
 *
 * @param offset Is used to indicate the number of elements to be skipped in the current context
 * (0 offset allows to execute only fetch)
 * @param fetch Is used to indicate the number of elements to be outputted in the current context
 * @param inputType It is used to mark the type of the incoming data
 */
class ProcTimeIdentitySortProcessFunctionOffsetFetch(
  private val offset: Int,
  private val fetch: Int,
  private val inputRowType: CRowTypeInfo)
    extends ProcessFunction[CRow, CRow] {

  private var stateEventsBuffer: ListState[Row] = _
  private var counterEvents: ValueState[Long] = _
  
  private var outputC: CRow = _
  private val adjustedFetchLimit: Long = offset + Math.max(fetch, 0)
  
  override def open(config: Configuration) {
    val sortDescriptor = new ListStateDescriptor[Row]("sortState",
        inputRowType.asInstanceOf[CRowTypeInfo].rowType)
    stateEventsBuffer = getRuntimeContext.getListState(sortDescriptor)
    
    val counterEventsDescriptor: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("counterEventsState", classOf[Long])
    counterEvents = getRuntimeContext.getState(counterEventsDescriptor)

    val arity:Integer = inputRowType.getArity
    if (outputC == null) {
      outputC = new CRow(Row.of(arity), true)
    }
    
  }

  override def processElement(
    inputC: CRow,
    ctx: ProcessFunction[CRow, CRow]#Context,
    out: Collector[CRow]): Unit = {

    val input = inputC.row
    
    val currentTime = ctx.timerService.currentProcessingTime
    //buffer the event incoming event
    stateEventsBuffer.add(input)
    
    //deduplication of multiple registered timers is done automatically
    ctx.timerService.registerProcessingTimeTimer(currentTime + 1)  
    
  }
  
  override def onTimer(
    timestamp: Long,
    ctx: ProcessFunction[CRow, CRow]#OnTimerContext,
    out: Collector[CRow]): Unit = {
    
    var countOF = counterEvents.value();
    if (countOF == null) {
      countOF = 0L
    }
    
    var i = 0
    //we need to build the output and emit the events in order
    val iter =  stateEventsBuffer.get.iterator()
    while (iter.hasNext()) {
      // display only elements beyond the offset limit
      outputC.row = iter.next()
      if (countOF >= offset && (fetch == -1 || countOF < adjustedFetchLimit)) {
        out.collect(outputC)
      }
      i += 1
      //to prevent the counter to overflow
      countOF = Math.min(countOF + 1, adjustedFetchLimit) 
    }
    stateEventsBuffer.clear()
    counterEvents.update(countOF)
    
  }
  
}