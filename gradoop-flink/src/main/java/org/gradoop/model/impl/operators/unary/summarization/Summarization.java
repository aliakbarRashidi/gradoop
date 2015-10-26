/*
 * This file is part of Gradoop.
 *
 * Gradoop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gradoop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Gradoop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.gradoop.model.impl.operators.unary.summarization;

import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.functions.FunctionAnnotation;
import org.apache.flink.api.java.operators.UnsortedGrouping;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.util.Collector;
import org.gradoop.util.GConstants;
import org.gradoop.model.api.EdgeData;
import org.gradoop.model.api.EdgeDataFactory;
import org.gradoop.model.api.GraphData;
import org.gradoop.model.api.GraphDataFactory;
import org.gradoop.model.api.VertexData;
import org.gradoop.model.api.VertexDataFactory;
import org.gradoop.util.FlinkConstants;
import org.gradoop.model.impl.LogicalGraph;
import org.gradoop.model.impl.operators.unary.summarization.tuples.EdgeGroupItem;
import org.gradoop.model.impl.operators.unary.summarization.tuples.VertexForGrouping;
import org.gradoop.model.impl.operators.unary.summarization.tuples.VertexGroupItem;
import org.gradoop.model.impl.operators.unary.summarization.tuples
  .VertexWithRepresentative;
import org.gradoop.model.api.operators.UnaryGraphToGraphOperator;

/**
 * The summarization operator determines a structural grouping of similar
 * vertices and edges to condense a graph and thus help to uncover insights
 * about patterns hidden in the graph.
 * <p>
 * The graph summarization operator represents every vertex group by a single
 * vertex in the summarized graph; edges between vertices in the summary graph
 * represent a group of edges between the vertex group members of the
 * original graph. Summarization is defined by specifying grouping keys for
 * vertices and edges, respectively, similarly as for GROUP BY in SQL.
 * <p>
 * Consider the following example:
 * <p>
 * Input graph:
 * <p>
 * Vertices:<br>
 * (0, "Person", {city: L})<br>
 * (1, "Person", {city: L})<br>
 * (2, "Person", {city: D})<br>
 * (3, "Person", {city: D})<br>
 * <p>
 * Edges:{(0,1), (1,0), (1,2), (2,1), (2,3), (3,2)}
 * <p>
 * Output graph (summarized on vertex property "city"):
 * <p>
 * Vertices:<br>
 * (0, "Person", {city: L, count: 2})
 * (2, "Person", {city: D, count: 2})
 * <p>
 * Edges:<br>
 * ((0, 0), {count: 2}) // 2 intra-edges in L<br>
 * ((2, 2), {count: 2}) // 2 intra-edges in L<br>
 * ((0, 2), {count: 1}) // 1 inter-edge from L to D<br>
 * ((2, 0), {count: 1}) // 1 inter-edge from D to L<br>
 * <p>
 * In addition to vertex properties, summarization is also possible on edge
 * properties, vertex- and edge labels as well as combinations of those.
 *
 * @param <VD> EPGM vertex type
 * @param <ED> EPGM edge type
 * @param <GD> EPGM graph head type
 */
