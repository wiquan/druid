---
id: automatic-compaction-api
title: Automatic compaction API
sidebar_label: Automatic compaction
---
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';


<!--

  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

This topic describes the status and configuration API endpoints for [automatic compaction using Coordinator duties](../data-management/automatic-compaction.md#auto-compaction-using-coordinator-duties) in Apache Druid. You can configure automatic compaction in the Druid web console or API.

:::info[Experimental]

Instead of the automatic compaction API, you can use the supervisor API to submit auto-compaction jobs using compaction supervisors. For more information, see [Auto-compaction using compaction supervisors](../data-management/automatic-compaction.md#auto-compaction-using-compaction-supervisors).

:::

In this topic, `http://ROUTER_IP:ROUTER_PORT` is a placeholder for your Router service address and port. Replace it with the information for your deployment. For example, use `http://localhost:8888` for quickstart deployments.

## Manage automatic compaction

### Create or update automatic compaction configuration

Creates or updates the automatic compaction configuration for a datasource. Pass the automatic compaction as a JSON object in the request body.

The automatic compaction configuration requires only the `dataSource` property. Druid fills all other properties with default values if not specified. See [Automatic compaction dynamic configuration](../configuration/index.md#automatic-compaction-dynamic-configuration) for configuration details.

Note that this endpoint returns an HTTP `200 OK` message code even if the datasource name does not exist.

#### URL

`POST` `/druid/coordinator/v1/config/compaction`

#### Responses

<Tabs>

<TabItem value="1" label="200 SUCCESS">


*Successfully submitted auto compaction configuration*

</TabItem>
</Tabs>

---
#### Sample request

The following example creates an automatic compaction configuration for the datasource `wikipedia_hour`, which was ingested with `HOUR` segment granularity. This automatic compaction configuration performs compaction on `wikipedia_hour`, resulting in compacted segments that represent a day interval of data.

In this example:

* `wikipedia_hour` is a datasource with `HOUR` segment granularity.
* `skipOffsetFromLatest` is set to `PT0S`, meaning that no data is skipped.
* `partitionsSpec` is set to the default `dynamic`, allowing Druid to dynamically determine the optimal partitioning strategy.
* `type` is set to `index_parallel`, meaning that parallel indexing is used.
* `segmentGranularity` is set to `DAY`, meaning that each compacted segment is a day of data.

<Tabs>

<TabItem value="2" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction"\
--header 'Content-Type: application/json' \
--data '{
    "dataSource": "wikipedia_hour",
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "partitionsSpec": {
            "type": "dynamic"
        },
        "type": "index_parallel"
    },
    "granularitySpec": {
        "segmentGranularity": "DAY"
    }
}'
```

</TabItem>
<TabItem value="3" label="HTTP">


```HTTP
POST /druid/coordinator/v1/config/compaction HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
Content-Type: application/json
Content-Length: 281

{
    "dataSource": "wikipedia_hour",
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "partitionsSpec": {
            "type": "dynamic"
        },
        "type": "index_parallel"
    },
    "granularitySpec": {
        "segmentGranularity": "DAY"
    }
}
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.


### Remove automatic compaction configuration

Removes the automatic compaction configuration for a datasource. This updates the compaction status of the datasource to "Not enabled."

#### URL

`DELETE` `/druid/coordinator/v1/config/compaction/{dataSource}`

#### Responses

<Tabs>

<TabItem value="4" label="200 SUCCESS">


*Successfully deleted automatic compaction configuration*

</TabItem>
<TabItem value="5" label="404 NOT FOUND">


*Datasource does not have automatic compaction or invalid datasource name*

</TabItem>
</Tabs>

---


#### Sample request

<Tabs>

<TabItem value="6" label="cURL">


```shell
curl --request DELETE "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction/wikipedia_hour"
```

</TabItem>
<TabItem value="7" label="HTTP">


