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
package com.alipay.sofa.registry.server.meta.slot.arrange;

import com.alipay.sofa.registry.common.model.metaserver.nodes.DataNode;
import com.alipay.sofa.registry.common.model.slot.SlotTable;
import com.alipay.sofa.registry.exception.*;
import com.alipay.sofa.registry.jraft.bootstrap.ServiceStateMachine;
import com.alipay.sofa.registry.lifecycle.impl.LifecycleHelper;
import com.alipay.sofa.registry.observer.Observable;
import com.alipay.sofa.registry.observer.impl.AbstractLifecycleObservable;
import com.alipay.sofa.registry.server.meta.cluster.node.NodeAdded;
import com.alipay.sofa.registry.server.meta.cluster.node.NodeRemoved;
import com.alipay.sofa.registry.server.meta.lease.data.DataManagerObserver;
import com.alipay.sofa.registry.server.meta.lease.data.DefaultDataServerManager;
import com.alipay.sofa.registry.server.meta.slot.SlotAssigner;
import com.alipay.sofa.registry.server.meta.slot.SlotBalancer;
import com.alipay.sofa.registry.server.meta.slot.assigner.DefaultSlotAssigner;
import com.alipay.sofa.registry.server.meta.slot.balance.DefaultSlotBalancer;
import com.alipay.sofa.registry.server.meta.slot.manager.DefaultSlotManager;
import com.alipay.sofa.registry.server.meta.slot.manager.LocalSlotManager;
import com.alipay.sofa.registry.server.meta.slot.util.builder.SlotTableBuilder;
import com.alipay.sofa.registry.server.meta.slot.util.comparator.DataNodeComparator;
import com.alipay.sofa.registry.server.shared.slot.SlotTableUtils;
import com.alipay.sofa.registry.server.shared.util.NodeUtils;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.JsonUtils;
import com.alipay.sofa.registry.util.WakeUpLoopRunnable;
import com.google.common.annotations.VisibleForTesting;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author chen.zhu
 * <p>
 * Jan 14, 2021
 */
