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

import org.apache.flink.api.common.state.{ListState, ListStateDescriptor, MapState, MapStateDescriptor, ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation}
import org.apache.flink.api.java.typeutils.ListTypeInfo
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.table.runtime.types.{CRow, CRowTypeInfo}
import org.apache.flink.types.Row
import org.apache.flink.util.{Collector, Preconditions}

import java.util.Collections
import java.util.{List => JList, ArrayList => JArrayList}

/**
 * ProcessFunction to sort on event-time and possibly additional secondary sort attributes
 * with offset (optionally) and fetch
 *
 * @param offset Is used to indicate the number of elements to be skipped in the current context
 * (0 offset allows to execute only fetch)
 * @param fetch Is used to indicate the number of elements to be outputted in the current context
 * @param inputType It is used to mark the type of the incoming data
 * @param rowComparator the [[java.util.Comparator]] is used for this sort aggregation
 */
class RowTimeDescSortProcessFunctionOffsetFetch(
  private val offset: Int,
  private val fetch: Int,
  private val inputRowType: CRowTypeInfo,
  private val rowComparator: Option[CollectionRowComparator])
    extends ProcessFunction[CRow, CRow] {

  Preconditions.checkNotNull(rowComparator)

   // State to collect rows between watermarks.
  private var dataState: MapState[Long, JList[Row]] = _
  
  // the state which keeps the last triggering timestamp to filter late events
  private var lastTriggeringTsState: ValueState[Long] = _
  private var bufferedEventsLeftover: ListState[Row] = _
  
  private var outputC: CRow = _
  private var outputR: CRow = _
  private val adjustedFetchLimit = offset + Math.max(fetch, 0)
  
  override def open(config: Configuration) {
     
    val keyTypeInformation: TypeInformation[Long] =
      BasicTypeInfo.LONG_TYPE_INFO.asInstanceOf[TypeInformation[Long]]
    val valueTypeInformation: TypeInformation[JList[Row]] = new ListTypeInfo[Row](
        inputRowType.asInstanceOf[CRowTypeInfo].rowType)

    val mapStateDescriptor: MapStateDescriptor[Long, JList[Row]] =
      new MapStateDescriptor[Long, JList[Row]](
        "dataState",
        keyTypeInformation,
        valueTypeInformation)
    dataState = getRuntimeContext.getMapState(mapStateDescriptor)

    val sortDescriptorLeftRetract = new ListStateDescriptor[Row]("sortStateLeftOverRetract",
        inputRowType.asInstanceOf[CRowTypeInfo].rowType)
    bufferedEventsLeftover = getRuntimeContext.getListState(sortDescriptorLeftRetract)
    
    val lastTriggeringTsDescriptor: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("lastTriggeringTsState", classOf[Long])
    lastTriggeringTsState = getRuntimeContext.getState(lastTriggeringTsDescriptor)

    val arity:Integer = inputRowType.getArity
    outputC = new CRow()
    outputR = new CRow(Row.of(arity), false)
  }

  
  override def processElement(
    inputC: CRow,
    ctx: ProcessFunction[CRow, CRow]#Context,
    out: Collector[CRow]): Unit = {

     val input = inputC.row
    
    // timestamp of the processed row
    val triggeringTs = ctx.timestamp

    val lastTriggeringTs = lastTriggeringTsState.value

    // check if the row is late and drop it if it is late
    if (triggeringTs > lastTriggeringTs) {
      val rows = dataState.get(triggeringTs)
      if (null != rows) {
        rows.add(input)
        dataState.put(triggeringTs, rows)
      } else {
        val rows = new JArrayList[Row]
        rows.add(input)
        dataState.put(triggeringTs, rows)
        // register event time timer
        ctx.timerService.registerEventTimeTimer(triggeringTs)
      }
    }
  }
  
  
  override def onTimer(
    timestamp: Long,
    ctx: ProcessFunction[CRow, CRow]#OnTimerContext,
    out: Collector[CRow]): Unit = {

    
    //retract previous elements that were emitted
    val lastTriggeringTs = lastTriggeringTsState.value
    var inputs: JList[Row] = dataState.get(lastTriggeringTs)
    var i = 0
    
    // gets all rows for the triggering timestamps
    inputs = dataState.get(timestamp)

    if (null != inputs) {
      
      var retractionList = dataState.get(lastTriggeringTs)
      if (retractionList == null) {
        retractionList = new JArrayList[Row] 
      }

      // sort rows on secondary fields if necessary
      if (rowComparator.isDefined) {
        Collections.sort(inputs, rowComparator.get)
      }
      
       //add leftover events
      var iter = bufferedEventsLeftover.get.iterator()
      while (iter.hasNext()) {
        inputs.add(iter.next())
      }
      bufferedEventsLeftover.clear()
    
      //we need to build the output and emit the events in order
      i = 0
      while (i < inputs.size) {
        if (i >= offset && (fetch == -1 || i < adjustedFetchLimit)) {
          
          //for each element we emit we need to retract one ...if fetch is not infinite
          if (fetch!= -1 && retractionList.size() >= fetch) {
            outputR.row = retractionList.get(0)
            retractionList.remove(0)
            out.collect(outputR)
          }
          
          outputC.row = inputs.get(i)  
          out.collect(outputC)
          retractionList.add(inputs.get(i))
        } else if (i < offset ){
          //add for future use
          bufferedEventsLeftover.add(inputs.get(i))
        }
        i += 1
      }
    
      //we need to  clear the events processed and keep the sort list for order-retract next time
      dataState.put(timestamp, retractionList)
      // remove emitted rows from state
      dataState.remove(lastTriggeringTs)
    }
    
    lastTriggeringTsState.update(timestamp)
  }
  
}