```HTTP
DELETE /druid/coordinator/v1/config/compaction/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.

### Update capacity for compaction tasks

:::info
This API is now deprecated. Use [Update cluster-level compaction config](#update-cluster-level-compaction-config) instead.
:::

Updates the capacity for compaction tasks. The minimum number of compaction tasks is 1 and the maximum is 2147483647.

Note that while the max compaction tasks can theoretically be set to 2147483647, the practical limit is determined by the available cluster capacity and is capped at 10% of the cluster's total capacity.

#### URL

`POST` `/druid/coordinator/v1/config/compaction/taskslots`

#### Query parameters

To limit the maximum number of compaction tasks, use the optional query parameters `ratio` and `max`:

* `ratio` (optional)
  * Type: Float
  * Default: 0.1
  * Limits the ratio of the total task slots to compaction task slots.
* `max` (optional)
  * Type: Int
  * Default: 2147483647
  * Limits the maximum number of task slots for compaction tasks.

#### Responses

<Tabs>

<TabItem value="8" label="200 SUCCESS">


*Successfully updated compaction configuration*

</TabItem>
<TabItem value="9" label="404 NOT FOUND">


*Invalid `max` value*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="10" label="cURL">


```shell
curl --request POST "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction/taskslots?ratio=0.2&max=250000"
```

</TabItem>
<TabItem value="11" label="HTTP">


```HTTP
POST /druid/coordinator/v1/config/compaction/taskslots?ratio=0.2&max=250000 HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.

## View automatic compaction configuration

### Get all automatic compaction configurations

Retrieves all automatic compaction configurations. Returns a `compactionConfigs` object containing the active automatic compaction configurations of all datasources.

You can use this endpoint to retrieve `compactionTaskSlotRatio` and `maxCompactionTaskSlots` values for managing resource allocation of compaction tasks.

#### URL

`GET` `/druid/coordinator/v1/config/compaction`

#### Responses

<Tabs>

<TabItem value="12" label="200 SUCCESS">


*Successfully retrieved automatic compaction configurations*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="13" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction"
```

</TabItem>
<TabItem value="14" label="HTTP">


```HTTP
GET /druid/coordinator/v1/config/compaction HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "compactionConfigs": [
        {
            "dataSource": "wikipedia_hour",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "PT0S",
            "tuningConfig": {
                "maxRowsInMemory": null,
                "appendableIndexSpec": null,
                "maxBytesInMemory": null,
                "maxTotalRows": null,
                "splitHintSpec": null,
                "partitionsSpec": {
                    "type": "dynamic",
                    "maxRowsPerSegment": 5000000,
                    "maxTotalRows": null
                },
                "indexSpec": null,
                "indexSpecForIntermediatePersists": null,
                "maxPendingPersists": null,
                "pushTimeout": null,
                "segmentWriteOutMediumFactory": null,
                "maxNumConcurrentSubTasks": null,
                "maxRetry": null,
                "taskStatusCheckPeriodMs": null,
                "chatHandlerTimeout": null,
                "chatHandlerNumRetries": null,
                "maxNumSegmentsToMerge": null,
                "totalNumMergeTasks": null,
                "maxColumnsToMerge": null,
                "type": "index_parallel",
                "forceGuaranteedRollup": false
            },
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        },
        {
            "dataSource": "wikipedia",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "PT0S",
            "tuningConfig": {
                "maxRowsInMemory": null,
                "appendableIndexSpec": null,
                "maxBytesInMemory": null,
                "maxTotalRows": null,
                "splitHintSpec": null,
                "partitionsSpec": {
                    "type": "dynamic",
                    "maxRowsPerSegment": 5000000,
                    "maxTotalRows": null
                },
                "indexSpec": null,
                "indexSpecForIntermediatePersists": null,
                "maxPendingPersists": null,
                "pushTimeout": null,
                "segmentWriteOutMediumFactory": null,
                "maxNumConcurrentSubTasks": null,
                "maxRetry": null,
                "taskStatusCheckPeriodMs": null,
                "chatHandlerTimeout": null,
                "chatHandlerNumRetries": null,
                "maxNumSegmentsToMerge": null,
                "totalNumMergeTasks": null,
                "maxColumnsToMerge": null,
                "type": "index_parallel",
                "forceGuaranteedRollup": false
            },
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        }
    ],
    "compactionTaskSlotRatio": 0.1,
    "maxCompactionTaskSlots": 2147483647,

}
```
</details>

### Get automatic compaction configuration

Retrieves the automatic compaction configuration for a datasource.

#### URL

`GET` `/druid/coordinator/v1/config/compaction/{dataSource}`

#### Responses

<Tabs>

<TabItem value="15" label="200 SUCCESS">


*Successfully retrieved configuration for datasource*

</TabItem>
<TabItem value="16" label="404 NOT FOUND">


*Invalid datasource or datasource does not have automatic compaction enabled*

</TabItem>
</Tabs>

---

#### Sample request

The following example retrieves the automatic compaction configuration for datasource `wikipedia_hour`.

<Tabs>

<TabItem value="17" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction/wikipedia_hour"
```

