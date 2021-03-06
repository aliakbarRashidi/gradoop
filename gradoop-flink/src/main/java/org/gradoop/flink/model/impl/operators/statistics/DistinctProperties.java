/*
 * Copyright © 2014 - 2018 Leipzig University (Database Research Group)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradoop.flink.model.impl.operators.statistics;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.gradoop.common.model.impl.pojo.GraphElement;
import org.gradoop.common.model.impl.properties.PropertyValue;
import org.gradoop.flink.model.api.epgm.LogicalGraph;
import org.gradoop.flink.model.api.operators.UnaryGraphToValueOperator;
import org.gradoop.flink.model.impl.operators.statistics.functions.CombinePropertyValueDistribution;
import org.gradoop.flink.model.impl.tuples.WithCount;

import java.util.Set;

/**
 * Base class for Statistic operators calculating the number of distinct property values grouped by
 * a given key K
 *
 * @param <T> element type
 * @param <K> grouping key
 */
public abstract class DistinctProperties<T extends GraphElement, K>
  implements UnaryGraphToValueOperator<DataSet<WithCount<K>>> {


  @Override
  public DataSet<WithCount<K>> execute(LogicalGraph graph) {
    return extractValuePairs(graph)
      .groupBy(0)
      .reduceGroup(new CombinePropertyValueDistribution<>());
  }

  /**
   * Extracts key value pairs from the given logical graph
   * @param graph input graph
   * @return key value pairs
   */
  protected abstract DataSet<Tuple2<K, Set<PropertyValue>>> extractValuePairs(LogicalGraph graph);
}
