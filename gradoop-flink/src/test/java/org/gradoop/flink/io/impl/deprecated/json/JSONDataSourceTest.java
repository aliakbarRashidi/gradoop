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
package org.gradoop.flink.io.impl.deprecated.json;

import com.google.common.collect.Lists;
import org.apache.flink.api.java.io.LocalCollectionOutputFormat;
import org.gradoop.common.model.impl.pojo.Edge;
import org.gradoop.common.model.impl.pojo.GraphHead;
import org.gradoop.common.model.impl.pojo.Vertex;
import org.gradoop.flink.io.api.DataSource;
import org.gradoop.flink.model.GradoopFlinkTestBase;
import org.gradoop.flink.model.api.epgm.GraphCollection;
import org.junit.Test;

import java.util.Collection;

import static org.junit.Assert.assertEquals;

public class JSONDataSourceTest extends GradoopFlinkTestBase {

  @Test
  public void testRead() throws Exception {
    String graphFile = getFilePath("/data/json/sna/graphs.json");
    String vertexFile = getFilePath("/data/json/sna/nodes.json");
    String edgeFile = getFilePath("/data/json/sna/edges.json");

    DataSource dataSource = new JSONDataSource(graphFile, vertexFile, edgeFile, getConfig());

    GraphCollection collection = dataSource.getGraphCollection();

    Collection<GraphHead> graphHeads = Lists.newArrayList();
    Collection<Vertex> vertices = Lists.newArrayList();
    Collection<Edge> edges = Lists.newArrayList();

    collection.getGraphHeads().output(new LocalCollectionOutputFormat<>(graphHeads));
    collection.getVertices().output(new LocalCollectionOutputFormat<>(vertices));
    collection.getEdges().output(new LocalCollectionOutputFormat<>(edges));

    getExecutionEnvironment().execute();

    assertEquals("Wrong graph count", 4, graphHeads.size());
    assertEquals("Wrong vertex count", 11, vertices.size());
    assertEquals("Wrong edge count", 24, edges.size());
  }
}