</TabItem>
<TabItem value="18" label="HTTP">


```HTTP
GET /druid/coordinator/v1/config/compaction/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "dataSource": "wikipedia_hour",
    "taskPriority": 25,
    "inputSegmentSizeBytes": 100000000000000,
    "maxRowsPerSegment": null,
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "maxRowsInMemory": null,
        "appendableIndexSpec": null,
        "maxBytesInMemory": null,
        "maxTotalRows": null,
        "splitHintSpec": null,
        "partitionsSpec": {
            "type": "dynamic",
            "maxRowsPerSegment": 5000000,
            "maxTotalRows": null
        },
        "indexSpec": null,
        "indexSpecForIntermediatePersists": null,
        "maxPendingPersists": null,
        "pushTimeout": null,
        "segmentWriteOutMediumFactory": null,
        "maxNumConcurrentSubTasks": null,
        "maxRetry": null,
        "taskStatusCheckPeriodMs": null,
        "chatHandlerTimeout": null,
        "chatHandlerNumRetries": null,
        "maxNumSegmentsToMerge": null,
        "totalNumMergeTasks": null,
        "maxColumnsToMerge": null,
        "type": "index_parallel",
        "forceGuaranteedRollup": false
    },
    "granularitySpec": {
        "segmentGranularity": "DAY",
        "queryGranularity": null,
        "rollup": null
    },
    "dimensionsSpec": null,
    "metricsSpec": null,
    "transformSpec": null,
    "ioConfig": null,
    "taskContext": null
}
```
</details>

### Get automatic compaction configuration history

Retrieves the history of the automatic compaction configuration for a datasource. Returns an empty list if the  datasource does not exist or there is no compaction history for the datasource.

The response contains a list of objects with the following keys:
* `globalConfig`: A JSON object containing automatic compaction configuration that applies to the entire cluster.
* `compactionConfig`: A JSON object containing the automatic compaction configuration for the datasource.
* `auditInfo`: A JSON object containing information about the change made, such as `author`, `comment` or `ip`.
* `auditTime`: The date and time when the change was made.

#### URL

`GET` `/druid/coordinator/v1/config/compaction/{dataSource}/history`

#### Query parameters
* `interval` (optional)
  * Type: ISO-8601
  * Limits the results within a specified interval. Use `/` as the delimiter for the interval string.
* `count` (optional)
  * Type: Int
  * Limits the number of results.

#### Responses

<Tabs>

<TabItem value="19" label="200 SUCCESS">


*Successfully retrieved configuration history*

</TabItem>
<TabItem value="20" label="400 BAD REQUEST">


