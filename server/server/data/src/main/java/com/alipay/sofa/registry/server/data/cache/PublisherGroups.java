/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.data.cache;

import com.alipay.sofa.registry.common.model.ConnectId;
import com.alipay.sofa.registry.common.model.ProcessId;
import com.alipay.sofa.registry.common.model.PublisherVersion;
import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.dataserver.DatumSummary;
import com.alipay.sofa.registry.common.model.dataserver.DatumVersion;
import com.alipay.sofa.registry.common.model.store.Publisher;
import com.google.common.collect.Maps;
import org.glassfish.jersey.internal.guava.Sets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author yuzhi.lyz
 * @version v 0.1 2020-12-02 21:52 yuzhi.lyz Exp $
 */
public final class PublisherGroups {
    private final Map<String, PublisherGroup> publisherGroupMap = Maps.newConcurrentMap();

    Datum getDatum(String dataInfoId) {
        PublisherGroup group = publisherGroupMap.get(dataInfoId);
        return group == null ? null : group.toDatum();
    }

    Map<String, Datum> getAllDatum() {
        Map<String, Datum> map = new HashMap<>(publisherGroupMap.size());
        publisherGroupMap.forEach((k, v) -> {
            map.put(k, v.toDatum());
        });
        return map;
    }

    Map<String, Publisher> getByConnectId(ConnectId connectId) {
        Map<String, Publisher> map = new HashMap<>(64);
        publisherGroupMap.values().forEach(v -> map.putAll(v.getByConnectId(connectId)));
        return map;
    }

    DatumVersion putPublisher(Publisher publisher, ProcessId seesionProcessId, String dataCenter) {
        PublisherGroup group = publisherGroupMap
                .computeIfAbsent(publisher.getDataInfoId(),
                        k -> new PublisherGroup(publisher, dataCenter));

        return group.addPublisher(publisher, seesionProcessId);
    }

    Map<String, DatumVersion> clean(ProcessId sessionProcessId) {
        Map<String, DatumVersion> versionMap = new HashMap<>(32);
        publisherGroupMap.values().forEach(g -> {
            DatumVersion ver = g.clean(sessionProcessId);
            if (ver != null) {
                versionMap.put(g.dataInfoId, ver);
            }
        });
        return versionMap;
    }

    Map<String, DatumVersion> remove(ConnectId connectId, ProcessId sessionProcessId, long registerTimestamp) {
        Map<String, DatumVersion> versionMap = new HashMap<>(32);
        publisherGroupMap.values().forEach(g -> {
            DatumVersion ver = g.remove(connectId, sessionProcessId, registerTimestamp);
            if (ver != null) {
                versionMap.put(g.dataInfoId, ver);
            }
        });
        return versionMap;
    }

    DatumVersion remove(String dataInfoId, ProcessId sessionProcessId) {
        PublisherGroup group = publisherGroupMap.get(dataInfoId);
        return group == null ? null : group.clean(sessionProcessId);
    }

    DatumVersion update(String dataInfoId, List<Publisher> updatedPublishers) {
        PublisherGroup group = publisherGroupMap.get(dataInfoId);
        return group == null ? null : group.update(updatedPublishers);
    }

    DatumVersion remove(String dataInfoId, ProcessId sessionProcessId,
                        Map<String, PublisherVersion> removedPublishers) {
        PublisherGroup group = publisherGroupMap.get(dataInfoId);
        return group == null ? null : group.remove(sessionProcessId, removedPublishers);
    }

    Map<String, DatumSummary> getSummary(String sessionIpAddress) {
        Map<String, DatumSummary> summarys = Maps.newHashMap();
        publisherGroupMap.forEach((k, g) -> {
            summarys.put(k, g.getSummary(sessionIpAddress));
        });
        return summarys;
    }

    Set<ProcessId> getSessionProcessIds() {
        Set<ProcessId> ids = Sets.newHashSet();
        publisherGroupMap.values().forEach(g -> ids.addAll(g.getSessionProcessIds()));
        return ids;
    }

    Map<String, Integer> compact(long tombstoneTimestamp) {
        Map<String, Integer> compacts = Maps.newHashMap();
        publisherGroupMap.values().forEach(g -> {
            int count = g.compact(tombstoneTimestamp);
            if (count != 0) {
                compacts.put(g.dataInfoId, count);
            }
        });
        return compacts;
    }
}