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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.msq.indexing.MSQSpec;
import org.apache.druid.msq.input.InputSpecSlicer;
import org.apache.druid.msq.input.table.SegmentsInputSlice;
import org.apache.druid.msq.input.table.TableInputSpec;
import org.apache.druid.msq.kernel.controller.ControllerQueryKernelConfig;
import org.apache.druid.server.DruidNode;

import java.io.File;

/**
 * Context used by multi-stage query controllers. Useful because it allows test fixtures to provide their own
 * implementations.
 */
public interface ControllerContext
{
  /**
   * Globally unique identifier for the query handled by this controller. This is used to set
   * {@link org.apache.druid.msq.kernel.QueryDefinition#getQueryId}. Must be globally unique because this is used for
   * identifying workers, naming temporary files, etc.
   */
  String queryId();

  /**
   * Configuration for {@link org.apache.druid.msq.kernel.controller.ControllerQueryKernel}.
   */
  ControllerQueryKernelConfig queryKernelConfig(MSQSpec querySpec);

  /**
   * Callback from the controller implementation to "register" the controller. Used in the indexing task implementation
   * to set up the task chat web service.
   */
  void registerController(Controller controller, Closer closer);

  /**
   * JSON-enabled object mapper.
   */
  ObjectMapper jsonMapper();

  /**
   * Emit the metric in the {@link MSQMetriceEventBuilder} using a {@link ServiceEmitter}. Might sets up addtional
   * context dependant dimensions.
   */
  void emitMetric(MSQMetriceEventBuilder metricBuilder);

  /**
   * Provides a way for tasks to request injectable objects. Useful because tasks are not able to request injection
   * at the time of server startup, because the server doesn't know what tasks it will be running.
   */
  Injector injector();

  /**
   * Fetch node info about self.
   */
  DruidNode selfNode();

  /**
   * Provides an {@link InputSpecSlicer} that slices {@link TableInputSpec} into {@link SegmentsInputSlice}.
   */
  InputSpecSlicer newTableInputSpecSlicer(WorkerManager workerManager);

  /**
   * Provide access to segment actions in the Overlord. Only called for ingestion queries, i.e., where
   * {@link MSQSpec#getDestination()} is {@link org.apache.druid.msq.indexing.destination.DataSourceMSQDestination}.
   */
  TaskActionClient taskActionClient();

  /**
   * Task lock type.
   */
  TaskLockType taskLockType();

  /**
   * Provides services about workers: starting, canceling, obtaining status.
   *
   * @param queryId           query ID
   * @param querySpec         query spec
   * @param queryKernelConfig config from {@link #queryKernelConfig(MSQSpec)}
   */
  WorkerManager newWorkerManager(
      String queryId,
      MSQSpec querySpec,
      ControllerQueryKernelConfig queryKernelConfig
  );

  /**
   * Fetch a directory for temporary outputs
   */
  default File taskTempDir()
  {
    throw new UnsupportedOperationException();
  }

  /**
   * Client for communicating with workers.
   */
  WorkerClient newWorkerClient();
}