*Invalid `count` value*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="21" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction/wikipedia_hour/history"
```

</TabItem>
<TabItem value="22" label="HTTP">


```HTTP
GET /druid/coordinator/v1/config/compaction/wikipedia_hour/history HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
[
    {
        "globalConfig": {
            "compactionTaskSlotRatio": 0.1,
            "maxCompactionTaskSlots": 2147483647,
            "compactionPolicy": {
                "type": "newestSegmentFirst",
                "priorityDatasource": "wikipedia"
            },
            "useSupervisors": true,
            "engine": "native"
        },
        "compactionConfig": {
            "dataSource": "wikipedia_hour",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "P1D",
            "tuningConfig": null,
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        },
        "auditInfo": {
            "author": "",
            "comment": "",
            "ip": "127.0.0.1"
        },
        "auditTime": "2023-07-31T18:15:19.302Z"
    },
    {
        "globalConfig": {
            "compactionTaskSlotRatio": 0.1,
            "maxCompactionTaskSlots": 2147483647,
            "compactionPolicy": {
                "type": "newestSegmentFirst"
            },
            "useSupervisors": false,
            "engine": "native"
        },
        "compactionConfig": {
            "dataSource": "wikipedia_hour",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "PT0S",
            "tuningConfig": {
                "maxRowsInMemory": null,
                "appendableIndexSpec": null,
                "maxBytesInMemory": null,
                "maxTotalRows": null,
                "splitHintSpec": null,
                "partitionsSpec": {
                    "type": "dynamic",
                    "maxRowsPerSegment": 5000000,
                    "maxTotalRows": null
                },
                "indexSpec": null,
                "indexSpecForIntermediatePersists": null,
                "maxPendingPersists": null,
                "pushTimeout": null,
                "segmentWriteOutMediumFactory": null,
                "maxNumConcurrentSubTasks": null,
                "maxRetry": null,
                "taskStatusCheckPeriodMs": null,
                "chatHandlerTimeout": null,
                "chatHandlerNumRetries": null,
                "maxNumSegmentsToMerge": null,
                "totalNumMergeTasks": null,
                "maxColumnsToMerge": null,
                "type": "index_parallel",
                "forceGuaranteedRollup": false
            },
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        },
        "auditInfo": {
            "author": "",
            "comment": "",
            "ip": "127.0.0.1"
        },
        "auditTime": "2023-07-31T18:16:16.362Z"
    }
]
```
</details>

## View automatic compaction status

### Get segments awaiting compaction

Returns the total size of segments awaiting compaction for a given datasource. Returns a 404 response if a datasource does not have automatic compaction enabled.

#### URL

`GET` `/druid/coordinator/v1/compaction/progress?dataSource={dataSource}`

#### Query parameter
* `dataSource` (required)
  * Type: String
  * Name of the datasource for this status information.

#### Responses

<Tabs>

<TabItem value="23" label="200 SUCCESS">


*Successfully retrieved segment size awaiting compaction*

</TabItem>
<TabItem value="24" label="404 NOT FOUND">


*Unknown datasource name or datasource does not have automatic compaction enabled*

</TabItem>
</Tabs>

---

#### Sample request

The following example retrieves the remaining segments to be compacted for datasource `wikipedia_hour`.

<Tabs>

<TabItem value="25" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/compaction/progress?dataSource=wikipedia_hour"
```

</TabItem>
<TabItem value="26" label="HTTP">


```HTTP
GET /druid/coordinator/v1/compaction/progress?dataSource=wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "remainingSegmentSize": 7615837
}
```
</details>


### Get compaction status and statistics

Retrieves an array of `latestStatus` objects representing the status and statistics from the latest automatic compaction run for all datasources with automatic compaction enabled.

#### Compaction status response

The `latestStatus` object has the following properties:
* `dataSource`: Name of the datasource for this status information.
* `scheduleStatus`: Automatic compaction scheduling status. Possible values are `NOT_ENABLED` and `RUNNING`. Returns `RUNNING ` if the datasource has an active automatic compaction configuration submitted. Otherwise, returns `NOT_ENABLED`.
* `bytesAwaitingCompaction`: Total bytes of this datasource waiting to be compacted by the automatic compaction (only consider intervals/segments that are eligible for automatic compaction).
* `bytesCompacted`: Total bytes of this datasource that are already compacted with the spec set in the automatic compaction configuration.
* `bytesSkipped`: Total bytes of this datasource that are skipped (not eligible for automatic compaction) by the automatic compaction.
* `segmentCountAwaitingCompaction`: Total number of segments of this datasource waiting to be compacted by the automatic compaction (only consider intervals/segments that are eligible for automatic compaction).
* `segmentCountCompacted`: Total number of segments of this datasource that are already compacted with the spec set in the automatic compaction configuration.
* `segmentCountSkipped`: Total number of segments of this datasource that are skipped (not eligible for automatic compaction) by the automatic compaction.
* `intervalCountAwaitingCompaction`: Total number of intervals of this datasource waiting to be compacted by the automatic compaction (only consider intervals/segments that are eligible for automatic compaction).
* `intervalCountCompacted`: Total number of intervals of this datasource that are already compacted with the spec set in the automatic compaction configuration.
* `intervalCountSkipped`: Total number of intervals of this datasource that are skipped (not eligible for automatic compaction) by the automatic compaction.

#### URL

`GET` `/druid/coordinator/v1/compaction/status`

#### Query parameters
* `dataSource` (optional)
  * Type: String
  * Filter the result by name of a specific datasource.

#### Responses

<Tabs>

<TabItem value="27" label="200 SUCCESS">


*Successfully retrieved `latestStatus` object*

</TabItem>
</Tabs>

---
#### Sample request

<Tabs>

