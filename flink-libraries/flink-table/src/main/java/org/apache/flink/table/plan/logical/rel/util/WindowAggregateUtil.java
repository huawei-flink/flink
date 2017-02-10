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
package org.apache.flink.table.plan.logical.rel.util;

import java.io.Serializable;
import java.util.List;

import org.apache.calcite.rel.core.Window.Group;
import org.apache.calcite.rel.core.Window.RexWinAggCall;
import org.apache.calcite.rel.logical.LogicalWindow;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.flink.api.java.tuple.Tuple5;

import com.google.common.collect.ImmutableList;

public class WindowAggregateUtil implements Serializable {

	private static final long serialVersionUID = -3916551736243544540L;

	private LogicalWindow windowPointer = null;

	public WindowAggregateUtil() {

	}

	public WindowAggregateUtil(LogicalWindow window) {
		this.windowPointer = window;

	}

	public List<Tuple5<String, String, String, Integer, Integer>> getAggregateFunctions(LogicalWindow window) {

		for (RexWinAggCall agg : window.groups.iterator().next().aggCalls) {

		}
		return null;

	}

	/**
	 * A utility function that checks whether a window is partitioned or it is a
	 * global window.
	 * 
	 * @param LogicalWindow
	 *            window to be checked for partitions
	 * @return true if partition keys are defined, false otherwise.
	 */
	public boolean isStreamPartitioned(LogicalWindow window) {
		// if it exists a group bounded by keys, the it is
		// a partitioned window
		for (Group group : window.groups) {
			if (!group.keys.isEmpty()) {
				return true;
			}
		}

		return false;
	}

	public int[] getKeysAsArray(Group group) {
		if (group == null) {
			return null;
		}
		return group.keys.toArray();
	}

	/**
	 * This method returns the [[int]] lowerbound of a window when expressed
	 * with an integer e.g. ... ROWS BETWEEN [[value]] PRECEDING AND CURRENT ROW
	 * 
	 * @param group
	 *            The group for analyzed window from which keys are extracted
	 * @param constants
	 *            the list of constant to get the offset value
	 * @return return the value of the lowerbound if available -1 otherwise
	 */
<<<<<<< 81118ff60dfba10dbd94a323ffd4c52f5eb1421c
	public int getLowerBoundary(Group group, ImmutableList<RexLiteral> constants) {
		Integer lowerBoundKey = group.keys.asList().get(0);
		Object lowerbound = constants.get(lowerBoundKey).getValue2();
		Object offset = group.lowerBound.getOffset();

		if (offset instanceof RexInputRef) {
			RelDataType type = ((RexInputRef) offset).getType();
			if (type.getSqlTypeName().equals(SqlTypeName.INTEGER)) {
				return Integer.parseInt(lowerbound.toString());
			}
		}
		return -1;
=======
	public int getLowerBoundary(ImmutableList<RexLiteral> constants) {
		
		return ((Long)constants.get(1).getValue2()).intValue();
>>>>>>> First version of ProcTime aggregation with Max, Min and Sum. To be
	}

	
	/**
	 * This method return the keys based on which the object is partitioned or null
	 * 
	 * @return return null for non-partitioned windows or the list of key indexes
	 */
	public List<Integer> getPartitions() {
		Group windowBoundaries = windowPointer.groups.asList().get(0);
		List<Integer> partitionKeys = null;

		if (windowBoundaries.keys.isEmpty()) {
			// partitionKeys = null is indicator of non partitioned OVER clause
		} else {
			partitionKeys = windowBoundaries.keys.asList();
		}

		return partitionKeys;
	}

}
