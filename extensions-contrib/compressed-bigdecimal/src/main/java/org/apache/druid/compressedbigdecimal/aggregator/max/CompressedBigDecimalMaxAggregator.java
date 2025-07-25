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

package org.apache.druid.compressedbigdecimal.aggregator.max;

import org.apache.druid.compressedbigdecimal.ArrayCompressedBigDecimal;
import org.apache.druid.compressedbigdecimal.CompressedBigDecimal;
import org.apache.druid.compressedbigdecimal.Utils;
import org.apache.druid.compressedbigdecimal.aggregator.CompressedBigDecimalAggregatorBase;
import org.apache.druid.segment.ColumnValueSelector;

/**
 * An Aggregator to aggregate big decimal values.
 */
public class CompressedBigDecimalMaxAggregator extends CompressedBigDecimalAggregatorBase
{

  /**
   * Constructor.
   *
   * @param size                the size to allocate
   * @param scale               the scale
   * @param selector            that has the metric value
   * @param strictNumberParsing true => NumberFormatExceptions thrown; false => NumberFormatException returns 0
   */
  public CompressedBigDecimalMaxAggregator(
      int size,
      int scale,
      ColumnValueSelector<CompressedBigDecimal> selector,
      boolean strictNumberParsing
  )
  {
    super(size, scale, selector, strictNumberParsing, CompressedBigDecimalMaxAggregator.class.getSimpleName());
  }

  @Override
  protected CompressedBigDecimal initValue(int size, int scale)
  {
    return ArrayCompressedBigDecimal.allocateMin(size, scale);
  }

  @Override
  public void aggregate()
  {
    CompressedBigDecimal selectedObject = Utils.objToCompressedBigDecimalWithScale(
        selector.getObject(),
        value.getScale(),
        strictNumberParsing
    );

    if (selectedObject != null) {
      empty = false;
      value.accumulateMax(selectedObject);
    }
  }
}