<TabItem value="28" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/compaction/status"
```

</TabItem>
<TabItem value="29" label="HTTP">


```HTTP
GET /druid/coordinator/v1/compaction/status HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "latestStatus": [
        {
            "dataSource": "wikipedia_api",
            "scheduleStatus": "RUNNING",
            "bytesAwaitingCompaction": 0,
            "bytesCompacted": 0,
            "bytesSkipped": 64133616,
            "segmentCountAwaitingCompaction": 0,
            "segmentCountCompacted": 0,
            "segmentCountSkipped": 8,
            "intervalCountAwaitingCompaction": 0,
            "intervalCountCompacted": 0,
            "intervalCountSkipped": 1
        },
        {
            "dataSource": "wikipedia_hour",
            "scheduleStatus": "RUNNING",
            "bytesAwaitingCompaction": 0,
            "bytesCompacted": 5998634,
            "bytesSkipped": 0,
            "segmentCountAwaitingCompaction": 0,
            "segmentCountCompacted": 1,
            "segmentCountSkipped": 0,
            "intervalCountAwaitingCompaction": 0,
            "intervalCountCompacted": 1,
            "intervalCountSkipped": 0
        }
    ]
}
```
</details>

## [Experimental] Unified Compaction APIs

This section describes the new unified compaction APIs which can be used regardless of whether compaction supervisors are enabled (i.e. `useSupervisors` is `true`) or not in the compaction dynamic config.

- If compaction supervisors are disabled, the APIs read or write the compaction dynamic config, same as the Coordinator-based compaction APIs above.
- If compaction supervisors are enabled, the APIs read or write the corresponding compaction supervisors. In conjunction with the APIs described below, the supervisor APIs may also be used to read or write the compaction supervisors as they offer greater flexibility and also serve information related to supervisor and task statuses.

### Update cluster-level compaction config

Updates cluster-level configuration for compaction tasks which applies to all datasources, unless explicitly overridden in the datasource compaction config.
This includes the following fields:

|Config|Description|Default value|
|------|-----------|-------------|
|`compactionTaskSlotRatio`|Ratio of number of slots taken up by compaction tasks to the number of total task slots across all workers.|0.1|
|`maxCompactionTaskSlots`|Maximum number of task slots that can be taken up by compaction tasks and sub-tasks. Minimum number of task slots available for compaction is 1. When using MSQ engine or Native engine with range partitioning, a single compaction job occupies more than one task slot. In this case, the minimum is 2 so that at least one compaction job can always run in the cluster.|2147483647 (i.e. total task slots)|
|`compactionPolicy`|Policy to choose intervals for compaction. Currently, the only supported policy is [Newest segment first](#compaction-policy-newestsegmentfirst).|Newest segment first|
|`useSupervisors`|Whether compaction should be run on Overlord using supervisors instead of Coordinator duties.|false|
|`engine`|Engine used for running compaction tasks, unless overridden in the datasource-level compaction config. Possible values are `native` and `msq`. `msq` engine can be used for compaction only if `useSupervisors` is `true`.|`native`|

#### Compaction policy `newestSegmentFirst`

|Field|Description|Default value|
|-----|-----------|-------------|
|`type`|This must always be `newestSegmentFirst`||
|`priorityDatasource`|Datasource to prioritize for compaction. The intervals of this datasource are chosen for compaction before the intervals of any other datasource. Within this datasource, the intervals are prioritized based on the chosen compaction policy.|None|


#### URL

`POST` `/druid/indexer/v1/compaction/config/cluster`

#### Responses

<Tabs>

<TabItem value="8" label="200 SUCCESS">


*Successfully updated compaction configuration*

</TabItem>
<TabItem value="9" label="404 NOT FOUND">


*Invalid `max` value*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="10" label="cURL">


```shell
curl --request POST "http://ROUTER_IP:ROUTER_PORT/druid/coordinator/v1/config/compaction/cluster" \
--header 'Content-Type: application/json' \
--data '{
    "compactionTaskSlotRatio": 0.5,
    "maxCompactionTaskSlots": 1500,
    "compactionPolicy": {
        "type": "newestSegmentFirst",
        "priorityDatasource": "wikipedia"
    },
    "useSupervisors": true,
    "engine": "msq"
}'

```

</TabItem>
<TabItem value="11" label="HTTP">


```HTTP
POST /druid/indexer/v1/compaction/config/cluster HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
Content-Type: application/json