public class ScheduledSlotArranger extends AbstractLifecycleObservable implements
                                                                      DataManagerObserver {

    @Autowired
    private DefaultDataServerManager dataServerManager;

    @Autowired
    private LocalSlotManager         slotManager;

    @Autowired
    private DefaultSlotManager       defaultSlotManager;

    private final Arranger           arranger = new Arranger();

    private final Lock               lock     = new ReentrantLock();

    public ScheduledSlotArranger() {
    }

    public ScheduledSlotArranger(DefaultDataServerManager dataServerManager,
                                 LocalSlotManager slotManager, DefaultSlotManager defaultSlotManager) {
        this.dataServerManager = dataServerManager;
        this.slotManager = slotManager;
        this.defaultSlotManager = defaultSlotManager;
    }

    @PostConstruct
    public void postConstruct() throws Exception {
        LifecycleHelper.initializeIfPossible(this);
        LifecycleHelper.startIfPossible(this);
    }

    @PreDestroy
    public void preDestroy() throws Exception {
        LifecycleHelper.stopIfPossible(this);
        LifecycleHelper.disposeIfPossible(this);
    }

    @Override
    protected void doInitialize() throws InitializeException {
        super.doInitialize();
        dataServerManager.addObserver(this);
    }

    @Override
    protected void doStart() throws StartException {
        super.doStart();
        Thread executor = ConcurrentUtils.createDaemonThread(getClass().getSimpleName(), arranger);
        executor.start();
    }

    @Override
    protected void doStop() throws StopException {
        arranger.close();
        super.doStop();
    }

    @Override
    protected void doDispose() throws DisposeException {
        dataServerManager.removeObserver(this);
        super.doDispose();
    }

    @Override
    public void update(Observable source, Object message) {
        logger.warn("[update] receive from [{}], message: {}", source, message);
        if (message instanceof NodeRemoved) {
            arranger.wakeup();
        }
        if (message instanceof NodeAdded) {
            arranger.wakeup();
        }
    }

    public boolean tryLock() {
        return lock.tryLock();
    }

    public void unlock() {
        lock.unlock();
    }

    private boolean isRaftLeader() {
        return ServiceStateMachine.getInstance().isLeader();
    }

    private SlotTableBuilder createSlotTableBuilder(SlotTable slotTable, List<String> currentDataNodeIps,
                                                    int slotNum, int replicas) {
        DataNodeComparator comparator = new DataNodeComparator(slotTable.getDataServers(), currentDataNodeIps);
        SlotTableBuilder slotTableBuilder = new SlotTableBuilder(slotTable, slotNum, replicas);
        slotTableBuilder.init(currentDataNodeIps);

        comparator.getRemoved().forEach(slotTableBuilder::removeDataServerSlots);
        return slotTableBuilder;
    }

    protected void assignSlots(SlotTableBuilder slotTableBuilder,
                               Collection<String> currentDataServers) {
        SlotTable slotTable = createSlotAssigner(slotTableBuilder, currentDataServers).assign();
        refreshSlotTable(slotTable);
    }

    protected SlotAssigner createSlotAssigner(SlotTableBuilder slotTableBuilder,
                                              Collection<String> currentDataServers) {
        return new DefaultSlotAssigner(slotTableBuilder, currentDataServers);
    }

    protected void balanceSlots(SlotTableBuilder slotTableBuilder,
                                Collection<String> currentDataServers) {
        SlotTable slotTable = createSlotBalancer(slotTableBuilder, currentDataServers).balance();
        refreshSlotTable(slotTable);
    }

    private void refreshSlotTable(SlotTable slotTable) {
        if (slotTable == null) {
            logger.info("[refreshSlotTable] slot-table not change");
            return;
        }
        if (!SlotTableUtils.isValidSlotTable(slotTable)) {
            throw new SofaRegistrySlotTableException("slot table is not valid: \n"
                                                     + JsonUtils.writeValueAsString(slotTable));
        }
        if (slotTable.getEpoch() > slotManager.getSlotTable().getEpoch()) {
            defaultSlotManager.refresh(slotTable);
        } else {
            logger.warn("[refreshSlotTable] slot-table epoch not change: {}",
                JsonUtils.writeValueAsString(slotTable));
        }
    }

    protected SlotBalancer createSlotBalancer(SlotTableBuilder slotTableBuilder,
                                              Collection<String> currentDataServers) {
        return new DefaultSlotBalancer(slotTableBuilder, currentDataServers);
    }

    private final class Arranger extends WakeUpLoopRunnable {

        private final int waitingMillis = Integer.getInteger("slot.arrange.interval.milli", 1000);

        @Override
        public int getWaitingMillis() {
            return waitingMillis;
        }

        @Override
        public void runUnthrowable() {
            try {
                arrangeSync();
            } catch (Throwable e) {
                logger.error("failed to arrange", e);
            }
        }
    }

    private boolean tryArrangeSlots(List<DataNode> dataNodes) {
        if (!tryLock()) {
            logger.warn("[tryBalanceSlots] tryLock failed");
            return false;
        }
        try {
            List<String> currentDataNodeIps = NodeUtils.transferNodeToIpList(dataNodes);
            logger.info("arrange slot with DataNode, size={}, {}", currentDataNodeIps.size(),
                currentDataNodeIps);
            final SlotTable curSlotTable = slotManager.getSlotTable();
            SlotTableBuilder tableBuilder = createSlotTableBuilder(curSlotTable,
                currentDataNodeIps, slotManager.getSlotNums(), slotManager.getSlotReplicaNums());
            if (tableBuilder.hasNoAssignedSlots()) {
                logger.info("[re-assign][begin] assign slots to data-server");
                assignSlots(tableBuilder, currentDataNodeIps);
                logger.info("[re-assign][end]");
            } else {
                logger.info("[balance][begin] balance slots to data-server");
                balanceSlots(tableBuilder, currentDataNodeIps);
                logger.info("[balance][end]");
            }
        } finally {
            unlock();
        }
        return true;
    }

    public void arrangeAsync() {
        arranger.wakeup();
    }

    @VisibleForTesting
    public boolean arrangeSync() {
        if (isRaftLeader()) {
            // the start arrange with the dataNodes snapshot
            final List<DataNode> dataNodes = dataServerManager.getClusterMembers();
            if (dataNodes.isEmpty()) {
                logger.warn("[Arranger] empty data server list, continue");
                return true;
            } else {
                return tryArrangeSlots(dataNodes);
            }
        } else {
            logger.info("not leader for arrange");
            return false;
        }
    }
}