public abstract class Summarization<
  VD extends VertexData,
  ED extends EdgeData,
  GD extends GraphData>
  implements UnaryGraphToGraphOperator<VD, ED, GD> {
  /**
   * Used to represent vertices that do not have the vertex grouping property.
   */
  public static final String NULL_VALUE = "__NULL";
  /**
   * Property key to store the number of summarized entities in a group.
   */
  public static final String COUNT_PROPERTY_KEY = "count";
  /**
   * Creates new graph data objects.
   */
  protected GraphDataFactory<GD> graphDataFactory;
  /**
   * Creates new vertex data objects.
   */
  protected VertexDataFactory<VD> vertexDataFactory;
  /**
   * Creates new edge data objects.
   */
  protected EdgeDataFactory<ED> edgeDataFactory;
  /**
   * Used to summarize vertices.
   */
  private final String vertexGroupingKey;
  /**
   * Used to summarize edges.
   */
  private final String edgeGroupingKey;
  /**
   * True if vertices shall be summarized using their label.
   */
  private final boolean useVertexLabels;
  /**
   * True if edges shall be summarized using their label.
   */
  private final boolean useEdgeLabels;

  /**
   * Creates summarization.
   *
   * @param vertexGroupingKey property key to summarize vertices
   * @param edgeGroupingKey   property key to summarize edges
   * @param useVertexLabels   summarize on vertex label true/false
   * @param useEdgeLabels     summarize on edge label true/false
   */
  Summarization(String vertexGroupingKey, String edgeGroupingKey,
    boolean useVertexLabels, boolean useEdgeLabels) {
    this.vertexGroupingKey = vertexGroupingKey;
    this.edgeGroupingKey = edgeGroupingKey;
    this.useVertexLabels = useVertexLabels;
    this.useEdgeLabels = useEdgeLabels;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public LogicalGraph<VD, ED, GD> execute(LogicalGraph<VD, ED, GD> graph) {
    LogicalGraph<VD, ED, GD> result;

    vertexDataFactory = graph.getVertexDataFactory();
    edgeDataFactory = graph.getEdgeDataFactory();
    graphDataFactory = graph.getGraphDataFactory();

    if (!useVertexProperty() &&
      !useEdgeProperty() && !useVertexLabels() && !useEdgeLabels()) {
      // graphs stays unchanged
      result = graph;
    } else {
      GD graphData = createNewGraphData();
      result = LogicalGraph.fromGellyGraph(
        summarizeInternal(graph.toGellyGraph()),
        graphData,
        vertexDataFactory,
        edgeDataFactory,
        graphDataFactory);
    }
    return result;
  }

  /**
   * Returns true if the vertex property shall be used for summarization.
   *
   * @return true if vertex property shall be used for summarization, false
   * otherwise
   */
  protected boolean useVertexProperty() {
    return vertexGroupingKey != null && !"".equals(vertexGroupingKey);
  }

  /**
   * Vertex property key to use for summarizing vertices.
   *
   * @return vertex property key
   */
  protected String getVertexGroupingKey() {
    return vertexGroupingKey;
  }

  /**
   * True, if vertex labels shall be used for summarization.
   *
   * @return true, if vertex labels shall be used for summarization, false
   * otherwise
   */
  protected boolean useVertexLabels() {
    return useVertexLabels;
  }

  /**
   * Returns true if the edge property shall be used for summarization.
   *
   * @return true if edge property shall be used for summarization, false
   * otherwise
   */
  protected boolean useEdgeProperty() {
    return edgeGroupingKey != null && !"".equals(edgeGroupingKey);
  }

  /**
   * Edge property key to use for summarizing edges.
   *
   * @return edge property key
   */
  protected String getEdgeGroupingKey() {
    return edgeGroupingKey;
  }

  /**
   * True, if edge labels shall be used for summarization.
   *
   * @return true, if edge labels shall be used for summarization, false
   * otherwise
   */
  protected boolean useEdgeLabels() {
    return useEdgeLabels;
  }

  /**
   * Group vertices by either vertex label, vertex property or both.
   *
   * @param groupVertices dataset containing vertex representation for grouping
   * @return unsorted vertex grouping
   */
  protected UnsortedGrouping<VertexForGrouping> groupVertices(
    DataSet<VertexForGrouping> groupVertices) {
    UnsortedGrouping<VertexForGrouping> vertexGrouping;
    if (useVertexLabels() && useVertexProperty()) {
      vertexGrouping = groupVertices.groupBy(1, 2);
    } else if (useVertexLabels()) {
      vertexGrouping = groupVertices.groupBy(1);
    } else {
      vertexGrouping = groupVertices.groupBy(2);
    }
    return vertexGrouping;
  }

  /**
   * Groups {@link VertexGroupItem} by either vertex label, vertex property
   * or both. This is used by group combine approaches.
   *
   * @param groupedVertices dataset containing vertex group items
   * @return unsorted grouping
   */
  protected UnsortedGrouping<VertexGroupItem> groupVertexGroupItems(
    DataSet<VertexGroupItem> groupedVertices) {
    UnsortedGrouping<VertexGroupItem> vertexGrouping;
    if (useVertexLabels() && useVertexProperty()) {
      vertexGrouping = groupedVertices.groupBy(2, 3);
    } else if (useVertexLabels()) {
      vertexGrouping = groupedVertices.groupBy(2);
    } else {
      vertexGrouping = groupedVertices.groupBy(3);
    }
    return vertexGrouping;
  }

  /**
   * Build summarized edges by joining them with vertices and their group
   * representative.
   *
   * @param graph                     inout graph
   * @param vertexToRepresentativeMap dataset containing tuples of vertex id
   *                                  and group representative
   * @return summarized edges
   */
  protected DataSet<Edge<Long, ED>> buildSummarizedEdges(
    Graph<Long, VD, ED> graph,
    DataSet<VertexWithRepresentative> vertexToRepresentativeMap) {
    // join edges with vertex-group-map on vertex-id == edge-source-id
    DataSet<EdgeGroupItem> edges =
      graph.getEdges().join(vertexToRepresentativeMap).where(0).equalTo(0)
        // project edges to necessary information
        .with(new SourceVertexJoinFunction<ED>(getEdgeGroupingKey(),
          useEdgeLabels()))
          // join result with vertex-group-map on edge-target-id == vertex-id
        .join(vertexToRepresentativeMap).where(2).equalTo(0)
        .with(new TargetVertexJoinFunction());

    return groupEdges(edges).reduceGroup(
      new EdgeGroupSummarizer<>(getEdgeGroupingKey(), useEdgeLabels(),
        edgeDataFactory)).withForwardedFields("f0");
  }

  /**
   * Groups edges based on the algorithm parameters.
   *
   * @param edges input graph edges
   * @return grouped edges
   */
  protected UnsortedGrouping<EdgeGroupItem> groupEdges(
    DataSet<EdgeGroupItem> edges) {
    UnsortedGrouping<EdgeGroupItem> groupedEdges;
    if (useEdgeProperty() && useEdgeLabels()) {
      groupedEdges = edges.groupBy(1, 2, 3, 4);
    } else if (useEdgeLabels()) {
      groupedEdges = edges.groupBy(1, 2, 3);
    } else if (useEdgeProperty()) {
      groupedEdges = edges.groupBy(1, 2, 4);
    } else {
      groupedEdges = edges.groupBy(1, 2);
    }
    return groupedEdges;
  }

  /**
   * Creates new graph data for the resulting logical graph.
   *
   * @return graph data
   */
  private GD createNewGraphData() {
    return graphDataFactory.createGraphData(FlinkConstants.SUMMARIZE_GRAPH_ID);
  }

  /**
   * Overridden by concrete implementations.
   *
   * @param graph input graph
   * @return summarized output graph
   */
  protected abstract Graph<Long, VD, ED> summarizeInternal(
    Graph<Long, VD, ED> graph);

  /**
   * Creates a summarized edge from a group of edges including an edge
   * grouping value.
   */
  protected static class EdgeGroupSummarizer<ED extends EdgeData> implements
    GroupReduceFunction<EdgeGroupItem, Edge<Long, ED>>,
    ResultTypeQueryable<Edge<Long, ED>> {

    /**
     * Edge data factory
     */
    private final EdgeDataFactory<ED> edgeDataFactory;
    /**
     * Edge property key to store group value
     */
    private final String groupPropertyKey;
    /**
     * True, if label shall be considered
     */
    private boolean useLabel;

    /**
     * True, if property shall be considered.
     */
    private boolean useProperty;
    /**
     * Avoid object instantiation in each reduce call.
     */
    private final Edge<Long, ED> reuseEdge;

    /**
     * Creates group reducer
     *
     * @param groupPropertyKey edge property key to store group value
     * @param useLabel         use edge label
     * @param edgeDataFactory  edge data factory
     */
    public EdgeGroupSummarizer(String groupPropertyKey, boolean useLabel,
      EdgeDataFactory<ED> edgeDataFactory) {
      this.groupPropertyKey = groupPropertyKey;
      this.useLabel = useLabel;
      this.useProperty =
        groupPropertyKey != null && !"".equals(groupPropertyKey);
      this.edgeDataFactory = edgeDataFactory;
      this.reuseEdge = new Edge<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reduce(Iterable<EdgeGroupItem> edgeGroupItems,
      Collector<Edge<Long, ED>> collector) throws Exception {
      int edgeCount = 0;
      boolean initialized = false;
      // new edge id will be the first edge id in the group (which is sorted)
      Long newEdgeID = null;
      Long newSourceVertexId = null;
      Long newTargetVertexId = null;
      String edgeLabel = GConstants.DEFAULT_EDGE_LABEL;
      String edgeGroupingValue = null;

      for (EdgeGroupItem e : edgeGroupItems) {
        edgeCount++;
        if (!initialized) {
          newEdgeID = e.getEdgeId();
          newSourceVertexId = e.getSourceVertexId();
          newTargetVertexId = e.getTargetVertexId();
          if (useLabel) {
            edgeLabel = e.getGroupLabel();
          }
          edgeGroupingValue = e.getGroupPropertyValue();
          initialized = true;
        }
      }

      ED newEdgeData = edgeDataFactory
        .createEdgeData(newEdgeID, edgeLabel, newSourceVertexId,
          newTargetVertexId);

      if (useProperty) {
        newEdgeData.setProperty(groupPropertyKey, edgeGroupingValue);
      }
      newEdgeData.setProperty(COUNT_PROPERTY_KEY, edgeCount);
      newEdgeData.addGraph(FlinkConstants.SUMMARIZE_GRAPH_ID);

      reuseEdge.setSource(newSourceVertexId);
      reuseEdge.setTarget(newTargetVertexId);
      reuseEdge.setValue(newEdgeData);
      collector.collect(reuseEdge);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public TypeInformation<Edge<Long, ED>> getProducedType() {
      return new TupleTypeInfo(Edge.class, BasicTypeInfo.LONG_TYPE_INFO,
        BasicTypeInfo.LONG_TYPE_INFO,
        TypeExtractor.createTypeInfo(edgeDataFactory.getType()));
    }
  }

  /**
   * Takes an edge and a tuple (vertex-id, group-representative) as input.
   * Replaces the edge-source-id with the group-representative and outputs
   * projected edge information possibly containing the edge label and a
   * group property.
   */
  @FunctionAnnotation.ForwardedFieldsFirst("f1->f2") // edge target id
  @FunctionAnnotation.ForwardedFieldsSecond("f1") // edge source id
  protected static class SourceVertexJoinFunction<ED extends EdgeData>
    implements
    JoinFunction<Edge<Long, ED>, VertexWithRepresentative, EdgeGroupItem> {

    /**
     * Vertex property key for grouping
     */
    private final String groupPropertyKey;
    /**
     * True, if vertex label shall be considered.
     */
    private final boolean useLabel;
    /**
     * True, if vertex property shall be considered.
     */
    private final boolean useProperty;

    /**
     * Avoid object initialization in each call.
     */
    private final EdgeGroupItem reuseEdgeGroupItem;

    /**
     * Creates join function.
     *
     * @param groupPropertyKey vertex property key for grouping
     * @param useLabel         true, if vertex label shall be used
     */
    public SourceVertexJoinFunction(String groupPropertyKey, boolean useLabel) {
      this.groupPropertyKey = groupPropertyKey;
      this.useLabel = useLabel;
      this.reuseEdgeGroupItem = new EdgeGroupItem();
      this.useProperty =
        groupPropertyKey != null && !"".equals(groupPropertyKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EdgeGroupItem join(Edge<Long, ED> e,
      VertexWithRepresentative vertexRepresentative) throws Exception {
      String groupLabel = useLabel ? e.getValue().getLabel() : null;
      String groupPropertyValue = null;

      boolean hasProperty =
        useProperty && (e.getValue().getProperty(groupPropertyKey) != null);
      if (useProperty && hasProperty) {
        groupPropertyValue =
          e.getValue().getProperty(groupPropertyKey).toString();
      } else if (useProperty) {
        groupPropertyValue = NULL_VALUE;
      }
      reuseEdgeGroupItem.setEdgeId(e.getValue().getId());
      reuseEdgeGroupItem.setSourceVertexId(
        vertexRepresentative.getGroupRepresentativeVertexId());
      reuseEdgeGroupItem.setTargetVertexId(e.getTarget());
      reuseEdgeGroupItem.setGroupLabel(groupLabel);
      reuseEdgeGroupItem.setGroupPropertyValue(groupPropertyValue);

      return reuseEdgeGroupItem;
    }
  }

  /**
   * Takes a projected edge and an (vertex-id, group-representative) tuple
   * and replaces the edge-target-id with the group-representative.
   */
  @FunctionAnnotation.ForwardedFieldsFirst("f0;f1;f3;f4")
  @FunctionAnnotation.ForwardedFieldsSecond("f1->f2")
  protected static class TargetVertexJoinFunction implements
    JoinFunction<EdgeGroupItem, VertexWithRepresentative, EdgeGroupItem> {

    /**
     * {@inheritDoc}
     */
    @Override
    public EdgeGroupItem join(EdgeGroupItem edge,
      VertexWithRepresentative vertexRepresentative) throws Exception {
      edge.setField(vertexRepresentative.getGroupRepresentativeVertexId(), 2);
      return edge;
    }
  }
}