{
    "compactionTaskSlotRatio": 0.5,
    "maxCompactionTaskSlots": 1500,
    "compactionPolicy": {
        "type": "newestSegmentFirst",
        "priorityDatasource": "wikipedia"
    },
    "useSupervisors": true,
    "engine": "msq"
}
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.

### Get cluster-level compaction config

Retrieves cluster-level configuration for compaction tasks which applies to all datasources, unless explicitly overridden in the datasource compaction config.
This includes all the fields listed in [Update cluster-level compaction config](#update-cluster-level-compaction-config).

#### URL

`GET` `/druid/indexer/v1/compaction/config/cluster`

#### Responses

<Tabs>

<TabItem value="8" label="200 SUCCESS">

*Successfully retrieved cluster compaction configuration*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="10" label="cURL">

```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/config/cluster"
```
</TabItem>

<TabItem value="11" label="HTTP">

```HTTP
GET /druid/indexer/v1/compaction/config/cluster HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "compactionTaskSlotRatio": 0.5,
    "maxCompactionTaskSlots": 1500,
    "compactionPolicy": {
        "type": "newestSegmentFirst",
        "priorityDatasource": "wikipedia"
    },
    "useSupervisors": true,
    "engine": "msq"
}
```

</details>

### Get automatic compaction configurations for all datasources

Retrieves all datasource compaction configurations.

#### URL

`GET` `/druid/indexer/v1/compaction/config/datasources`

#### Responses

<Tabs>

<TabItem value="12" label="200 SUCCESS">


*Successfully retrieved automatic compaction configurations*

</TabItem>
</Tabs>

---

#### Sample request

<Tabs>

<TabItem value="13" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/config/datasources"
```

</TabItem>
<TabItem value="14" label="HTTP">


```HTTP
GET /druid/indexer/v1/compaction/config/datasources HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "compactionConfigs": [
        {
            "dataSource": "wikipedia_hour",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "PT0S",
            "tuningConfig": {
                "maxRowsInMemory": null,
                "appendableIndexSpec": null,
                "maxBytesInMemory": null,
                "maxTotalRows": null,
                "splitHintSpec": null,
                "partitionsSpec": {
                    "type": "dynamic",
                    "maxRowsPerSegment": 5000000,
                    "maxTotalRows": null
                },
                "indexSpec": null,
                "indexSpecForIntermediatePersists": null,
                "maxPendingPersists": null,
                "pushTimeout": null,
                "segmentWriteOutMediumFactory": null,
                "maxNumConcurrentSubTasks": null,
                "maxRetry": null,
                "taskStatusCheckPeriodMs": null,
                "chatHandlerTimeout": null,
                "chatHandlerNumRetries": null,
                "maxNumSegmentsToMerge": null,
                "totalNumMergeTasks": null,
                "maxColumnsToMerge": null,
                "type": "index_parallel",
                "forceGuaranteedRollup": false
            },
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        },
        {
            "dataSource": "wikipedia",
            "taskPriority": 25,
            "inputSegmentSizeBytes": 100000000000000,
            "maxRowsPerSegment": null,
            "skipOffsetFromLatest": "PT0S",
            "tuningConfig": {
                "maxRowsInMemory": null,
                "appendableIndexSpec": null,
                "maxBytesInMemory": null,
                "maxTotalRows": null,
                "splitHintSpec": null,
                "partitionsSpec": {
                    "type": "dynamic",
                    "maxRowsPerSegment": 5000000,
                    "maxTotalRows": null
                },
                "indexSpec": null,
                "indexSpecForIntermediatePersists": null,
                "maxPendingPersists": null,
                "pushTimeout": null,
                "segmentWriteOutMediumFactory": null,
                "maxNumConcurrentSubTasks": null,
                "maxRetry": null,
                "taskStatusCheckPeriodMs": null,
                "chatHandlerTimeout": null,
                "chatHandlerNumRetries": null,
                "maxNumSegmentsToMerge": null,
                "totalNumMergeTasks": null,
                "maxColumnsToMerge": null,
                "type": "index_parallel",
                "forceGuaranteedRollup": false
            },
            "granularitySpec": {
                "segmentGranularity": "DAY",
                "queryGranularity": null,
                "rollup": null
            },
            "dimensionsSpec": null,
            "metricsSpec": null,
            "transformSpec": null,
            "ioConfig": null,
            "taskContext": null
        }
    ]
}
```
</details>

### Get automatic compaction configuration for a datasource

Retrieves the automatic compaction configuration for a datasource.

#### URL

`GET` `/druid/indexer/v1/compaction/config/datasources/{dataSource}`

#### Responses

<Tabs>

<TabItem value="15" label="200 SUCCESS">


*Successfully retrieved configuration for datasource*

</TabItem>
<TabItem value="16" label="404 NOT FOUND">


*Invalid datasource or datasource does not have automatic compaction enabled*

</TabItem>
</Tabs>

---

#### Sample request

The following example retrieves the automatic compaction configuration for datasource `wikipedia_hour`.

<Tabs>

<TabItem value="17" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/config/datasources/wikipedia_hour"
```

