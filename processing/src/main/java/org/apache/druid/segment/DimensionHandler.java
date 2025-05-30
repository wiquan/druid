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

package org.apache.druid.segment;

import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionSchema.MultiValueHandling;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.selector.settable.SettableColumnValueSelector;
import org.apache.druid.segment.writeout.SegmentWriteOutMedium;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Comparator;

/**
 * Processing related interface
 *
 * A DimensionHandler is an object that encapsulates indexing, column merging/building, and querying operations
 * for a given dimension type (e.g., dict-encoded String, Long).
 *
 * These operations are handled by sub-objects created through a DimensionHandler's methods:
 *   DimensionIndexer, DimensionMerger, and DimensionColumnReader, respectively.
 *
 * Each DimensionHandler object is associated with a single dimension.
 *
 * This interface allows type-specific behavior column logic, such as choice of indexing structures and disk formats.
 * to be contained within a type-specific set of handler objects, simplifying processing classes
 * such as {@link org.apache.druid.segment.incremental.IncrementalIndex} and {@link IndexMerger} and allowing for abstracted
 * development of additional dimension types.
 *
 * A DimensionHandler is a stateless object, and thus thread-safe; its methods should be pure functions.
 *
 * The EncodedType and ActualType are Comparable because columns used as dimensions must have sortable values.
 *
 * @param <EncodedType> class of a single encoded value
 * @param <EncodedKeyComponentType> A row key contains a component for each dimension, this param specifies the
 *                                 class of this dimension's key component. A column type that supports multivalue rows
 *                                 should use an array type (Strings would use int[]). Column types without multivalue
 *                                 row support should use single objects (e.g., Long, Float).
 * @param <ActualType> class of a single actual value
 */
public interface DimensionHandler
    <EncodedType extends Comparable<EncodedType>, EncodedKeyComponentType, ActualType extends Comparable<ActualType>>
{
  /**
   * Get the name of the column associated with this handler.
   *
   * This string would be the output name of the column during ingestion, and the name of an input column when querying.
   *
   * @return Dimension name
   */
  String getDimensionName();

  /**
   * Create a {@link DimensionSpec} for this handler
   */
  default DimensionSpec getDimensionSpec()
  {
    return DefaultDimensionSpec.of(getDimensionName());
  }

  DimensionSchema getDimensionSchema(ColumnCapabilities capabilities);

  /**
   * Get {@link MultiValueHandling} for the column associated with this handler.
   * Only string columns can have {@link MultiValueHandling} currently.
   */
  @Nullable
  default MultiValueHandling getMultivalueHandling()
  {
    return null;
  }

  /**
   * Creates a new DimensionIndexer, a per-dimension object responsible for processing ingested rows in-memory, used
   * by the IncrementalIndex. See {@link DimensionIndexer} interface for more information.
   *
   * @return A new DimensionIndexer object.
   */
  DimensionIndexer<EncodedType, EncodedKeyComponentType, ActualType> makeIndexer();

  /**
   * @deprecated use {@link #makeMerger(String, IndexSpec, SegmentWriteOutMedium, ColumnCapabilities, ProgressIndicator, File, Closer)}
   *
   * This method exists for backwards compatiblity with older versions of Druid since this is an unofficial extension
   * point that must be implemented to create custom dimension types, and will be removed in a future release.
   */
  @Deprecated
  default DimensionMergerV9 makeMerger(
      String outputName,
      IndexSpec indexSpec,
      SegmentWriteOutMedium segmentWriteOutMedium,
      ColumnCapabilities capabilities,
      ProgressIndicator progress,
      Closer closer
  )
  {
    throw DruidException.defensive(
        "this method is no longer supported, use makeMerger(String, IndexSpec, SegmentWriteOutMedium, ColumnCapabilities, ProgressIndicator, File, Closer) instead"
    );
  }


  /**
   * Creates a new DimensionMergerV9, a per-dimension object responsible for merging indexes/row data across segments
   * and building the on-disk representation of a dimension. For use with IndexMergerV9 only.
   *
   * See {@link DimensionMergerV9} interface for more information.
   *
   * @param outputName            Output "file" name for the column to use for serializers, to control where it is
   *                              stored in the segments internal files
   * @param indexSpec             Specification object for the index merge
   * @param segmentWriteOutMedium this SegmentWriteOutMedium object could be used internally in the created merger, if
   *                              needed
   * @param capabilities          The ColumnCapabilities of the dimension represented by this DimensionHandler
   * @param progress              ProgressIndicator used by the merging process
   * @param segmentBaseDir        segment write out path; temporary files may be created here, though should delete
   *                              after merge is finished OR be registered with the Closer parameter
   * @param closer                Closer tied to segment completion. Anything which is not cleaned up inside of the
   *                              merger after merge is complete should be registered with this closer. For example,
   *                              resources which are required for final serialization of the column
   * @return A new DimensionMergerV9 object.
   */
  default DimensionMergerV9 makeMerger(
      String outputName,
      IndexSpec indexSpec,
      SegmentWriteOutMedium segmentWriteOutMedium,
      ColumnCapabilities capabilities,
      ProgressIndicator progress,
      File segmentBaseDir,
      Closer closer
  )
  {
    return makeMerger(
        outputName,
        indexSpec,
        segmentWriteOutMedium,
        capabilities,
        progress,
        closer
    );
  }

  /**
   * Given an key component representing a single set of row value(s) for this dimension as an Object,
   * return the length of the key component after appropriate type-casting.
   *
   * For example, a dictionary encoded String dimension would receive an int[] as input to this method,
   * while a Long numeric dimension would receive a single Long object (no multivalue support)
   *
   * @param dimVals Values for this dimension from a row
   * @return Size of dimVals
   */
  int getLengthOfEncodedKeyComponent(EncodedKeyComponentType dimVals);

  /**
   * Returns a comparator that knows how to compare {@link ColumnValueSelector} of the assumed dimension type,
   * corresponding to this DimensionHandler. E. g. {@link StringDimensionHandler} returns a comparator, that compares
   * {@link ColumnValueSelector}s as {@link DimensionSelector}s.
   *
   * The comparison rules used by this method should match the rules used by
   * {@link DimensionIndexer#compareUnsortedEncodedKeyComponents}, otherwise incorrect ordering/merging of rows
   * can occur during ingestion, causing issues such as imperfect rollup.
   */
  Comparator<ColumnValueSelector> getEncodedValueSelectorComparator();

  /**
   * Creates and returns a new object of some implementation of {@link SettableColumnValueSelector}, that corresponds
   * to the type of this DimensionHandler. E. g. {@link LongDimensionHandler} returns {@link
   * org.apache.druid.segment.selector.settable.SettableLongColumnValueSelector}, etc.
   */
  SettableColumnValueSelector makeNewSettableEncodedValueSelector();
}
