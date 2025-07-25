/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.msq.exec;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.DoubleDimensionSchema;
import org.apache.druid.data.input.impl.FloatDimensionSchema;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.indexer.granularity.GranularitySpec;
import org.apache.druid.indexer.granularity.UniformGranularitySpec;
import org.apache.druid.indexer.partitions.DimensionRangePartitionsSpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.actions.RetrieveUsedSegmentsAction;
import org.apache.druid.indexing.common.actions.TaskAction;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.GranularityType;
import org.apache.druid.msq.indexing.error.TooManySegmentsInTimeChunkFault;
import org.apache.druid.msq.indexing.report.MSQSegmentReport;
import org.apache.druid.msq.sql.MSQTaskQueryMaker;
import org.apache.druid.msq.test.CounterSnapshotMatcher;
import org.apache.druid.msq.test.MSQTestBase;
import org.apache.druid.msq.test.MSQTestTaskActionClient;
import org.apache.druid.msq.util.MultiStageQueryContext;
import org.apache.druid.query.DruidMetrics;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.timeline.CompactionState;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.easymock.EasyMock;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MSQReplaceTest extends MSQTestBase
{

  private static final String WITH_REPLACE_LOCK_AND_COMPACTION_STATE = "with_replace_lock_and_compaction_state";
  private static final Map<String, Object> QUERY_CONTEXT_WITH_REPLACE_LOCK_AND_COMPACTION_STATE =
      ImmutableMap.<String, Object>builder()
                  .putAll(DEFAULT_MSQ_CONTEXT)
                  .put(Tasks.TASK_LOCK_TYPE, StringUtils.toLowerCase(TaskLockType.REPLACE.name()))
                  .put(Tasks.STORE_COMPACTION_STATE_KEY, true)
                  .build();

  public static Collection<Object[]> data()
  {
    Object[][] data = new Object[][]{
        {DEFAULT, DEFAULT_MSQ_CONTEXT},
        {DURABLE_STORAGE, DURABLE_STORAGE_MSQ_CONTEXT},
        {FAULT_TOLERANCE, FAULT_TOLERANCE_MSQ_CONTEXT},
        {PARALLEL_MERGE, PARALLEL_MERGE_MSQ_CONTEXT},
        {SUPERUSER, SUPERUSER_MSQ_CONTEXT},
        {WITH_REPLACE_LOCK_AND_COMPACTION_STATE, QUERY_CONTEXT_WITH_REPLACE_LOCK_AND_COMPACTION_STATE}
        };
    return Arrays.asList(data);
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAll(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY DAY ")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.of("2000-01-01T/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2000-01-02T/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2000-01-03T/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2001-01-01T/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2001-01-02T/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2001-01-03T/P1D"), "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978480000000L, 6.0f}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1, 1, 1, 1, 1, 1).frames(1, 1, 1, 1, 1, 1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1, 1, 1, 1, 1, 1).frames(1, 1, 1, 1, 1, 1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.DAY,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByDim(String contextName, Map<String, Object> context)
  {
    // Tests [CLUSTERED BY dim1] with the default forceSegmentSortByTime (true). In this case,
    // partitioning uses [dim1] but segment sort uses [__time, dim1].
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY dim1")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, "", 1.0f},
                             new Object[]{946771200000L, "10.1", 2.0f},
                             new Object[]{946857600000L, "2", 3.0f},
                             new Object[]{978307200000L, "1", 4.0f},
                             new Object[]{978393600000L, "def", 5.0f},
                             new Object[]{978480000000L, "abc", 6.0f}
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("dim1"),
                             DimensionsSpec.builder()
                                           .setDimensions(
                                               ImmutableList.of(
                                                   new StringDimensionSchema("dim1"),
                                                   new FloatDimensionSchema("m1")
                                               )
                                           )
                                           .setDimensionExclusions(Collections.singletonList("__time"))
                                           .build(),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByDimExplicitSort(String contextName, Map<String, Object> context)
  {
    // Tests [CLUSTERED BY LOWER(dim1)], i.e. an expression that is not actually stored.
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY LOWER(dim1)")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedShardSpec(NumberedShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, "", 1.0f},
                             new Object[]{946771200000L, "10.1", 2.0f},
                             new Object[]{946857600000L, "2", 3.0f},
                             new Object[]{978307200000L, "1", 4.0f},
                             new Object[]{978393600000L, "def", 5.0f},
                             new Object[]{978480000000L, "abc", 6.0f}
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             DimensionsSpec.builder()
                                           .setDimensions(
                                               ImmutableList.of(
                                                   new StringDimensionSchema("dim1"),
                                                   new FloatDimensionSchema("m1")
                                               )
                                           )
                                           .setDimensionExclusions(Collections.singletonList("__time"))
                                           .build(),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByExpression(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("dim1", ColumnType.STRING)
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Map<String, Object> queryContext = new HashMap<>(context);
    queryContext.put(DimensionsSpec.PARAMETER_FORCE_TIME_SORT, false);

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY dim1")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(queryContext)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{"", 946684800000L, 1.0f},
                             new Object[]{"1", 978307200000L, 4.0f},
                             new Object[]{"10.1", 946771200000L, 2.0f},
                             new Object[]{"2", 946857600000L, 3.0f},
                             new Object[]{"abc", 978480000000L, 6.0f},
                             new Object[]{"def", 978393600000L, 5.0f}
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("dim1"),
                             DimensionsSpec.builder()
                                           .setDimensions(
                                               ImmutableList.of(
                                                   new StringDimensionSchema("dim1"),
                                                   new LongDimensionSchema("__time"),
                                                   new FloatDimensionSchema("m1")
                                               )
                                           )
                                           .setForceSegmentSortByTime(false)
                                           .build(),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByDimThenTimeExplicitSort(String contextName, Map<String, Object> context)
  {
    // Tests that [CLUSTERED BY dim1, __time] and [CLUSTERED BY dim1] are same when
    // forceSegmentSortByTime = false. (Same expectations as the prior test,
    // testReplaceOnFooWithAllClusteredByDimExplicitSort.)
    RowSignature rowSignature = RowSignature.builder()
                                            .add("dim1", ColumnType.STRING)
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Map<String, Object> queryContext = new HashMap<>(context);
    queryContext.put(DimensionsSpec.PARAMETER_FORCE_TIME_SORT, false);

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY dim1, __time")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(queryContext)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{"", 946684800000L, 1.0f},
                             new Object[]{"1", 978307200000L, 4.0f},
                             new Object[]{"10.1", 946771200000L, 2.0f},
                             new Object[]{"2", 946857600000L, 3.0f},
                             new Object[]{"abc", 978480000000L, 6.0f},
                             new Object[]{"def", 978393600000L, 5.0f}
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("dim1"),
                             DimensionsSpec.builder()
                                           .setDimensions(
                                               ImmutableList.of(
                                                   new StringDimensionSchema("dim1"),
                                                   new LongDimensionSchema("__time"),
                                                   new FloatDimensionSchema("m1")
                                               )
                                           )
                                           .setForceSegmentSortByTime(false)
                                           .build(),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByDimThenTimeError(String contextName, Map<String, Object> context)
  {
    // Tests that [CLUSTERED BY dim1, __time] is an error when forceSegmentSortByTime = true (the default).
    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY dim1, __time")
                     .setExpectedDataSource("foo")
                     .setQueryContext(context)
                     .setExpectedValidationErrorMatcher(invalidSqlContains(
                         "Sort order (CLUSTERED BY) cannot include[__time] in position[1] unless context "
                         + "parameter[forceSegmentSortByTime] is set to[false]."
                     ))
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByDimThenTimeError2(String contextName, Map<String, Object> context)
  {
    // Tests that setting segmentSortOrder = [dim1, __time] is an error when
    // forceSegmentSortByTime = false (the default).
    Map<String, Object> queryContext = new HashMap<>(context);
    queryContext.put(MultiStageQueryContext.CTX_SORT_ORDER, "dim1, __time");

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY dim1")
                     .setExpectedDataSource("foo")
                     .setQueryContext(queryContext)
                     .setExpectedValidationErrorMatcher(invalidSqlContains(
                         "Context parameter[segmentSortOrder] must start with[__time] unless context "
                         + "parameter[forceSegmentSortByTime] is set to[false]."
                     ))
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithAllClusteredByTimeThenDimExplicitSort(String contextName, Map<String, Object> context)
  {
    // Tests [CLUSTERED BY __time, dim1] with forceSegmentSortByTime = false.
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Map<String, Object> queryContext = new HashMap<>(context);
    queryContext.put(DimensionsSpec.PARAMETER_FORCE_TIME_SORT, false);

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));

    testIngestQuery().setSql(" REPLACE INTO foo OVERWRITE ALL "
                             + "SELECT __time, dim1, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL "
                             + "CLUSTERED BY __time, dim1")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(queryContext)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedShardSpec(NumberedShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, "", 1.0f},
                             new Object[]{946771200000L, "10.1", 2.0f},
                             new Object[]{946857600000L, "2", 3.0f},
                             new Object[]{978307200000L, "1", 4.0f},
                             new Object[]{978393600000L, "def", 5.0f},
                             new Object[]{978480000000L, "abc", 6.0f}
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             // For backwards-compatibility, compaction state is stored as if
                             // forceSegmentSortByTime = true.
                             ImmutableList.of(
                                 new StringDimensionSchema("dim1"),
                                 new FloatDimensionSchema("m1")
                             ),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFooWithWhere(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(
                         " REPLACE INTO foo OVERWRITE WHERE __time >= TIMESTAMP '2000-01-02' AND __time < TIMESTAMP '2000-01-03' "
                         + "SELECT __time, m1 "
                         + "FROM foo "
                         + "WHERE __time >= TIMESTAMP '2000-01-02' AND __time < TIMESTAMP '2000-01-03' "
                         + "PARTITIONED by DAY ")
                     .setExpectedDataSource("foo")
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of(
                         "2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z")))
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-02T/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{946771200000L, 2.0f}))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(1),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.DAY,
                             Intervals.of("2000-01-02T/P1D")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnRestricted(String contextName, Map<String, Object> context)
  {
    // Set expected results based on query's end user
    boolean isSuperUser = context.get(MSQTaskQueryMaker.USER_KEY).equals(CalciteTests.TEST_SUPERUSER_NAME);
    ImmutableSet<Interval> expectedTombstoneIntervals = isSuperUser
                                                        ? ImmutableSet.of()
                                                        : ImmutableSet.of(
                                                            Intervals.of("2001-01-01T/P1D"),
                                                            Intervals.of("2001-01-02T/P1D")
                                                        );
    ImmutableSet<SegmentId> expectedSegments = isSuperUser ? ImmutableSet.of(
        SegmentId.of("restrictedDatasource_m1_is_6", Intervals.of("2001-01-01T/P1D"), "test", 0),
        SegmentId.of("restrictedDatasource_m1_is_6", Intervals.of("2001-01-02T/P1D"), "test", 0),
        SegmentId.of("restrictedDatasource_m1_is_6", Intervals.of("2001-01-03T/P1D"), "test", 0)
    ) : ImmutableSet.of(SegmentId.of("restrictedDatasource_m1_is_6", Intervals.of("2001-01-03T/P1D"), "test", 0));
    ImmutableList<Object[]> expectedResultRows = isSuperUser ? ImmutableList.of(
        new Object[]{978307200000L, 4.0f},
        new Object[]{978393600000L, 5.0f},
        new Object[]{978480000000L, 6.0f}
    ) : ImmutableList.of(new Object[]{978480000000L, 6.0f});
    // Set common expected results (not relevant to query's end user)
    CounterSnapshotMatcher shuffleCounterSnapshotMatcher = isSuperUser
                                                           ? CounterSnapshotMatcher.with()
                                                                                   .rows(1, 1, 1)
                                                                                   .frames(1, 1, 1)
                                                           : CounterSnapshotMatcher.with().rows(1).frames(1);
    CounterSnapshotMatcher inputCounterSnapshotMatcher = isSuperUser
                                                         ? CounterSnapshotMatcher.with()
                                                                                 .rows(1, 1, 1)
                                                                                 .frames(1, 1, 1)
                                                         : CounterSnapshotMatcher.with().rows(1).frames(1);
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(
                         " REPLACE INTO restrictedDatasource_m1_is_6 OVERWRITE WHERE __time >= TIMESTAMP '2001-01-01' AND __time < TIMESTAMP '2001-01-04' "
                         + "SELECT __time, m1 "
                         + "FROM restrictedDatasource_m1_is_6 "
                         + "WHERE __time >= TIMESTAMP '2001-01-01' AND __time < TIMESTAMP '2001-01-04' "
                         + "PARTITIONED by DAY ")
                     .setExpectedDataSource("restrictedDatasource_m1_is_6")
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of("2001-01-01T/P3D")))
                     .setExpectedTombstoneIntervals(expectedTombstoneIntervals)
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(expectedSegments)
                     .setExpectedResultRows(expectedResultRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(shuffleCounterSnapshotMatcher, 0, 0, "shuffle")
                     .setExpectedCountersForStageWorkerChannel(inputCounterSnapshotMatcher, 1, 0, "input0")
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(expectedSegments.size()),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.DAY,
                             Intervals.of("2001-01-01T/P3D")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceWithDynamicParameters(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(
                         " REPLACE INTO foo OVERWRITE WHERE __time >= ? AND __time < ? "
                         + "SELECT __time, m1 "
                         + "FROM foo "
                         + "WHERE __time >= ? AND __time < ? "
                         + "PARTITIONED by DAY ")
                     .setDynamicParameters(ImmutableList.of(
                         TypedValue.ofLocal(ColumnMetaData.Rep.JAVA_SQL_TIMESTAMP, DateTimes.of("2000-01-02").getMillis()),
                         TypedValue.ofLocal(ColumnMetaData.Rep.JAVA_SQL_TIMESTAMP, DateTimes.of("2000-01-03").getMillis()),
                         TypedValue.ofLocal(ColumnMetaData.Rep.JAVA_SQL_TIMESTAMP, DateTimes.of("2000-01-02").getMillis()),
                         TypedValue.ofLocal(ColumnMetaData.Rep.JAVA_SQL_TIMESTAMP, DateTimes.of("2000-01-03").getMillis())
                     ))
                     .setExpectedDataSource("foo")
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of(
                         "2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z")))
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-02T/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{946771200000L, 2.0f}))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(1),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.DAY,
                             Intervals.of("2000-01-02T/P1D")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFoo1WithAllExtern(String contextName, Map<String, Object> context) throws IOException
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("cnt", ColumnType.LONG).build();

    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    testIngestQuery().setSql(" REPLACE INTO foo1 OVERWRITE ALL SELECT "
                             + "  floor(TIME_PARSE(\"timestamp\") to hour) AS __time, "
                             + "  count(*) AS cnt "
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ") GROUP BY 1  PARTITIONED BY HOUR ")
                     .setExpectedDataSource("foo1")
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "Using[0] CLUSTERED BY columns for 'range' shard specs, since the next column is of "
                             + "type[LONG]. Only string columns are included in 'range' shard specs."
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(
                                             SegmentId.of(
                                                 "foo1",
                                                 Intervals.of("2016-06-27T00:00:00.000Z/2016-06-27T01:00:00.000Z"),
                                                 "test",
                                                 0
                                             ),
                                             SegmentId.of(
                                                 "foo1",
                                                 Intervals.of("2016-06-27T01:00:00.000Z/2016-06-27T02:00:00.000Z"),
                                                 "test",
                                                 0
                                             ),
                                             SegmentId.of(
                                                 "foo1",
                                                 Intervals.of("2016-06-27T02:00:00.000Z/2016-06-27T03:00:00.000Z"),
                                                 "test",
                                                 0
                                             )
                                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{1466985600000L, 10L},
                             new Object[]{1466989200000L, 4L},
                             new Object[]{1466992800000L, 6L}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(3).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(3).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1, 1, 1).frames(1, 1, 1),
                         1, 0, "shuffle"
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new LongDimensionSchema("cnt")),
                             GranularityType.HOUR,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFoo1WithWhereExtern(String contextName, Map<String, Object> context) throws IOException
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("user", ColumnType.STRING).build();

    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    testIngestQuery().setSql(
                         " REPLACE INTO foo1 OVERWRITE WHERE __time >= TIMESTAMP '2016-06-27 01:00:00.00' AND __time < TIMESTAMP '2016-06-27 02:00:00.00' "
                         + " SELECT "
                         + "  floor(TIME_PARSE(\"timestamp\") to hour) AS __time, "
                         + "  user "
                         + "FROM TABLE(\n"
                         + "  EXTERN(\n"
                         + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                         + "    '{\"type\": \"json\"}',\n"
                         + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                         + "  )\n"
                         + ") "
                         + "where \"timestamp\" >= TIMESTAMP '2016-06-27 01:00:00.00' AND \"timestamp\" < TIMESTAMP '2016-06-27 02:00:00.00' "
                         + "PARTITIONED BY HOUR ")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of(
                         "2016-06-27T01:00:00.000Z/2016-06-27T02:00:00.000Z")))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27T01:00:00.000Z/2016-06-27T02:00:00.000Z"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{1466989200000L, "2001:DA8:207:E132:94DC:BA03:DFDF:8F9F"},
                             new Object[]{1466989200000L, "Ftihikam"},
                             new Object[]{1466989200000L, "Guly600"},
                             new Object[]{1466989200000L, "Kolega2357"}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(4).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(4).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(4),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new StringDimensionSchema("user")),
                             GranularityType.HOUR,
                             Intervals.of("2016-06-27T01:00:00.000Z/2016-06-27T02:00:00.000Z")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceIncorrectSyntax(String contextName, Map<String, Object> context)
  {
    testIngestQuery()
        .setSql("REPLACE INTO foo1 OVERWRITE SELECT * FROM foo PARTITIONED BY ALL TIME")
        .setExpectedDataSource("foo1")
        .setQueryContext(context)
        .setExpectedValidationErrorMatcher(invalidSqlContains(
            "Missing time chunk information in OVERWRITE clause for REPLACE. "
            + "Use OVERWRITE WHERE <__time based condition> or OVERWRITE ALL to overwrite the entire table."
        ))
        .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceSegmentEntireTable(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL TIME ")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978480000000L, 6.0f}
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo", Intervals.ETERNITY, "test", 0)))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceSegmentsRepartitionTable(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();
    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doCallRealMethod()
           .doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.ETERNITY)));


    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978480000000L, 6.0f}
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(
                                             SegmentId.of("foo", Intervals.of("2000-01-01T/P1M"), "test", 0),
                                             SegmentId.of("foo", Intervals.of("2001-01-01T/P1M"), "test", 0)
                                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(3, 3).frames(1, 1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(3, 3).frames(1, 1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceWithWhereClause(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment0))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction("foo", ImmutableList.of(Intervals.of("2000-01-01/2000-03-01"))));

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-03-01' "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-01-03' "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Collections.singletonList(Intervals.of("2000-01-01T/2000-03-01T")))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f}
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(2),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.of("2000-01-01T/2000-03-01T")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceWhereClauseLargerThanData(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction(
               EasyMock.eq("foo"),
               EasyMock.eq(ImmutableList.of(Intervals.of("2000-01-01/2002-01-01")))
           ));


    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2002-01-01' "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-01-03' "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Collections.singletonList(Intervals.of("2000-01-01T/2002-01-01T")))
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.of("2001-01-01T/2001-02-01T")))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(2),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.of("2000-01-01T/2002-01-01T")
                         )
                     )
                     .verifyResults();
  }

  @Test
  public void testReplaceWithTooManySegmentsInTimeChunk()
  {
    final Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                                    .putAll(DEFAULT_MSQ_CONTEXT)
                                                    .put("maxNumSegments", 1)
                                                    .put("rowsPerSegment", 1)
                                                    .build();

    testIngestQuery().setSql("REPLACE INTO foo"
                             + " OVERWRITE ALL "
                             + " SELECT TIME_PARSE(ts) AS __time, c1 "
                             + " FROM (VALUES('2023-01-01 01:00:00', 'day1_1'), ('2023-01-01 01:00:00', 'day1_2'), ('2023-02-01 06:00:00', 'day2')) AS t(ts, c1)"
                             + " PARTITIONED BY HOUR")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(RowSignature.builder().add("__time", ColumnType.LONG).build())
                     .setQueryContext(context)
                     .setExpectedMSQFault(
                         new TooManySegmentsInTimeChunkFault(
                             DateTimes.of("2023-01-01T01:00:00.000Z"),
                             2,
                             1,
                             Granularities.HOUR
                         )
                     )
                     .verifyResults();

  }

  @Test
  public void testReplaceWithMaxNumSegments()
  {
    final Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                                    .putAll(DEFAULT_MSQ_CONTEXT)
                                                    .put("maxNumSegments", 1)
                                                    .build();

    final RowSignature expectedRowSignature = RowSignature.builder()
                                                          .add("__time", ColumnType.LONG)
                                                          .add("c1", ColumnType.STRING)
                                                          .build();

    // Ingest query should generate at most 1 segment for all the rows.
    testIngestQuery().setSql("REPLACE INTO foo"
                             + " OVERWRITE ALL"
                             + " SELECT TIME_PARSE(ts) AS __time, c1 "
                             + " FROM (VALUES('2023-01-01', 'day1_1'), ('2023-01-01', 'day1_2'), ('2023-02-01', 'day2')) AS t(ts, c1)"
                             + " LIMIT 10"
                             + " PARTITIONED BY ALL")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(expectedRowSignature)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.ETERNITY, "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{1672531200000L, "day1_1"},
                             new Object[]{1672531200000L, "day1_2"},
                             new Object[]{1675209600000L, "day2"}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceLimitWithPeriodGranularityThrowsException(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "LIMIT 50"
                             + "PARTITIONED BY MONTH")
                     .setQueryContext(context)
                     .setExpectedValidationErrorMatcher(invalidSqlContains(
                         "INSERT and REPLACE queries cannot have a LIMIT unless PARTITIONED BY is \"ALL\""
                     ))
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOffsetThrowsException(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "LIMIT 50 "
                             + "OFFSET 10"
                             + "PARTITIONED BY ALL TIME")
                     .setExpectedValidationErrorMatcher(invalidSqlContains(
                         "INSERT and REPLACE queries cannot have an OFFSET"
                     ))
                     .setQueryContext(context)
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceTimeChunks(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    final DataSegment existingDataSegment = DataSegment.builder()
                                                       .dataSource("foo")
                                                       .interval(Intervals.of("2000-01-01/2000-01-04"))
                                                       .version(MSQTestTaskActionClient.VERSION)
                                                       .size(1)
                                                       .build();
    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction(
               EasyMock.eq("foo"),
               EasyMock.eq(ImmutableList.of(Intervals.of("2000-01-01/2000-03-01")))
           ));

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-03-01'"
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-01-03' "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Collections.singletonList(Intervals.of("2000-01-01T/2000-03-01T")))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f}
                         ))
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.of("2000-01-01T/2000-03-01T")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFoo1WithLimit(String contextName, Map<String, Object> context)
  {
    Map<String, Object> queryContext = ImmutableMap.<String, Object>builder()
                                                   .putAll(context)
                                                   .put(MultiStageQueryContext.CTX_ROWS_PER_SEGMENT, 2)
                                                   .build();

    List<Object[]> expectedRows = ImmutableList.of(
        new Object[]{946684800000L, ""},
        new Object[]{978307200000L, "1"},
        new Object[]{946771200000L, "10.1"},
        new Object[]{946857600000L, "2"}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .build();

    testIngestQuery().setSql(
                         "REPLACE INTO \"foo1\" OVERWRITE ALL\n"
                         + "SELECT\n"
                         + "  \"__time\",\n"
                         + "  \"dim1\"\n"
                         + "FROM foo\n"
                         + "LIMIT 4\n"
                         + "PARTITIONED BY ALL\n"
                         + "CLUSTERED BY dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(queryContext)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0), SegmentId.of("foo1", Intervals.ETERNITY, "test", 1)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             DimensionRangeShardSpec.class.getSimpleName(),
                             "Using 'range' shard specs with all CLUSTERED BY fields."
                         )
                     )
                     .setExpectedLastCompactionState(expectedCompactionState(
                         queryContext,
                         Collections.singletonList("dim1"),
                         Collections.singletonList(new StringDimensionSchema("dim1")),
                         GranularityType.ALL,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceTimeChunksLargerThanData(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();
    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction(
               EasyMock.eq("foo"),
               EasyMock.eq(ImmutableList.of(Intervals.of("2000/2002")))
           ));

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2002-01-01'"
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-01-03' "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Collections.singletonList(Intervals.of("2000-01-01T/2002-01-01T")))
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.of("2001-01-01T/2001-02-01T")))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f}
                         )
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.of("2000-01-01T/2002-01-01T")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceAllOverEternitySegment(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    // Create a datasegment which lies partially outside the generated segment
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.ETERNITY)
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "WHERE __time >= TIMESTAMP '2000-01-01' AND __time < TIMESTAMP '2000-01-03' "
                             + "PARTITIONED BY MONTH")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Collections.singletonList(Intervals.ETERNITY))
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("%s/%s", Intervals.ETERNITY.getStart(), "2000-01-01"),
                             Intervals.of("%s/%s", "2000-02-01", Intervals.ETERNITY.getEnd())
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f}
                         )
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.MONTH,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFoo1Range(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "REPLACE INTO foo1 OVERWRITE ALL "
                         + "select  __time, dim1 , count(*) as cnt from foo  where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             DimensionRangeShardSpec.class.getSimpleName(),
                             "Using 'range' shard specs with all CLUSTERED BY fields."
                         )
                     )
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedFooRows())
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("dim1"),
                             Arrays.asList(
                                 new StringDimensionSchema("dim1"),
                                 new LongDimensionSchema("cnt")
                             ),
                             GranularityType.DAY,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceOnFoo1RangeClusteredBySubset(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("m1", ColumnType.FLOAT)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "REPLACE INTO foo1\n"
                         + "OVERWRITE ALL\n"
                         + "SELECT dim1, m1, COUNT(*) AS cnt\n"
                         + "FROM foo\n"
                         + "GROUP BY dim1, m1\n"
                         + "PARTITIONED BY ALL\n"
                         + "CLUSTERED BY dim1"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{0L, "", 1.0f, 1L},
                             new Object[]{0L, "1", 4.0f, 1L},
                             new Object[]{0L, "10.1", 2.0f, 1L},
                             new Object[]{0L, "2", 3.0f, 1L},
                             new Object[]{0L, "abc", 6.0f, 1L},
                             new Object[]{0L, "def", 5.0f, 1L}
                         )
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("dim1"),
                             Arrays.asList(
                                 new StringDimensionSchema("dim1"),
                                 new FloatDimensionSchema("m1"),
                                 new LongDimensionSchema("cnt")
                             ),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceSegmentsInsertIntoNewTable(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(" REPLACE INTO foobar "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL TIME ")
                     .setExpectedDataSource("foobar")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foobar", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978480000000L, 6.0f}
                         )
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("m1")),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceSegmentsWithQuarterSegmentGranularity(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .add("m2", ColumnType.DOUBLE)
                                            .build();

    testIngestQuery().setSql(" REPLACE INTO foobar "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1, m2 "
                             + "FROM foo "
                             + "PARTITIONED by TIME_FLOOR(__time, 'P3M') ")
                     .setExpectedDataSource("foobar")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of(
                                 "foobar",
                                 Intervals.of(
                                     "2000-01-01T00:00:00.000Z/2000-04-01T00:00:00.000Z"),
                                 "test",
                                 0
                             ),
                             SegmentId.of(
                                 "foobar",
                                 Intervals.of(
                                     "2001-01-01T00:00:00.000Z/2001-04-01T00:00:00.000Z"),
                                 "test",
                                 0
                             )
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f, 1.0},
                             new Object[]{946771200000L, 2.0f, 2.0},
                             new Object[]{946857600000L, 3.0f, 3.0},
                             new Object[]{978307200000L, 4.0f, 4.0},
                             new Object[]{978393600000L, 5.0f, 5.0},
                             new Object[]{978480000000L, 6.0f, 6.0}
                         )
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Arrays.asList(new FloatDimensionSchema("m1"), new DoubleDimensionSchema("m2")),
                             GranularityType.QUARTER,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceWithClusteredByDescendingThrowsException(String contextName, Map<String, Object> context)
  {
    // Add a DESC clustered by column, which should not be allowed
    testIngestQuery().setSql(" REPLACE INTO foobar "
                             + "OVERWRITE ALL "
                             + "SELECT __time, m1, m2 "
                             + "FROM foo "
                             + "PARTITIONED BY ALL TIME "
                             + "CLUSTERED BY m2, m1 DESC"
                     )
                     .setExpectedValidationErrorMatcher(
                         invalidSqlIs("Invalid CLUSTERED BY clause [`m1` DESC]: cannot sort in descending order.")
                     )
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceUnnestSegmentEntireTable(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.STRING)
                                            .build();

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, d "
                             + "FROM foo, UNNEST(MV_TO_ARRAY(dim3)) as unnested(d) "
                             + "PARTITIONED BY ALL TIME ")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "CLUSTERED BY clause is empty."
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, "a"},
                             new Object[]{946684800000L, "b"},
                             new Object[]{946771200000L, "b"},
                             new Object[]{946771200000L, "c"},
                             new Object[]{946857600000L, "d"},
                             new Object[]{978307200000L, ""},
                             new Object[]{978393600000L, null},
                             new Object[]{978480000000L, null}
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo", Intervals.ETERNITY, "test", 0)))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(8).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(8).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(8),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new StringDimensionSchema("d")),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceUnnestWithVirtualColumnSegmentEntireTable(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE ALL "
                             + "SELECT __time, d "
                             + "FROM foo, UNNEST(ARRAY[m1, m2]) as unnested(d) "
                             + "PARTITIONED BY ALL TIME ")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(Intervals.ONLY_ETERNITY)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-01T/P1M"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946684800000L, 1.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946771200000L, 2.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{946857600000L, 3.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978307200000L, 4.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978393600000L, 5.0f},
                             new Object[]{978480000000L, 6.0f},
                             new Object[]{978480000000L, 6.0f}
                         )
                     )
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo", Intervals.ETERNITY, "test", 0)))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(12).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(12).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(12),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.emptyList(),
                             Collections.singletonList(new FloatDimensionSchema("d")),
                             GranularityType.ALL,
                             Intervals.ETERNITY
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceUnnestSegmentWithTimeFilter(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.STRING)
                                            .build();

    DataSegment existingDataSegment0 = DataSegment.builder()
                                                  .interval(Intervals.of("2000-01-01T/2000-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();
    DataSegment existingDataSegment1 = DataSegment.builder()
                                                  .interval(Intervals.of("2001-01-01T/2001-01-04T"))
                                                  .size(50)
                                                  .version(MSQTestTaskActionClient.VERSION)
                                                  .dataSource("foo")
                                                  .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment0, existingDataSegment1))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction(
               EasyMock.eq("foo"),
               EasyMock.eq(ImmutableList.of(Intervals.of("1999/2002")))
           ));

    testIngestQuery().setSql(" REPLACE INTO foo "
                             + "OVERWRITE WHERE __time >= TIMESTAMP '1999-01-01 00:00:00' and __time < TIMESTAMP '2002-01-01 00:00:00'"
                             + "SELECT __time, d "
                             + "FROM foo, UNNEST(MV_TO_ARRAY(dim3)) as unnested(d) "
                             + "PARTITIONED BY DAY CLUSTERED BY d ")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of(
                         "1999-01-01T00:00:00.000Z/2002-01-01T00:00:00.000Z")))
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946684800000L, "a"},
                             new Object[]{946684800000L, "b"},
                             new Object[]{946771200000L, "b"},
                             new Object[]{946771200000L, "c"},
                             new Object[]{946857600000L, "d"},
                             new Object[]{978307200000L, ""},
                             new Object[]{978393600000L, null},
                             new Object[]{978480000000L, null}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2, 2, 1, 1, 1, 1).frames(1, 1, 1, 1, 1, 1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(2, 2, 1, 1, 1, 1).frames(1, 1, 1, 1, 1, 1),
                         1, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(8),
                         1, 0
                     )
                     .setExpectedLastCompactionState(
                         expectedCompactionState(
                             context,
                             Collections.singletonList("d"),
                             Collections.singletonList(new StringDimensionSchema("d")),
                             GranularityType.DAY,
                             Intervals.of("1999-01-01T00:00:00.000Z/2002-01-01T00:00:00.000Z")
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceTombstonesOverPartiallyOverlappingSegments(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    // Create a datasegment which lies partially outside the generated segment
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.of("2001-01-01T/2003-01-04T"))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(new RetrieveUsedSegmentsAction(
               EasyMock.eq("foo1"),
               EasyMock.eq(ImmutableList.of(Intervals.of("2000/2002")))
           ));

    List<Object[]> expectedResults = ImmutableList.of(
        new Object[]{946684800000L, "", 1L},
        new Object[]{946771200000L, "10.1", 1L},
        new Object[]{946857600000L, "2", 1L},
        new Object[]{978307200000L, "1", 1L},
        new Object[]{978393600000L, "def", 1L},
        new Object[]{978480000000L, "abc", 1L}
    );

    testIngestQuery().setSql(
                         "REPLACE INTO foo1 "
                         + "OVERWRITE WHERE __time >= TIMESTAMP '2000-01-01 00:00:00' and __time < TIMESTAMP '2002-01-01 00:00:00'"
                         + "SELECT  __time, dim1 , count(*) as cnt "
                         + "FROM foo "
                         + "WHERE dim1 IS NOT NULL "
                         + "GROUP BY 1, 2 "
                         + "PARTITIONED by TIME_FLOOR(__time, 'P3M') "
                         + "CLUSTERED by dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedShardSpec(DimensionRangeShardSpec.class)
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("2001-04-01/P3M"),
                             Intervals.of("2001-07-01/P3M"),
                             Intervals.of("2001-10-01/P3M")
                         )
                     )
                     .setExpectedResultRows(expectedResults)
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.singletonList("dim1"),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.QUARTER,
                         Intervals.of("2000-01-01T00:00:00.000Z/2002-01-01T00:00:00.000Z")
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAll(String contextName, Map<String, Object> context)
  {
    // An empty replace all with no used segment should effectively be the same as an empty insert
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceInterval(String contextName, Map<String, Object> context)
  {
    // An empty replace interval with no used segment should effectively be the same as an empty insert
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE WHERE __time >= TIMESTAMP '2016-06-27 01:00:00.00' AND __time < TIMESTAMP '2016-06-27 02:00:00.00'"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY HOUR"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAllOverExistingSegment(String contextName, Map<String, Object> context)
  {
    Interval existingSegmentInterval = Intervals.of("2001-01-01T/2001-01-02T");
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(existingSegmentInterval)
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(ImmutableSet.of(existingSegmentInterval))
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.DAY,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceIntervalOverPartiallyOverlappingSegment(String contextName, Map<String, Object> context)
  {
    // Create a data segment which lies partially outside the generated segment
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.of("2016-06-27T/2016-06-28T"))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE WHERE __time >= TIMESTAMP '2016-06-27 01:00:00.00' AND __time < TIMESTAMP '2016-06-27 02:00:00.00'"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY HOUR"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("2016-06-27T01:00:00/2016-06-27T02:00:00")
                         )
                     )
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.HOUR,
                         Intervals.of("2016-06-27T01:00:00/2016-06-27T02:00:00")
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceIntervalOverPartiallyOverlappingStart(String contextName, Map<String, Object> context)
  {
    // Create a data segment whose start partially lies outside the query's replace interval
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.of("2016-06-01T/2016-07-01T"))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE WHERE __time >= TIMESTAMP '2016-06-29' AND __time < TIMESTAMP '2016-07-03'"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("2016-06-29T/2016-06-30T"),
                             Intervals.of("2016-06-30T/2016-07-01T")
                         )
                     )
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.DAY,
                         Intervals.of("2016-06-29T00:00:00.000Z/2016-07-03T00:00:00.000Z")
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceIntervalOverPartiallyOverlappingEnd(String contextName, Map<String, Object> context)
  {
    // Create a data segment whose end partially lies outside the query's replace interval
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.of("2016-06-01T/2016-07-01T"))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE WHERE __time >= TIMESTAMP '2016-05-25' AND __time < TIMESTAMP '2016-06-03'"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("2016-06-01T/2016-06-02T"),
                             Intervals.of("2016-06-02T/2016-06-03T")
                         )
                     )
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.DAY,
                         Intervals.of("2016-05-25T00:00:00.000Z/2016-06-03T00:00:00.000Z")
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAllOverEternitySegment(String contextName, Map<String, Object> context)
  {
    // Create a data segment spanning eternity
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.ETERNITY)
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(
               ArgumentMatchers.argThat(
                   (ArgumentMatcher<TaskAction<?>>) argument ->
                       argument instanceof RetrieveUsedSegmentsAction
                       && "foo1".equals(((RetrieveUsedSegmentsAction) argument).getDataSource())
               ));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.ETERNITY))
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.DAY,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }


  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAllWithAllGrainOverFiniteIntervalSegment(String contextName, Map<String, Object> context)
  {
    // Create a finite-interval segment
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.of("2016-06-01T/2016-09-01T"))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();
    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.argThat(
               (ArgumentMatcher<TaskAction<?>>) argument ->
                   argument instanceof RetrieveUsedSegmentsAction
                   && "foo1".equals(((RetrieveUsedSegmentsAction) argument).getDataSource())));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.of("2016-06-01T/2016-09-01T")))
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.ALL,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAllWithAllGrainOverEternitySegment(String contextName, Map<String, Object> context)
  {
    // Create a segment spanning eternity
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.ETERNITY)
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.argThat(
               (ArgumentMatcher<TaskAction<?>>) argument ->
                   argument instanceof RetrieveUsedSegmentsAction
                   && "foo1".equals(((RetrieveUsedSegmentsAction) argument).getDataSource())));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.ETERNITY))
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.ALL,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceAllWithAllGrainOverHalfEternitySegment(String contextName, Map<String, Object> context)
  {
    // Create a segment spanning half-eternity
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(new Interval(DateTimes.of("2000"), DateTimes.MAX))
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();
    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.isA(RetrieveUsedSegmentsAction.class));

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(ImmutableSet.of(Intervals.ETERNITY))
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.ALL,
                         Intervals.ETERNITY
                     ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceLimitQuery(String contextName, Map<String, Object> context)
  {
    // A limit query which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "REPLACE INTO foo1 "
                         + " OVERWRITE ALL"
                         + " SELECT  __time, dim1, COUNT(*) AS cnt"
                         + " FROM foo WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " LIMIT 100"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyReplaceIntervalOverEternitySegment(String contextName, Map<String, Object> context)
  {
    // Create a data segment spanning eternity
    DataSegment existingDataSegment = DataSegment.builder()
                                                 .interval(Intervals.ETERNITY)
                                                 .size(50)
                                                 .version(MSQTestTaskActionClient.VERSION)
                                                 .dataSource("foo1")
                                                 .build();

    Mockito.doReturn(ImmutableSet.of(existingDataSegment))
           .when(testTaskActionClient)
           .submit(ArgumentMatchers.argThat(
               (ArgumentMatcher<TaskAction<?>>) argument ->
                   argument instanceof RetrieveUsedSegmentsAction
                   && "foo1".equals(((RetrieveUsedSegmentsAction) argument).getDataSource())));

    // Insert with a condition which results in 0 rows being inserted -- do nothing!
    testIngestQuery().setSql(
                         "REPLACE INTO foo1"
                         + " OVERWRITE WHERE __time >= TIMESTAMP '2016-06-01' AND __time < TIMESTAMP '2016-06-03'"
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo"
                         + " WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY DAY"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo1")
                     .setExpectedResultRows(ImmutableList.of())
                     .setExpectedTombstoneIntervals(
                         ImmutableSet.of(
                             Intervals.of("2016-06-01T/2016-06-02T"),
                             Intervals.of("2016-06-02T/2016-06-03T")
                         )
                     )
                     .setExpectedLastCompactionState(expectedCompactionState(
                         context,
                         Collections.emptyList(),
                         ImmutableList.of(
                             new StringDimensionSchema("dim1"),
                             new LongDimensionSchema("cnt")
                         ),
                         GranularityType.DAY,
                         Intervals.of("2016-06-01T00:00:00.000Z/2016-06-03T00:00:00.000Z")
                     ))
                     .verifyResults();
  }

  @Test
  void testRealtimeQueryWithReindexShouldThrowException()
  {
    Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                              .putAll(DEFAULT_MSQ_CONTEXT)
                                              .put(MultiStageQueryContext.CTX_INCLUDE_SEGMENT_SOURCE, SegmentSource.REALTIME.name())
                                              .build();

    testIngestQuery().setSql(
                         "REPLACE INTO foo"
                         + " OVERWRITE ALL"
                         + " SELECT *"
                         + " FROM foo"
                         + " PARTITIONED BY DAY")
                     .setQueryContext(context)
                     .setExpectedValidationErrorMatcher(
                         new DruidExceptionMatcher(
                             DruidException.Persona.USER,
                             DruidException.Category.INVALID_INPUT,
                             "general"
                         ).expectMessageContains(
                             "Cannot ingest into datasource[foo] since it is also being queried from, with REALTIME "
                             + "segments included. Ingest to a different datasource, or disable querying of realtime "
                             + "segments by modifying [includeSegmentSource] in the query context.")
                     )
                     .verifyPlanningErrors();

  }

  @Nonnull
  private Set<SegmentId> expectedFooSegments()
  {
    return new TreeSet<>(
        ImmutableSet.of(
            SegmentId.of("foo1", Intervals.of("2000-01-01T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2000-01-02T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2000-01-03T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-01T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-02T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-03T/P1D"), "test", 0)
        )
    );
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testReplaceMetrics(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(
                         " REPLACE INTO foo OVERWRITE WHERE __time >= TIMESTAMP '2000-01-02' AND __time < TIMESTAMP '2000-01-03' "
                         + "SELECT __time, m1 "
                         + "FROM foo "
                         + "WHERE __time >= TIMESTAMP '2000-01-02' AND __time < TIMESTAMP '2000-01-03' "
                         + "PARTITIONED by DAY ")
                     .setExpectedDataSource("foo")
                     .setExpectedDestinationIntervals(ImmutableList.of(Intervals.of(
                         "2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z")))
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo",
                         Intervals.of("2000-01-02T/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{946771200000L, 2.0f}))
                     .setExpectedMetricDimensions(
                         Map.of(
                             DruidMetrics.DATASOURCE, "foo",
                             DruidMetrics.INTERVAL, List.of("2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z"),
                             DruidMetrics.DURATION, Duration.standardDays(1),
                             DruidMetrics.SUCCESS, true
                             )
                     )
                     .verifyResults();
  }

  @Nonnull
  private List<Object[]> expectedFooRows()
  {
    return ImmutableList.of(
        new Object[]{946684800000L, "", 1L},
        new Object[]{946771200000L, "10.1", 1L},
        new Object[]{946857600000L, "2", 1L},
        new Object[]{978307200000L, "1", 1L},
        new Object[]{978393600000L, "def", 1L},
        new Object[]{978480000000L, "abc", 1L}
    );
  }

  private CompactionState expectedCompactionState(
      Map<String, Object> context,
      List<String> partitionDimensions,
      List<DimensionSchema> dimensions,
      GranularityType segmentGranularity,
      Interval interval
  )
  {
    return expectedCompactionState(
        context,
        partitionDimensions,
        new DimensionsSpec.Builder().setDimensions(dimensions)
                                    .setDimensionExclusions(Collections.singletonList("__time"))
                                    .build(),
        segmentGranularity,
        interval
    );
  }

  private CompactionState expectedCompactionState(
      Map<String, Object> context,
      List<String> partitionDimensions,
      DimensionsSpec dimensionsSpec,
      GranularityType segmentGranularity,
      Interval interval
  )
  {
    if (!context.containsKey(Tasks.STORE_COMPACTION_STATE_KEY)
        || !((Boolean) context.get(Tasks.STORE_COMPACTION_STATE_KEY))) {
      return null;
    }
    PartitionsSpec partitionsSpec;
    if (partitionDimensions.isEmpty()) {
      partitionsSpec = new DynamicPartitionsSpec(
          MultiStageQueryContext.getRowsPerSegment(QueryContext.of(context)),
          Long.MAX_VALUE
      );
    } else {
      partitionsSpec = new DimensionRangePartitionsSpec(
          null,
          MultiStageQueryContext.getRowsPerSegment(QueryContext.of(context)),
          partitionDimensions,
          false
      );
    }

    IndexSpec indexSpec = IndexSpec.DEFAULT;
    GranularitySpec granularitySpec = new UniformGranularitySpec(
        segmentGranularity.getDefaultGranularity(),
        GranularityType.NONE.getDefaultGranularity(),
        false,
        Collections.singletonList(interval)
    );
    List<AggregatorFactory> metricsSpec = Collections.emptyList();

    return new CompactionState(
        partitionsSpec,
        dimensionsSpec,
        metricsSpec,
        null,
        indexSpec,
        granularitySpec,
        null
    );

  }
}