</TabItem>
<TabItem value="18" label="HTTP">


```HTTP
GET /druid/indexer/v1/compaction/config/datasources/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "dataSource": "wikipedia_hour",
    "taskPriority": 25,
    "inputSegmentSizeBytes": 100000000000000,
    "maxRowsPerSegment": null,
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "maxRowsInMemory": null,
        "appendableIndexSpec": null,
        "maxBytesInMemory": null,
        "maxTotalRows": null,
        "splitHintSpec": null,
        "partitionsSpec": {
            "type": "dynamic",
            "maxRowsPerSegment": 5000000,
            "maxTotalRows": null
        },
        "indexSpec": null,
        "indexSpecForIntermediatePersists": null,
        "maxPendingPersists": null,
        "pushTimeout": null,
        "segmentWriteOutMediumFactory": null,
        "maxNumConcurrentSubTasks": null,
        "maxRetry": null,
        "taskStatusCheckPeriodMs": null,
        "chatHandlerTimeout": null,
        "chatHandlerNumRetries": null,
        "maxNumSegmentsToMerge": null,
        "totalNumMergeTasks": null,
        "maxColumnsToMerge": null,
        "type": "index_parallel",
        "forceGuaranteedRollup": false
    },
    "granularitySpec": {
        "segmentGranularity": "DAY",
        "queryGranularity": null,
        "rollup": null
    },
    "dimensionsSpec": null,
    "metricsSpec": null,
    "transformSpec": null,
    "ioConfig": null,
    "taskContext": null
}
```
</details>

### Create or update automatic compaction configuration for a datasource

Creates or updates the automatic compaction configuration for a datasource. Pass the automatic compaction as a JSON object in the request body.

The automatic compaction configuration requires only the `dataSource` property. Druid fills all other properties with default values if not specified. See [Automatic compaction dynamic configuration](../configuration/index.md#automatic-compaction-dynamic-configuration) for configuration details.

Note that this endpoint returns an HTTP `200 OK` message code even if the datasource name does not exist.

#### URL

`POST` `/druid/indexer/v1/compaction/config/datasources/wikipedia_hour`

#### Responses

<Tabs>

<TabItem value="1" label="200 SUCCESS">


*Successfully submitted auto compaction configuration*

</TabItem>
</Tabs>

---
#### Sample request

The following example creates an automatic compaction configuration for the datasource `wikipedia_hour`, which was ingested with `HOUR` segment granularity. This automatic compaction configuration performs compaction on `wikipedia_hour`, resulting in compacted segments that represent a day interval of data.

In this example:

* `wikipedia_hour` is a datasource with `HOUR` segment granularity.
* `skipOffsetFromLatest` is set to `PT0S`, meaning that no data is skipped.
* `partitionsSpec` is set to the default `dynamic`, allowing Druid to dynamically determine the optimal partitioning strategy.
* `type` is set to `index_parallel`, meaning that parallel indexing is used.
* `segmentGranularity` is set to `DAY`, meaning that each compacted segment is a day of data.

<Tabs>

<TabItem value="2" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/config/datasources/wikipedia_hour"\
--header 'Content-Type: application/json' \
--data '{
    "dataSource": "wikipedia_hour",
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "partitionsSpec": {
            "type": "dynamic"
        },
        "type": "index_parallel"
    },
    "granularitySpec": {
        "segmentGranularity": "DAY"
    }
}'
```

</TabItem>
<TabItem value="3" label="HTTP">


```HTTP
POST /druid/indexer/v1/compaction/config/datasources/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
Content-Type: application/json
Content-Length: 281

{
    "dataSource": "wikipedia_hour",
    "skipOffsetFromLatest": "PT0S",
    "tuningConfig": {
        "partitionsSpec": {
            "type": "dynamic"
        },
        "type": "index_parallel"
    },
    "granularitySpec": {
        "segmentGranularity": "DAY"
    }
}
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.


