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

import React from 'react';

import type { IngestionSpec } from '../../../druid-models';
import { getConsoleViewIcon } from '../../../druid-models';
import type { Capabilities } from '../../../helpers';
import { useQueryManager } from '../../../hooks';
import { getApiArray, partition, pluralIfNeeded, queryDruidSql } from '../../../utils';
import { HomeViewCard } from '../home-view-card/home-view-card';

export interface SupervisorCounts {
  running: number;
  suspended: number;
}

export interface SupervisorsCardProps {
  capabilities: Capabilities;
}

export const SupervisorsCard = React.memo(function SupervisorsCard(props: SupervisorsCardProps) {
  const [supervisorCountState] = useQueryManager<Capabilities, SupervisorCounts>({
    processQuery: async (capabilities, cancelToken) => {
      if (capabilities.hasSql()) {
        return (
          await queryDruidSql(
            {
              query: `SELECT
  COUNT(*) FILTER (WHERE "suspended" = 0) AS "running",
  COUNT(*) FILTER (WHERE "suspended" = 1) AS "suspended"
FROM sys.supervisors`,
            },
            cancelToken,
          )
        )[0];
      } else if (capabilities.hasOverlordAccess()) {
        const supervisors = await getApiArray<{ spec: IngestionSpec }>(
          '/druid/indexer/v1/supervisor?full',
          cancelToken,
        );
        const [running, suspended] = partition(supervisors, d => !d.spec.suspended);
        return {
          running: running.length,
          suspended: suspended.length,
        };
      } else {
        throw new Error(`must have SQL or overlord access`);
      }
    },
    initQuery: props.capabilities,
  });

  const { running, suspended } = supervisorCountState.data || {
    running: 0,
    suspended: 0,
  };

  return (
    <HomeViewCard
      className="supervisors-card"
      href="#supervisors"
      icon={getConsoleViewIcon('supervisors')}
      title="Supervisors"
      loading={supervisorCountState.loading}
      error={supervisorCountState.error}
    >
      {!(running + suspended) && <p>No supervisors</p>}
      {Boolean(running) && <p>{pluralIfNeeded(running, 'running supervisor')}</p>}
      {Boolean(suspended) && <p>{pluralIfNeeded(suspended, 'suspended supervisor')}</p>}
    </HomeViewCard>
  );
});
