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

package org.apache.rocketmq.store.ha.autoswitch;

import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.utils.ConcurrentHashMapUtils;
import org.apache.rocketmq.logging.org.slf4j.Logger;
import org.apache.rocketmq.logging.org.slf4j.LoggerFactory;
import org.apache.rocketmq.remoting.protocol.EpochEntry;
import org.apache.rocketmq.remoting.protocol.body.HARuntimeInfo;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.DispatchRequest;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.ha.DefaultHAService;
import org.apache.rocketmq.store.ha.GroupTransferService;
import org.apache.rocketmq.store.ha.HAClient;
import org.apache.rocketmq.store.ha.HAConnection;
import org.apache.rocketmq.store.ha.HAConnectionStateNotificationService;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * SwitchAble ha service, support switch role to master or slave.
 */
public class AutoSwitchHAService extends DefaultHAService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryImpl("AutoSwitchHAService_Executor_"));
    private final ConcurrentHashMap<String, Long> connectionCaughtUpTimeTable = new ConcurrentHashMap<>();
    private final List<Consumer<Set<String>>> syncStateSetChangedListeners = new ArrayList<>();
    private final Set<String> syncStateSet = new HashSet<>();
    private final Set<String> remoteSyncStateSet = new HashSet<>();
    private final ReadWriteLock syncStateSetReadWriteLock = new ReentrantReadWriteLock();
    private final Lock readLock = syncStateSetReadWriteLock.readLock();
    private final Lock writeLock = syncStateSetReadWriteLock.writeLock();

    //  Indicate whether the syncStateSet is currently in the process of being synchronized to controller.
    private volatile boolean isSynchronizingSyncStateSet = false;
    private volatile long confirmOffset = -1;

    private String localAddress;

    private EpochFileCache epochCache;
    private AutoSwitchHAClient haClient;

    public AutoSwitchHAService() {
    }

    @Override
    public void init(final DefaultMessageStore defaultMessageStore) throws IOException {
        this.epochCache = new EpochFileCache(defaultMessageStore.getMessageStoreConfig().getStorePathEpochFile());
        this.epochCache.initCacheFromFile();
        this.defaultMessageStore = defaultMessageStore;
        this.acceptSocketService = new AutoSwitchAcceptSocketService(defaultMessageStore.getMessageStoreConfig());
        this.groupTransferService = new GroupTransferService(this, defaultMessageStore);
        this.haConnectionStateNotificationService = new HAConnectionStateNotificationService(this, defaultMessageStore);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (this.haClient != null) {
            this.haClient.shutdown();
        }
        this.executorService.shutdown();
    }

    @Override
    public void removeConnection(HAConnection conn) {
        if (!defaultMessageStore.isShutdown()) {
            final Set<String> syncStateSet = getLocalSyncStateSet();
            String slave = ((AutoSwitchHAConnection) conn).getSlaveAddress();
            if (syncStateSet.contains(slave)) {
                syncStateSet.remove(slave);
                markSynchronizingSyncStateSet(syncStateSet);
                notifySyncStateSetChanged(syncStateSet);
            }
        }
        super.removeConnection(conn);
    }

    @Override
    public boolean changeToMaster(int masterEpoch) {
        final int lastEpoch = this.epochCache.lastEpoch();
        if (masterEpoch < lastEpoch) {
            LOGGER.warn("newMasterEpoch {} < lastEpoch {}, fail to change to master", masterEpoch, lastEpoch);
            return false;
        }
        destroyConnections();
        // Stop ha client if needed
        if (this.haClient != null) {
            this.haClient.shutdown();
        }

        // Truncate dirty file
        final long truncateOffset = truncateInvalidMsg();

        updateConfirmOffset(computeConfirmOffset());

        if (truncateOffset >= 0) {
            this.epochCache.truncateSuffixByOffset(truncateOffset);
        }

        // Append new epoch to epochFile
        final EpochEntry newEpochEntry = new EpochEntry(masterEpoch, this.defaultMessageStore.getMaxPhyOffset());
        if (this.epochCache.lastEpoch() >= masterEpoch) {
            this.epochCache.truncateSuffixByEpoch(masterEpoch);
        }
        this.epochCache.appendEntry(newEpochEntry);

        // Waiting consume queue dispatch
        while (defaultMessageStore.dispatchBehindBytes() > 0) {
            try {
                Thread.sleep(100);
            } catch (Exception ignored) {

            }
        }

        if (defaultMessageStore.isTransientStorePoolEnable()) {
            waitingForAllCommit();
            defaultMessageStore.getTransientStorePool().setRealCommit(true);
        }

        LOGGER.info("TruncateOffset is {}, confirmOffset is {}, maxPhyOffset is {}", truncateOffset, getConfirmOffset(), this.defaultMessageStore.getMaxPhyOffset());
        this.defaultMessageStore.recoverTopicQueueTable();
        this.defaultMessageStore.setStateMachineVersion(masterEpoch);
        LOGGER.info("Change ha to master success, newMasterEpoch:{}, startOffset:{}", masterEpoch, newEpochEntry.getStartOffset());
        return true;
    }

    @Override
    public boolean changeToSlave(String newMasterAddr, int newMasterEpoch, Long slaveId) {
        final int lastEpoch = this.epochCache.lastEpoch();
        if (newMasterEpoch < lastEpoch) {
            LOGGER.warn("newMasterEpoch {} < lastEpoch {}, fail to change to slave", newMasterEpoch, lastEpoch);
            return false;
        }
        try {
            destroyConnections();
            if (this.haClient == null) {
                this.haClient = new AutoSwitchHAClient(this, defaultMessageStore, this.epochCache);
            } else {
                this.haClient.reOpen();
            }
            this.haClient.setLocalAddress(this.localAddress);
            this.haClient.updateSlaveId(slaveId);
            this.haClient.updateMasterAddress(newMasterAddr);
            this.haClient.updateHaMasterAddress(null);
            this.haClient.start();

            if (defaultMessageStore.isTransientStorePoolEnable()) {
                waitingForAllCommit();
                defaultMessageStore.getTransientStorePool().setRealCommit(false);
            }

            this.defaultMessageStore.setStateMachineVersion(newMasterEpoch);

            LOGGER.info("Change ha to slave success, newMasterAddress:{}, newMasterEpoch:{}", newMasterAddr, newMasterEpoch);
            return true;
        } catch (final Exception e) {
            LOGGER.error("Error happen when change ha to slave", e);
            return false;
        }
    }

    public void waitingForAllCommit() {
        while (getDefaultMessageStore().remainHowManyDataToCommit() > 0) {
            getDefaultMessageStore().getCommitLog().getFlushManager().wakeUpCommit();
            try {
                Thread.sleep(100);
            } catch (Exception e) {

            }
        }
    }

    @Override
    public HAClient getHAClient() {
        return this.haClient;
    }

    @Override
    public void updateHaMasterAddress(String newAddr) {
        if (this.haClient != null) {
            this.haClient.updateHaMasterAddress(newAddr);
        }
    }

    @Override
    public void updateMasterAddress(String newAddr) {
    }

    public void registerSyncStateSetChangedListener(final Consumer<Set<String>> listener) {
        this.syncStateSetChangedListeners.add(listener);
    }

    public void notifySyncStateSetChanged(final Set<String> newSyncStateSet) {
        this.executorService.submit(() -> {
            for (Consumer<Set<String>> listener : syncStateSetChangedListeners) {
                listener.accept(newSyncStateSet);
            }
        });
    }

    /**
     * Check and maybe shrink the inSyncStateSet.
     * A slave will be removed from inSyncStateSet if (curTime - HaConnection.lastCaughtUpTime) > option(haMaxTimeSlaveNotCatchup)
     */
    public Set<String> maybeShrinkInSyncStateSet() {
        final Set<String> newSyncStateSet = getLocalSyncStateSet();
        boolean isSyncStateSetChanged = false;
        final long haMaxTimeSlaveNotCatchup = this.defaultMessageStore.getMessageStoreConfig().getHaMaxTimeSlaveNotCatchup();
        for (Map.Entry<String, Long> next : this.connectionCaughtUpTimeTable.entrySet()) {
            final String slaveAddress = next.getKey();
            if (newSyncStateSet.contains(slaveAddress)) {
                final Long lastCaughtUpTimeMs = this.connectionCaughtUpTimeTable.get(slaveAddress);
                if ((System.currentTimeMillis() - lastCaughtUpTimeMs) > haMaxTimeSlaveNotCatchup) {
                    newSyncStateSet.remove(slaveAddress);
                    isSyncStateSetChanged = true;
                }
            }
        }
        if (isSyncStateSetChanged) {
            markSynchronizingSyncStateSet(newSyncStateSet);
        }
        return newSyncStateSet;
    }

    /**
     * Check and maybe add the slave to inSyncStateSet. A slave will be added to inSyncStateSet if its slaveMaxOffset >=
     * current confirmOffset, and it is caught up to an offset within the current leader epoch.
     */
    public void maybeExpandInSyncStateSet(final String slaveAddress, final long slaveMaxOffset) {
        final Set<String> currentSyncStateSet = getLocalSyncStateSet();
        if (currentSyncStateSet.contains(slaveAddress)) {
            return;
        }
        final long confirmOffset = getConfirmOffset();
        if (slaveMaxOffset >= confirmOffset) {
            final EpochEntry currentLeaderEpoch = this.epochCache.lastEntry();
            if (slaveMaxOffset >= currentLeaderEpoch.getStartOffset()) {
                currentSyncStateSet.add(slaveAddress);
                markSynchronizingSyncStateSet(currentSyncStateSet);
                // Notify the upper layer that syncStateSet changed.
                notifySyncStateSetChanged(currentSyncStateSet);
            }
        }
    }

    private void markSynchronizingSyncStateSet(final Set<String> newSyncStateSet) {
        this.writeLock.lock();
        try {
            this.isSynchronizingSyncStateSet = true;
            this.remoteSyncStateSet.clear();
            this.remoteSyncStateSet.addAll(newSyncStateSet);
        } finally {
            this.writeLock.unlock();
        }
    }

    private void markSynchronizingSyncStateSetDone() {
        // No need to lock, because the upper-level calling method has already locked write lock
        this.isSynchronizingSyncStateSet = false;
    }

    public boolean isSynchronizingSyncStateSet() {
        return isSynchronizingSyncStateSet;
    }

    public void updateConnectionLastCaughtUpTime(final String slaveAddress, final long lastCaughtUpTimeMs) {
        Long prevTime = ConcurrentHashMapUtils.computeIfAbsent(this.connectionCaughtUpTimeTable, slaveAddress, k -> 0L);
        this.connectionCaughtUpTimeTable.put(slaveAddress, Math.max(prevTime, lastCaughtUpTimeMs));
    }

    /**
     * Get confirm offset (min slaveAckOffset of all syncStateSet members) for master
     */
    public long getConfirmOffset() {
        if (this.defaultMessageStore.getMessageStoreConfig().getBrokerRole() != BrokerRole.SLAVE) {
            if (getLocalSyncStateSet().size() == 1) {
                return this.defaultMessageStore.getMaxPhyOffset();
            }
            // First time compute confirmOffset.
            if (this.confirmOffset <= 0) {
                this.confirmOffset = computeConfirmOffset();
            }
        }
        return confirmOffset;
    }

    public void updateConfirmOffsetWhenSlaveAck(final String slaveAddress) {
        this.readLock.lock();
        try {
            if (this.syncStateSet.contains(slaveAddress)) {
                this.confirmOffset = computeConfirmOffset();
            }
        } finally {
            this.readLock.unlock();
        }
    }

    @Override
    public int inSyncReplicasNums(final long masterPutWhere) {
        this.readLock.lock();
        try {
            if (this.isSynchronizingSyncStateSet) {
                return Math.max(this.syncStateSet.size(), this.remoteSyncStateSet.size());
            } else {
                return this.syncStateSet.size();
            }
        } finally {
            this.readLock.unlock();
        }
    }

    @Override
    public HARuntimeInfo getRuntimeInfo(long masterPutWhere) {
        HARuntimeInfo info = new HARuntimeInfo();

        if (BrokerRole.SLAVE.equals(this.getDefaultMessageStore().getMessageStoreConfig().getBrokerRole())) {
            info.setMaster(false);

            info.getHaClientRuntimeInfo().setMasterAddr(this.haClient.getHaMasterAddress());
            info.getHaClientRuntimeInfo().setMaxOffset(this.getDefaultMessageStore().getMaxPhyOffset());
            info.getHaClientRuntimeInfo().setLastReadTimestamp(this.haClient.getLastReadTimestamp());
            info.getHaClientRuntimeInfo().setLastWriteTimestamp(this.haClient.getLastWriteTimestamp());
            info.getHaClientRuntimeInfo().setTransferredByteInSecond(this.haClient.getTransferredByteInSecond());
            info.getHaClientRuntimeInfo().setMasterFlushOffset(this.defaultMessageStore.getMasterFlushedOffset());
        } else {
            info.setMaster(true);

            info.setMasterCommitLogMaxOffset(masterPutWhere);

            Set<String> localSyncStateSet = getLocalSyncStateSet();
            for (HAConnection conn : this.connectionList) {
                HARuntimeInfo.HAConnectionRuntimeInfo cInfo = new HARuntimeInfo.HAConnectionRuntimeInfo();

                long slaveAckOffset = conn.getSlaveAckOffset();
                cInfo.setSlaveAckOffset(slaveAckOffset);
                cInfo.setDiff(masterPutWhere - slaveAckOffset);
                cInfo.setAddr(conn.getClientAddress().substring(1));
                cInfo.setTransferredByteInSecond(conn.getTransferredByteInSecond());
                cInfo.setTransferFromWhere(conn.getTransferFromWhere());

                cInfo.setInSync(localSyncStateSet.contains(((AutoSwitchHAConnection) conn).getSlaveAddress()));

                info.getHaConnectionInfo().add(cInfo);
            }
            info.setInSyncSlaveNums(localSyncStateSet.size() - 1);
        }
        return info;
    }

    public void updateConfirmOffset(long confirmOffset) {
        this.confirmOffset = confirmOffset;
    }

    private long computeConfirmOffset() {
        final Set<String> currentSyncStateSet = getSyncStateSet();
        long confirmOffset = this.defaultMessageStore.getMaxPhyOffset();
        for (HAConnection connection : this.connectionList) {
            final String slaveAddress = ((AutoSwitchHAConnection) connection).getSlaveAddress();
            if (currentSyncStateSet.contains(slaveAddress)) {
                confirmOffset = Math.min(confirmOffset, connection.getSlaveAckOffset());
            }
        }
        return confirmOffset;
    }

    public void setSyncStateSet(final Set<String> syncStateSet) {
        this.writeLock.lock();
        try {
            markSynchronizingSyncStateSetDone();
            this.syncStateSet.clear();
            this.syncStateSet.addAll(syncStateSet);
            this.confirmOffset = computeConfirmOffset();
        } finally {
            this.writeLock.unlock();
        }
    }

    /**
     * Return the union of the local and remote syncStateSets
     */
    public Set<String> getSyncStateSet() {
        this.readLock.lock();
        try {
            if (this.isSynchronizingSyncStateSet) {
                Set<String> unionSyncStateSet = new HashSet<>(this.syncStateSet.size() + this.remoteSyncStateSet.size());
                unionSyncStateSet.addAll(this.syncStateSet);
                unionSyncStateSet.addAll(this.remoteSyncStateSet);
                return unionSyncStateSet;
            } else {
                HashSet<String> syncStateSet = new HashSet<>(this.syncStateSet.size());
                syncStateSet.addAll(this.syncStateSet);
                return syncStateSet;
            }
        } finally {
            this.readLock.unlock();
        }
    }

    public Set<String> getLocalSyncStateSet() {
        this.readLock.lock();
        try {
            HashSet<String> localSyncStateSet = new HashSet<>(this.syncStateSet.size());
            localSyncStateSet.addAll(this.syncStateSet);
            return localSyncStateSet;
        } finally {
            this.readLock.unlock();
        }
    }

    public void truncateEpochFilePrefix(final long offset) {
        this.epochCache.truncatePrefixByOffset(offset);
    }

    public void truncateEpochFileSuffix(final long offset) {
        this.epochCache.truncateSuffixByOffset(offset);
    }

    public void setLocalAddress(String localAddress) {
        this.localAddress = localAddress;
    }

    /**
     * Try to truncate incomplete msg transferred from master.
     */
    public long truncateInvalidMsg() {
        long dispatchBehind = this.defaultMessageStore.dispatchBehindBytes();
        if (dispatchBehind <= 0) {
            LOGGER.info("Dispatch complete, skip truncate");
            return -1;
        }

        boolean doNext = true;
        long reputFromOffset = this.defaultMessageStore.getMaxPhyOffset() - dispatchBehind;
        do {
            SelectMappedBufferResult result = this.defaultMessageStore.getCommitLog().getData(reputFromOffset);
            if (result == null) {
                break;
            }

            try {
                reputFromOffset = result.getStartOffset();

                int readSize = 0;
                while (readSize < result.getSize()) {
                    DispatchRequest dispatchRequest = this.defaultMessageStore.getCommitLog().checkMessageAndReturnSize(result.getByteBuffer(), false, false);
                    if (dispatchRequest.isSuccess()) {
                        int size = dispatchRequest.getMsgSize();
                        if (size > 0) {
                            reputFromOffset += size;
                            readSize += size;
                        } else {
                            reputFromOffset = this.defaultMessageStore.getCommitLog().rollNextFile(reputFromOffset);
                            break;
                        }
                    } else {
                        doNext = false;
                        break;
                    }
                }
            } finally {
                result.release();
            }
        } while (reputFromOffset < this.defaultMessageStore.getMaxPhyOffset() && doNext);

        LOGGER.info("Truncate commitLog to {}", reputFromOffset);
        this.defaultMessageStore.truncateDirtyFiles(reputFromOffset);
        return reputFromOffset;
    }

    public int getLastEpoch() {
        return this.epochCache.lastEpoch();
    }

    public List<EpochEntry> getEpochEntries() {
        return this.epochCache.getAllEntries();
    }

    class AutoSwitchAcceptSocketService extends AcceptSocketService {

        public AutoSwitchAcceptSocketService(final MessageStoreConfig messageStoreConfig) {
            super(messageStoreConfig);
        }

        @Override
        public String getServiceName() {
            if (defaultMessageStore.getBrokerConfig().isInBrokerContainer()) {
                return defaultMessageStore.getBrokerConfig().getIdentifier() + AcceptSocketService.class.getSimpleName();
            }
            return AutoSwitchAcceptSocketService.class.getSimpleName();
        }

        @Override
        protected HAConnection createConnection(SocketChannel sc) throws IOException {
            return new AutoSwitchHAConnection(AutoSwitchHAService.this, sc, AutoSwitchHAService.this.epochCache);
        }
    }
}