### Delete automatic compaction configuration for a datasource

Removes the automatic compaction configuration for a datasource. This updates the compaction status of the datasource to "Not enabled."

#### URL

`DELETE` `/druid/indexer/v1/compaction/config/datasources/{dataSource}`

#### Responses

<Tabs>

<TabItem value="4" label="200 SUCCESS">


*Successfully deleted automatic compaction configuration*

</TabItem>
<TabItem value="5" label="404 NOT FOUND">


*Datasource does not have automatic compaction or invalid datasource name*

</TabItem>
</Tabs>

---


#### Sample request

<Tabs>

<TabItem value="6" label="cURL">


```shell
curl --request DELETE "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/config/datasources/wikipedia_hour"
```

</TabItem>
<TabItem value="7" label="HTTP">


```HTTP
DELETE /druid/indexer/v1/compaction/config/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

A successful request returns an HTTP `200 OK` message code and an empty response body.

### Get compaction status for all datasources

Retrieves an array of `latestStatus` objects representing the status and statistics from the latest automatic compaction run for all the datasources to which the user has read access.
The response payload is in the same format as [Compaction status response](#compaction-status-response).

#### URL

`GET` `/druid/indexer/v1/compaction/status/datasources`

#### Responses

<Tabs>

<TabItem value="27" label="200 SUCCESS">


*Successfully retrieved `latestStatus` object*

</TabItem>
</Tabs>

---
#### Sample request

<Tabs>

<TabItem value="28" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/status/datasources"
```

</TabItem>
<TabItem value="29" label="HTTP">


```HTTP
GET /druid/indexer/v1/compaction/status/datasources HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "latestStatus": [
        {
            "dataSource": "wikipedia_api",
            "scheduleStatus": "RUNNING",
            "bytesAwaitingCompaction": 0,
            "bytesCompacted": 0,
            "bytesSkipped": 64133616,
            "segmentCountAwaitingCompaction": 0,
            "segmentCountCompacted": 0,
            "segmentCountSkipped": 8,
            "intervalCountAwaitingCompaction": 0,
            "intervalCountCompacted": 0,
            "intervalCountSkipped": 1
        },
        {
            "dataSource": "wikipedia_hour",
            "scheduleStatus": "RUNNING",
            "bytesAwaitingCompaction": 0,
            "bytesCompacted": 5998634,
            "bytesSkipped": 0,
            "segmentCountAwaitingCompaction": 0,
            "segmentCountCompacted": 1,
            "segmentCountSkipped": 0,
            "intervalCountAwaitingCompaction": 0,
            "intervalCountCompacted": 1,
            "intervalCountSkipped": 0
        }
    ]
}
```
</details>

### Get compaction status for a single datasource

Retrieves the latest status from the latest automatic compaction run for a datasource. The response payload is in the same format as [Compaction status response](#compaction-status-response) with zero or one entry.

#### URL

`GET` `/druid/indexer/v1/compaction/status/datasources/{dataSource}`

#### Responses

<Tabs>

<TabItem value="27" label="200 SUCCESS">


*Successfully retrieved `latestStatus` object*

</TabItem>
</Tabs>

---
#### Sample request

<Tabs>

<TabItem value="28" label="cURL">


```shell
curl "http://ROUTER_IP:ROUTER_PORT/druid/indexer/v1/compaction/status/datasources/wikipedia_hour"
```

</TabItem>
<TabItem value="29" label="HTTP">


```HTTP
GET /druid/indexer/v1/compaction/status/datasources/wikipedia_hour HTTP/1.1
Host: http://ROUTER_IP:ROUTER_PORT
```

</TabItem>
</Tabs>

#### Sample response

<details>
  <summary>View the response</summary>

```json
{
    "latestStatus": [
        {
            "dataSource": "wikipedia_hour",
            "scheduleStatus": "RUNNING",
            "bytesAwaitingCompaction": 0,
            "bytesCompacted": 5998634,
            "bytesSkipped": 0,
            "segmentCountAwaitingCompaction": 0,
            "segmentCountCompacted": 1,
            "segmentCountSkipped": 0,
            "intervalCountAwaitingCompaction": 0,
            "intervalCountCompacted": 1,
            "intervalCountSkipped": 0
        }
    ]
}
```
</details>
