/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.partition.impl;

import com.hazelcast.cluster.ClusterState;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.core.MigrationListener;
import com.hazelcast.instance.Node;
import com.hazelcast.internal.cluster.ClusterStateListener;
import com.hazelcast.internal.cluster.ClusterVersionListener;
import com.hazelcast.internal.cluster.Versions;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.internal.cluster.impl.operations.TriggerMemberListPublishOp;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.internal.partition.InternalPartition;
import com.hazelcast.internal.partition.InternalPartitionService;
import com.hazelcast.internal.partition.MigrationInfo;
import com.hazelcast.internal.partition.MigrationInfo.MigrationStatus;
import com.hazelcast.internal.partition.PartitionListener;
import com.hazelcast.internal.partition.PartitionReplica;
import com.hazelcast.internal.partition.PartitionReplicaVersionManager;
import com.hazelcast.internal.partition.PartitionRuntimeState;
import com.hazelcast.internal.partition.PartitionServiceProxy;
import com.hazelcast.internal.partition.PartitionTableView;
import com.hazelcast.internal.partition.operation.AssignPartitions;
import com.hazelcast.internal.partition.operation.FetchPartitionStateOperation;
import com.hazelcast.internal.partition.operation.PartitionStateOperation;
import com.hazelcast.internal.partition.operation.PartitionStateVersionCheckOperation;
import com.hazelcast.internal.partition.operation.ShutdownRequestOperation;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.partition.NoDataMemberInClusterException;
import com.hazelcast.partition.PartitionEvent;
import com.hazelcast.partition.PartitionEventListener;
import com.hazelcast.partition.PartitionLostListener;
import com.hazelcast.spi.EventPublishingService;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.InternalCompletableFuture;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationService;
import com.hazelcast.spi.PartitionAwareService;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationservice.InternalOperationService;
import com.hazelcast.spi.partition.IPartition;
import com.hazelcast.spi.partition.IPartitionLostEvent;
import com.hazelcast.spi.properties.GroupProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.util.FutureUtil.ExceptionHandler;
import com.hazelcast.util.HashUtil;
import com.hazelcast.util.scheduler.CoalescingDelayedTrigger;
import com.hazelcast.util.scheduler.ScheduledEntry;
import com.hazelcast.version.Version;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import static com.hazelcast.cluster.memberselector.MemberSelectors.DATA_MEMBER_SELECTOR;
import static com.hazelcast.cluster.memberselector.MemberSelectors.NON_LOCAL_MEMBER_SELECTOR;
import static com.hazelcast.util.FutureUtil.logAllExceptions;
import static com.hazelcast.util.FutureUtil.returnWithDeadline;
import static com.hazelcast.util.MapUtil.createHashMap;
import static java.lang.Math.ceil;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The {@link InternalPartitionService} implementation.
 */
@SuppressWarnings({"checkstyle:methodcount", "checkstyle:classfanoutcomplexity", "checkstyle:classdataabstractioncoupling"})
public class InternalPartitionServiceImpl implements InternalPartitionService,
        EventPublishingService<PartitionEvent, PartitionEventListener<PartitionEvent>>,
        PartitionAwareService, ClusterStateListener, ClusterVersionListener {

    private static final int PARTITION_OWNERSHIP_WAIT_MILLIS = 10;
    private static final String EXCEPTION_MSG_PARTITION_STATE_SYNC_TIMEOUT = "Partition state sync invocation timed out";
    private static final int PTABLE_SYNC_TIMEOUT_SECONDS = 10;
    private static final int SAFE_SHUTDOWN_MAX_AWAIT_STEP_MILLIS = 1000;
    private static final long FETCH_PARTITION_STATE_SECONDS = 5;
    private static final long TRIGGER_MASTER_DELAY_MILLIS = 1000;

    private final Node node;
    private final NodeEngineImpl nodeEngine;
    private final ILogger logger;

    private final int partitionCount;

    private final long partitionMigrationTimeout;

    private final PartitionServiceProxy proxy;
    private final Lock lock = new ReentrantLock();
    private final InternalPartitionListener partitionListener;

    private final PartitionStateManager partitionStateManager;
    private final MigrationManager migrationManager;
    private final PartitionReplicaManager replicaManager;
    private final PartitionReplicaStateChecker partitionReplicaStateChecker;
    private final PartitionEventManager partitionEventManager;

    private final ExceptionHandler partitionStateSyncTimeoutHandler;

    /** Determines if a {@link AssignPartitions} is being sent to the master, used to limit partition assignment requests. */
    private final AtomicBoolean masterTriggered = new AtomicBoolean(false);
    private final CoalescingDelayedTrigger masterTrigger;

    private final AtomicReference<CountDownLatch> shutdownLatchRef = new AtomicReference<CountDownLatch>();

    private volatile Address lastMaster;

    /** Whether the master should fetch the partition tables from other nodes, can happen when node becomes new master. */
    private volatile boolean shouldFetchPartitionTables;

    public InternalPartitionServiceImpl(Node node) {
        HazelcastProperties properties = node.getProperties();
        this.partitionCount = properties.getInteger(GroupProperty.PARTITION_COUNT);
        this.node = node;
        this.nodeEngine = node.nodeEngine;
        this.logger = node.getLogger(InternalPartitionService.class);

        partitionListener = new InternalPartitionListener(node, this);

        partitionStateManager = new PartitionStateManager(node, this, partitionListener);
        migrationManager = new MigrationManager(node, this, lock);
        replicaManager = new PartitionReplicaManager(node, this);

        partitionReplicaStateChecker = new PartitionReplicaStateChecker(node, this);
        partitionEventManager = new PartitionEventManager(node);

        masterTrigger = new CoalescingDelayedTrigger(nodeEngine.getExecutionService(), TRIGGER_MASTER_DELAY_MILLIS,
                2 * TRIGGER_MASTER_DELAY_MILLIS, new Runnable() {
            @Override
            public void run() {
                resetMasterTriggeredFlag();
            }
        });

        partitionStateSyncTimeoutHandler =
                logAllExceptions(logger, EXCEPTION_MSG_PARTITION_STATE_SYNC_TIMEOUT, Level.FINEST);

        partitionMigrationTimeout = properties.getMillis(GroupProperty.PARTITION_MIGRATION_TIMEOUT);

        proxy = new PartitionServiceProxy(nodeEngine, this);

        MetricsRegistry metricsRegistry = nodeEngine.getMetricsRegistry();
        metricsRegistry.scanAndRegister(this, "partitions");
        metricsRegistry.scanAndRegister(partitionStateManager, "partitions");
        metricsRegistry.scanAndRegister(migrationManager, "partitions");
        metricsRegistry.scanAndRegister(replicaManager, "partitions");
    }

    @Override
    public void init(NodeEngine nodeEngine, Properties properties) {
        int partitionTableSendInterval = node.getProperties().getSeconds(GroupProperty.PARTITION_TABLE_SEND_INTERVAL);
        if (partitionTableSendInterval <= 0) {
            partitionTableSendInterval = 1;
        }
        ExecutionService executionService = nodeEngine.getExecutionService();
        executionService.scheduleWithRepetition(new PublishPartitionRuntimeStateTask(node, this),
                partitionTableSendInterval, partitionTableSendInterval, SECONDS);

        migrationManager.start();
        replicaManager.scheduleReplicaVersionSync(executionService);
    }

    @Override
    public Address getPartitionOwner(int partitionId) {
        if (!partitionStateManager.isInitialized()) {
            firstArrangement();
        }
        final InternalPartition partition = partitionStateManager.getPartitionImpl(partitionId);
        if (partition.getOwnerReplicaOrNull() == null && !node.isMaster()) {
            if (!isClusterFormedByOnlyLiteMembers()) {
                triggerMasterToAssignPartitions();
            }
        }
        return partition.getOwnerOrNull();
    }

    @Override
    public Address getPartitionOwnerOrWait(int partitionId) {
        Address owner;
        while ((owner = getPartitionOwner(partitionId)) == null) {
            if (!nodeEngine.isRunning()) {
                throw new HazelcastInstanceNotActiveException();
            }

            ClusterState clusterState = node.getClusterService().getClusterState();
            if (!clusterState.isMigrationAllowed()) {
                throw new IllegalStateException("Partitions can't be assigned since cluster-state: " + clusterState);
            }
            if (isClusterFormedByOnlyLiteMembers()) {
                throw new NoDataMemberInClusterException(
                        "Partitions can't be assigned since all nodes in the cluster are lite members");
            }

            try {
                Thread.sleep(PARTITION_OWNERSHIP_WAIT_MILLIS);
            } catch (InterruptedException e) {
                currentThread().interrupt();
                throw ExceptionUtil.rethrow(e);
            }
        }
        return owner;
    }

    @Override
    public PartitionRuntimeState firstArrangement() {
        if (!node.isMaster()) {
            triggerMasterToAssignPartitions();
            return null;
        }

        lock.lock();
        try {
            if (!partitionStateManager.isInitialized()) {
                Set<Member> excludedMembers = migrationManager.getShutdownRequestedMembers();
                if (partitionStateManager.initializePartitionAssignments(excludedMembers)) {
                    publishPartitionRuntimeState();
                }
            }

            return createPartitionStateInternal();
        } finally {
            lock.unlock();
        }
    }

    /** Sends a {@link AssignPartitions} to the master to assign partitions. */
    private void triggerMasterToAssignPartitions() {
        if (!shouldTriggerMasterToAssignPartitions()) {
            return;
        }

        ClusterServiceImpl clusterService = node.getClusterService();

        ClusterState clusterState = clusterService.getClusterState();
        if (!clusterState.isMigrationAllowed()) {
            logger.warning("Partitions can't be assigned since cluster-state=" + clusterState);
            return;
        }

        final Address masterAddress = clusterService.getMasterAddress();
        if (masterAddress == null || masterAddress.equals(node.getThisAddress())) {
            return;
        }

        if (masterTriggered.compareAndSet(false, true)) {
            InternalOperationService operationService = nodeEngine.getOperationService();
            ICompletableFuture<PartitionRuntimeState> future =
                    operationService.invokeOnTarget(SERVICE_NAME, new AssignPartitions(), masterAddress);
            future.andThen(new ExecutionCallback<PartitionRuntimeState>() {
                @Override
                public void onResponse(PartitionRuntimeState partitionState) {
                    resetMasterTriggeredFlag();
                    partitionState.setMaster(masterAddress);
                    processPartitionRuntimeState(partitionState);
                }

                @Override
                public void onFailure(Throwable t) {
                    resetMasterTriggeredFlag();
                    logger.severe(t);
                }
            });

            masterTrigger.executeWithDelay();
        }
    }

    private boolean shouldTriggerMasterToAssignPartitions() {
        ClusterServiceImpl clusterService = node.getClusterService();
        return !partitionStateManager.isInitialized() && clusterService.isJoined() && node.getNodeExtension().isStartCompleted();
    }

    private void resetMasterTriggeredFlag() {
        masterTriggered.set(false);
    }

    private boolean isClusterFormedByOnlyLiteMembers() {
        final ClusterServiceImpl clusterService = node.getClusterService();
        return clusterService.getMembers(DATA_MEMBER_SELECTOR).isEmpty();
    }

    /**
     * Sets the initial partition table and state version. If any partition has a replica, the partition state manager is
     * set to initialized, otherwise {@link #partitionStateManager#isInitialized()} stays uninitialized but the current state
     * will be updated nevertheless.
     * This method acquires the partition service lock.
     *
     * @param partitionTable the initial partition table
     * @throws IllegalStateException if the partition manager has already been initialized
     */
    public void setInitialState(PartitionTableView partitionTable) {
        lock.lock();
        try {
            partitionStateManager.setInitialState(partitionTable);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getMemberGroupsSize() {
        return partitionStateManager.getMemberGroupsSize();
    }

    @Probe(name = "maxBackupCount")
    @Override
    public int getMaxAllowedBackupCount() {
        return max(min(getMemberGroupsSize() - 1, InternalPartition.MAX_BACKUP_COUNT), 0);
    }

    @Override
    public void memberAdded(Member member) {
        logger.fine("Adding " + member);
        lock.lock();
        try {
            lastMaster = node.getClusterService().getMasterAddress();
            if (!member.localMember()) {
                partitionStateManager.updateMemberGroupsSize();
            }
            if (node.isMaster()) {
                if (partitionStateManager.isInitialized()) {
                    migrationManager.triggerControlTask();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void memberRemoved(Member member) {
        logger.fine("Removing " + member);
        lock.lock();
        try {
            migrationManager.onMemberRemove(member);
            replicaManager.cancelReplicaSyncRequestsTo(member);

            ClusterState clusterState = node.getClusterService().getClusterState();
            if (clusterState.isMigrationAllowed() || clusterState.isPartitionPromotionAllowed()) {
                partitionStateManager.updateMemberGroupsSize();

                boolean isThisNodeNewMaster = node.isMaster() && !node.getThisAddress().equals(lastMaster);
                if (isThisNodeNewMaster) {
                    assert !shouldFetchPartitionTables;
                    shouldFetchPartitionTables = true;
                }
                if (node.isMaster()) {
                    migrationManager.triggerControlTask();
                }
            }

            lastMaster = node.getClusterService().getMasterAddress();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onClusterStateChange(ClusterState newState) {
        if (!newState.isMigrationAllowed()) {
            return;
        }
        if (!partitionStateManager.isInitialized()) {
            return;
        }
        if (!node.isMaster()) {
            return;
        }

        lock.lock();
        try {
            if (partitionStateManager.isInitialized()
                    && migrationManager.shouldTriggerRepartitioningWhenClusterStateAllowsMigration()) {
                migrationManager.triggerControlTask();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onClusterVersionChange(Version newVersion) {
        // RU_COMPAT_3_11
        if (newVersion.isEqualTo(Versions.V3_12)) {
            lock.lock();
            try {
                if (!partitionStateManager.isInitialized()) {
                    return;
                }
                for (int pid = 0; pid < getPartitionCount(); pid++) {
                    InternalPartitionImpl partition = partitionStateManager.getPartitionImpl(pid);
                    PartitionReplica[] replicaMembers = partition.getReplicas();
                    for (PartitionReplica member : replicaMembers) {
                        if (member == null) {
                            continue;
                        }
                        if (PartitionReplica.UNKNOWN_UID.equals(member.uuid())) {
                            throw new IllegalStateException("Unknown uuid: " + member);
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public PartitionRuntimeState createPartitionState() {
        if (!isFetchMostRecentPartitionTableTaskRequired()) {
            return createPartitionStateInternal();
        }
        return null;
    }

    /**
     * Returns a copy of the partition table or {@code null} if not initialized. This method will acquire the partition service
     * lock.
     */
    public PartitionRuntimeState createPartitionStateInternal() {
        lock.lock();
        try {
            if (!partitionStateManager.isInitialized()) {
                return null;
            }

            List<MigrationInfo> completedMigrations = migrationManager.getCompletedMigrationsCopy();
            InternalPartition[] partitions = partitionStateManager.getPartitions();

            PartitionRuntimeState state = new PartitionRuntimeState(partitions, completedMigrations, getPartitionStateVersion());
            state.setActiveMigration(migrationManager.getActiveMigration());
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a transient PartitionRuntimeState to commit given migration.
     * Result migration is applied to partition table and migration is added to completed-migrations set.
     * Version of created partition table is incremented by 1.
     */
    PartitionRuntimeState createMigrationCommitPartitionState(MigrationInfo migrationInfo) {
        lock.lock();
        try {
            if (!partitionStateManager.isInitialized()) {
                return null;
            }

            List<MigrationInfo> completedMigrations = migrationManager.getCompletedMigrationsCopy();
            InternalPartition[] partitions = partitionStateManager.getPartitionsCopy();

            int partitionId = migrationInfo.getPartitionId();
            InternalPartitionImpl partition = (InternalPartitionImpl) partitions[partitionId];
            migrationManager.applyMigration(partition, migrationInfo);

            migrationInfo.setStatus(MigrationStatus.SUCCESS);
            completedMigrations.add(migrationInfo);

            int committedVersion = getPartitionStateVersion() + 1;
            return new PartitionRuntimeState(partitions, completedMigrations, committedVersion);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a transient {@link PartitionRuntimeState} to commit promotions by applying the {@code migrationInfos}.
     * The partition table version is incremented by number of promotions.
     * This method will acquire the partition service lock.
     *
     * @param migrationInfos the promotions to be executed on the destination
     * @return the partition table with the executed migrations or {@code null} if the partitions are not initialized (assigned)
     */
    PartitionRuntimeState createPromotionCommitPartitionState(Collection<MigrationInfo> migrationInfos) {
        lock.lock();
        try {
            if (!partitionStateManager.isInitialized()) {
                return null;
            }

            List<MigrationInfo> completedMigrations = migrationManager.getCompletedMigrationsCopy();
            InternalPartition[] partitions = partitionStateManager.getPartitionsCopy();

            for (MigrationInfo migrationInfo : migrationInfos) {
                int partitionId = migrationInfo.getPartitionId();
                InternalPartitionImpl partition = (InternalPartitionImpl) partitions[partitionId];
                migrationManager.applyMigration(partition, migrationInfo);
                migrationInfo.setStatus(MigrationStatus.SUCCESS);
            }

            int committedVersion = getPartitionStateVersion() + migrationInfos.size();
            return new PartitionRuntimeState(partitions, completedMigrations, committedVersion);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called on the master node to publish the current partition state to all cluster nodes. It will not publish the partition
     * state if the partitions have not yet been initialized, there is ongoing repartitioning or a node is joining the cluster.
     */
    @SuppressWarnings("checkstyle:npathcomplexity")
    void publishPartitionRuntimeState() {
        if (!partitionStateManager.isInitialized()) {
            // do not send partition state until initialized!
            return;
        }

        if (!node.isMaster()) {
            return;
        }

        if (!areMigrationTasksAllowed()) {
            // migration is disabled because of a member leave, wait till enabled!
            return;
        }

        PartitionRuntimeState partitionState = createPartitionStateInternal();
        if (partitionState == null) {
            return;
        }

        if (logger.isFineEnabled()) {
            logger.fine("Publishing partition state, version: " + partitionState.getVersion());
        }

        PartitionStateOperation op = new PartitionStateOperation(partitionState, false);
        OperationService operationService = nodeEngine.getOperationService();
        Collection<Member> members = node.clusterService.getMembers();
        for (Member member : members) {
            if (!member.localMember()) {
                try {
                    operationService.send(op, member.getAddress());
                } catch (Exception e) {
                    logger.finest(e);
                }
            }
        }
    }

    void sendPartitionRuntimeState(Address target) {
        if (!node.isRunning()) {
            return;
        }
        assert partitionStateManager.isInitialized();
        assert node.isMaster();
        assert areMigrationTasksAllowed();

        PartitionRuntimeState partitionState = createPartitionStateInternal();
        assert partitionState != null;

        if (logger.isFineEnabled()) {
            logger.fine("Sending partition state, version: " + partitionState.getVersion() + ", to " + target);
        }

        OperationService operationService = nodeEngine.getOperationService();
        PartitionStateOperation op = new PartitionStateOperation(partitionState, true);
        operationService.invokeOnTarget(SERVICE_NAME, op, target);
    }

    void checkClusterPartitionRuntimeStates() {
        if (!partitionStateManager.isInitialized()) {
            return;
        }

        if (!node.isMaster()) {
            return;
        }

        if (!areMigrationTasksAllowed()) {
            // migration is disabled because of a member leave, wait till enabled!
            return;
        }

        int partitionStateVersion = getPartitionStateVersion();
        if (logger.isFineEnabled()) {
            logger.fine("Checking partition state, version: " + partitionStateVersion);
        }

        OperationService operationService = nodeEngine.getOperationService();
        Collection<Member> members = node.clusterService.getMembers();
        for (final Member member : members) {
            if (!member.localMember()) {
                Operation op = new PartitionStateVersionCheckOperation(partitionStateVersion);
                ICompletableFuture<Boolean> future = operationService.invokeOnTarget(SERVICE_NAME, op, member.getAddress());
                future.andThen(new ExecutionCallback<Boolean>() {
                    @Override
                    public void onResponse(Boolean response) {
                        if (!Boolean.TRUE.equals(response)) {
                            logger.fine(member + " has a stale partition state. Will send the most recent partition state now.");
                            sendPartitionRuntimeState(member.getAddress());
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        logger.fine("Failure while checking partition state on " + member, t);
                        sendPartitionRuntimeState(member.getAddress());
                    }
                });
            }
        }
    }

    /**
     * Called on the master node to send the partition tables to other cluster members. It will not publish the partition
     * state if the partitions have not yet been initialized.
     * Waits for {@value PTABLE_SYNC_TIMEOUT_SECONDS} for the members to respond to the partition state operation.
     *
     * @return {@code true} if all cluster members have synced their partition tables, {@code false} otherwise.
     */
    // RU_COMPAT_3_11
    @SuppressWarnings("checkstyle:npathcomplexity")
    boolean syncPartitionRuntimeState() {
        assert !((ReentrantLock) lock).isHeldByCurrentThread();
        assert partitionStateManager.isInitialized();
        assert node.isMaster();
        assert areMigrationTasksAllowed();

        PartitionRuntimeState partitionState = createPartitionStateInternal();
        assert partitionState != null;

        if (logger.isFineEnabled()) {
            logger.fine("Sync'ing partition state, version: " + partitionState.getVersion());
        }

        OperationService operationService = nodeEngine.getOperationService();
        Collection<Member> members = node.clusterService.getMembers();
        Collection<Future<Boolean>> futures = new ArrayList<Future<Boolean>>(members.size());
        for (Member member : members) {
            if (!member.localMember()) {
                PartitionStateOperation op = new PartitionStateOperation(partitionState, true);
                InternalCompletableFuture<Boolean> future =
                        operationService.invokeOnTarget(SERVICE_NAME, op, member.getAddress());
                futures.add(future);
            }
        }

        Collection<Boolean> results = returnWithDeadline(futures, PTABLE_SYNC_TIMEOUT_SECONDS,
                SECONDS, partitionStateSyncTimeoutHandler);

        if (futures.size() != results.size()) {
            return false;
        }

        for (Boolean result : results) {
            if (!result) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets the {@code partitionState} if the node is started and the state is sent by the master known by this node.
     *
     * @param partitionState the new partition state
     * @return {@code true} if the partition state was applied
     */
    public boolean processPartitionRuntimeState(final PartitionRuntimeState partitionState) {
        Address sender = partitionState.getMaster();
        if (!node.getNodeExtension().isStartCompleted()) {
            logger.warning("Ignoring received partition table, startup is not completed yet. Sender: " + sender);
            return false;
        }

        if (!validateSenderIsMaster(sender, "partition table update")) {
            return false;
        }

        return applyNewPartitionTable(partitionState.getPartitionTable(), partitionState.getVersion(),
                partitionState.getCompletedMigrations(), sender);
    }

    private boolean validateSenderIsMaster(Address sender, String messageType) {
        Address master = node.getClusterService().getMasterAddress();
        if (node.isMaster() && !node.getThisAddress().equals(sender)) {
            logger.warning("This is the master node and received " + messageType + " from " + sender
                    + ". Ignoring incoming state! ");
            return false;
        } else {
            if (sender == null || !sender.equals(master)) {
                if (node.clusterService.getMember(sender) == null) {
                    logger.severe("Received " + messageType + " from an unknown member!"
                            + " => Sender: " + sender + ", Master: " + master + "! ");
                    return false;
                } else {
                    logger.warning("Received " + messageType + ", but its sender doesn't seem to be master!"
                            + " => Sender: " + sender + ", Master: " + master + "! "
                            + "(Ignore if master node has changed recently.)");
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Applies the {@code partitionState} sent by the {@code sender} if the new state is newer than the current one
     * and finalizes the migrations.
     * This method does not validate the sender. It is caller method's responsibility.
     * This method will acquire the partition service lock.
     *
     * @param partitionTable the new partition table
     * @param newVersion new partition state version
     * @param completedMigrations latest completed migrations
     * @param sender         the sender of the new partition state
     * @return {@code true} if the partition state version is higher than the current one and was applied or
     * if the partition state version is same as the current one
     */
    private boolean applyNewPartitionTable(PartitionReplica[][] partitionTable,
            int newVersion, Collection<MigrationInfo> completedMigrations, Address sender) {
        try {
            if (!lock.tryLock(PTABLE_SYNC_TIMEOUT_SECONDS, SECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        try {
            Boolean versionCheckResult = checkIfPartitionStateVersionApplied(newVersion, sender);
            if (versionCheckResult != null) {
                return versionCheckResult;
            }

            // RU_COMPAT_3_11
            updatePartitionTableReplicasForCompatibility(partitionTable);

            requestMemberListUpdateIfUnknownMembersFound(sender, partitionTable);
            updatePartitionsAndFinalizeMigrations(partitionTable, newVersion, completedMigrations);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // RU_COMPAT_3_11
    private void updatePartitionTableReplicasForCompatibility(PartitionReplica[][] partitionTable) {
        ClusterServiceImpl clusterService = node.getClusterService();
        Version version = clusterService.getClusterVersion();
        boolean compatibilityMode = version.isLessThan(Versions.V3_12);

        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            PartitionReplica[] replicas = partitionTable[partitionId];
            if (compatibilityMode) {
                for (int ix = 0; ix < replicas.length; ix++) {
                    if (replicas[ix] == null) {
                        continue;
                    }
                    if (PartitionReplica.UNKNOWN_UID.equals(replicas[ix].uuid())) {
                        Address address = replicas[ix].address();
                        Member member = clusterService.getMember(address);
                        if (member == null) {
                            // We don't know this member yet. But it's guaranteed we will learn it
                            // before cluster version is upgraded, since version upgrade transaction
                            // will fail until we get the latest member list.
                            continue;
                        }
                        replicas[ix] = PartitionReplica.from(member);
                    }
                }
            } else {
                for (PartitionReplica replica : replicas) {
                    assert replica == null || !PartitionReplica.UNKNOWN_UID.equals(replica.uuid())
                            : "Invalid replica: " + replica;
                }
            }
        }
    }

    /**
     * Checks whether {@code version} is already applied or a new version.
     *
     * @return {@code true} if current version is equal to the {@code version},
     *          {@code false} if {@code version} is stale,
     *          or {@code null} if {@code version} is newer and should be applied.
     */
    @Nullable
    private Boolean checkIfPartitionStateVersionApplied(int version, Address sender) {
        int currentVersion = partitionStateManager.getVersion();
        if (version < currentVersion) {
            if (logger.isFineEnabled()) {
                logger.fine("Already applied partition state change. Local version: " + currentVersion
                        + ", Master version: " + version + " Master: " + sender);
            }
            return false;
        } else if (version == currentVersion) {
            if (logger.isFineEnabled()) {
                logger.fine("Already applied partition state change. Version: " + currentVersion + ", Master: " + sender);
            }
            return true;
        }
        return null;
    }

    private void requestMemberListUpdateIfUnknownMembersFound(Address sender, PartitionReplica[][] partitionTable) {
        ClusterServiceImpl clusterService = node.clusterService;
        ClusterState clusterState = clusterService.getClusterState();
        Set<PartitionReplica> unknownReplicas = new HashSet<PartitionReplica>();

        for (PartitionReplica[] replicas : partitionTable) {
            for (int index = 0; index < InternalPartition.MAX_REPLICA_COUNT; index++) {
                PartitionReplica replica = replicas[index];
                if (replica == null) {
                    continue;
                }
                if (node.clusterService.getMember(replica.address(), replica.uuid()) == null
                        && (clusterState.isJoinAllowed()
                        || !clusterService.isMissingMember(replica.address(), replica.uuid()))) {
                        unknownReplicas.add(replica);
                }
            }
        }

        if (!unknownReplicas.isEmpty()) {
            if (logger.isWarningEnabled()) {
                StringBuilder s = new StringBuilder("Following unknown addresses are found in partition table")
                        .append(" sent from master[").append(sender).append("].")
                        .append(" (Probably they have recently joined or left the cluster.)")
                        .append(" {");
                for (PartitionReplica replica : unknownReplicas) {
                    s.append("\n\t").append(replica);
                }
                s.append("\n}");
                logger.warning(s.toString());
            }

            Address masterAddress = node.getClusterService().getMasterAddress();
            // If node is shutting down, master can be null.
            if (masterAddress != null && !masterAddress.equals(node.getThisAddress())) {
                // unknown addresses found in partition table, request a new member-list from master
                nodeEngine.getOperationService().send(new TriggerMemberListPublishOp(), masterAddress);
            }
        }
    }

    /**
     * Updates all partitions and version, updates (adds and retains) the completed migrations and finalizes the active
     * migration if it is equal to any completed.
     *
     * @see MigrationManager#scheduleActiveMigrationFinalization(MigrationInfo)
     * @param partitionTable new partition table
     * @param version partition state version
     * @param completedMigrations completed migrations
     */
    private void updatePartitionsAndFinalizeMigrations(PartitionReplica[][] partitionTable,
            int version, Collection<MigrationInfo> completedMigrations) {
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            PartitionReplica[] replicas = partitionTable[partitionId];
            partitionStateManager.updateReplicas(partitionId, replicas);
        }

        applyCompletedMigrationsInternal(completedMigrations, version);
    }

    /**
     * Applies completed migrations to the partition table, sets partition state version
     * and schedules finalization step of completed migrations.
     *
     * @param completedMigrations completed migrations
     * @param newVersion new partition state version
     */
    private void applyCompletedMigrationsInternal(Collection<MigrationInfo> completedMigrations, int newVersion) {
        lock.lock();
        try {
            Collection<MigrationInfo> recentlyCompletedMigrations = new LinkedList<MigrationInfo>();
            for (MigrationInfo migration : completedMigrations) {
                assert migration.getStatus() == MigrationStatus.SUCCESS
                        || migration.getStatus() == MigrationStatus.FAILED
                        : "Invalid migration: " + migration;

                boolean added = migrationManager.addCompletedMigration(migration);
                if (added) {
                    recentlyCompletedMigrations.add(migration);

                    if (migration.getStatus() == MigrationStatus.SUCCESS) {
                        if (logger.isFinestEnabled()) {
                            logger.finest("Applying completed migration " + migration);
                        }
                        InternalPartitionImpl partition = partitionStateManager.getPartitionImpl(migration.getPartitionId());
                        migrationManager.applyMigration(partition, migration);
                    }
                }
            }
            partitionStateManager.setVersion(newVersion);
            if (logger.isFinestEnabled()) {
                logger.finest("Applied completed migrations with partition state version: " + newVersion);
            }

            if (!partitionStateManager.setInitialized()) {
                node.getNodeExtension().onPartitionStateChange();
            }

            migrationManager.retainCompletedMigrations(completedMigrations);

            for (MigrationInfo migration : recentlyCompletedMigrations) {
                migrationManager.scheduleActiveMigrationFinalization(migration);
            }

        } finally {
            lock.unlock();
        }
    }

    public boolean applyCompletedMigrations(Collection<MigrationInfo> migrations, int newVersion, Address sender) {
        if (!validateSenderIsMaster(sender, "completed migrations")) {
            return false;
        }
        lock.lock();
        try {
            if (!partitionStateManager.isInitialized()) {
                if (logger.isFineEnabled()) {
                    logger.fine("Cannot apply completed migrations until partition table is initialized. "
                            + "Partition state version: " + newVersion);
                }
                return false;
            }
            Boolean versionCheckResult = checkIfPartitionStateVersionApplied(newVersion, sender);
            if (versionCheckResult != null) {
                return versionCheckResult;
            }

            applyCompletedMigrationsInternal(migrations, newVersion);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public IPartition[] getPartitions() {
        IPartition[] result = new IPartition[partitionCount];
        System.arraycopy(partitionStateManager.getPartitions(), 0, result, 0, partitionCount);
        return result;
    }

    @Override
    public InternalPartition[] getInternalPartitions() {
        return partitionStateManager.getPartitions();
    }

    @Override
    public InternalPartition getPartition(int partitionId) {
        return getPartition(partitionId, true);
    }

    @Override
    public InternalPartition getPartition(int partitionId, boolean triggerOwnerAssignment) {
        InternalPartitionImpl p = partitionStateManager.getPartitionImpl(partitionId);
        if (triggerOwnerAssignment && p.getOwnerReplicaOrNull() == null) {
            // probably ownerships are not set yet.
            // force it.
            getPartitionOwner(partitionId);
        }
        return p;
    }

    @Override
    public boolean onShutdown(long timeout, TimeUnit unit) {
        if (!node.getClusterService().isJoined()) {
            return true;
        }

        if (node.isLiteMember()) {
            return true;
        }

        CountDownLatch latch = getShutdownLatch();
        InternalOperationService operationService = nodeEngine.getOperationService();

        long timeoutMillis = unit.toMillis(timeout);
        long awaitStep = Math.min(SAFE_SHUTDOWN_MAX_AWAIT_STEP_MILLIS, timeoutMillis);
        try {
            do {
                Address masterAddress = nodeEngine.getMasterAddress();
                if (masterAddress == null) {
                    logger.warning("Safe shutdown failed, master member is not known!");
                    return false;
                }

                if (node.getThisAddress().equals(masterAddress)) {
                    onShutdownRequest(node.getLocalMember());
                } else {
                    operationService.send(new ShutdownRequestOperation(), masterAddress);
                }
                if (latch.await(awaitStep, TimeUnit.MILLISECONDS)) {
                    return true;
                }
                timeoutMillis -= awaitStep;
            } while (timeoutMillis > 0);
        } catch (InterruptedException e) {
            currentThread().interrupt();
            logger.info("Safe shutdown is interrupted!");
        }
        return false;
    }

    private CountDownLatch getShutdownLatch() {
        CountDownLatch latch = shutdownLatchRef.get();
        if (latch == null) {
            latch = new CountDownLatch(1);
            if (!shutdownLatchRef.compareAndSet(null, latch)) {
                latch = shutdownLatchRef.get();
            }
        }
        return latch;
    }

    public void onShutdownRequest(Member member) {
        if (lock.tryLock()) {
            try {
                migrationManager.onShutdownRequest(member);
            } finally {
                lock.unlock();
            }
        }
    }

    public void onShutdownResponse() {
        CountDownLatch latch = shutdownLatchRef.get();
        assert latch != null;
        latch.countDown();
    }

    @Override
    public boolean isMemberStateSafe() {
        return partitionReplicaStateChecker.getPartitionServiceState() == PartitionServiceState.SAFE;
    }

    @Override
    public boolean hasOnGoingMigration() {
        return hasOnGoingMigrationLocal()
                || (!node.isMaster() && partitionReplicaStateChecker.hasOnGoingMigrationMaster(Level.FINEST));
    }

    @Override
    public boolean hasOnGoingMigrationLocal() {
        return migrationManager.hasOnGoingMigration();
    }

    @Override
    public final int getPartitionId(Data key) {
        return HashUtil.hashToIndex(key.getPartitionHash(), partitionCount);
    }

    @Override
    public final int getPartitionId(Object key) {
        return getPartitionId(nodeEngine.toData(key));
    }

    @Override
    public final int getPartitionCount() {
        return partitionCount;
    }

    public long getPartitionMigrationTimeout() {
        return partitionMigrationTimeout;
    }

    @Override
    public PartitionReplicaVersionManager getPartitionReplicaVersionManager() {
        return replicaManager;
    }

    @Override
    public Map<Address, List<Integer>> getMemberPartitionsMap() {
        Collection<Member> dataMembers = node.getClusterService().getMembers(DATA_MEMBER_SELECTOR);
        int dataMembersSize = dataMembers.size();
        int partitionsPerMember = (dataMembersSize > 0 ? (int) ceil((float) partitionCount / dataMembersSize) : 0);

        Map<Address, List<Integer>> memberPartitions = createHashMap(dataMembersSize);
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            Address owner = getPartitionOwnerOrWait(partitionId);

            List<Integer> ownedPartitions = memberPartitions.get(owner);
            if (ownedPartitions == null) {
                ownedPartitions = new ArrayList<Integer>(partitionsPerMember);
                memberPartitions.put(owner, ownedPartitions);
            }
            ownedPartitions.add(partitionId);
        }
        return memberPartitions;
    }

    @Override
    public List<Integer> getMemberPartitions(Address target) {
        List<Integer> ownedPartitions = new LinkedList<Integer>();
        for (int i = 0; i < partitionCount; i++) {
            final Address owner = getPartitionOwner(i);
            if (target.equals(owner)) {
                ownedPartitions.add(i);
            }
        }
        return ownedPartitions;
    }

    @Override
    public List<Integer> getMemberPartitionsIfAssigned(Address target) {
        if (!partitionStateManager.isInitialized()) {
            return Collections.emptyList();
        }
        return getMemberPartitions(target);
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            shouldFetchPartitionTables = false;
            replicaManager.reset();
            partitionStateManager.reset();
            migrationManager.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pauseMigration() {
        migrationManager.pauseMigration();
    }

    @Override
    public void resumeMigration() {
        migrationManager.resumeMigration();
    }

    public boolean areMigrationTasksAllowed() {
        return migrationManager.areMigrationTasksAllowed();
    }

    @Override
    public void shutdown(boolean terminate) {
        logger.finest("Shutting down the partition service");
        migrationManager.stop();
        reset();
    }

    @Probe
    @Override
    public long getMigrationQueueSize() {
        return migrationManager.getMigrationQueueSize();
    }

    @Override
    public PartitionServiceProxy getPartitionServiceProxy() {
        return proxy;
    }

    @Override
    public String addMigrationListener(MigrationListener listener) {
        return partitionEventManager.addMigrationListener(listener);
    }

    @Override
    public boolean removeMigrationListener(String registrationId) {
        return partitionEventManager.removeMigrationListener(registrationId);
    }

    @Override
    public String addPartitionLostListener(PartitionLostListener listener) {
        return partitionEventManager.addPartitionLostListener(listener);
    }

    @Override
    public String addLocalPartitionLostListener(PartitionLostListener listener) {
        return partitionEventManager.addLocalPartitionLostListener(listener);
    }

    @Override
    public boolean removePartitionLostListener(String registrationId) {
        return partitionEventManager.removePartitionLostListener(registrationId);
    }

    @Override
    public void dispatchEvent(PartitionEvent partitionEvent, PartitionEventListener partitionEventListener) {
        partitionEventListener.onEvent(partitionEvent);
    }

    public void addPartitionListener(PartitionListener listener) {
        lock.lock();
        try {
            partitionListener.addChildListener(listener);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isPartitionOwner(int partitionId) {
        InternalPartition partition = partitionStateManager.getPartitionImpl(partitionId);
        return partition.isLocal();
    }

    @Override
    public int getPartitionStateVersion() {
        return partitionStateManager.getVersion();
    }

    @Override
    public void onPartitionLost(IPartitionLostEvent event) {
        partitionEventManager.onPartitionLost(event);
    }

    public void setInternalMigrationListener(InternalMigrationListener listener) {
        migrationManager.setInternalMigrationListener(listener);
    }

    public InternalMigrationListener getInternalMigrationListener() {
        return migrationManager.getInternalMigrationListener();
    }

    public void resetInternalMigrationListener() {
        migrationManager.resetInternalMigrationListener();
    }

    /**
     * @return copy of ongoing replica-sync operations
     */
    public List<ReplicaFragmentSyncInfo> getOngoingReplicaSyncRequests() {
        return replicaManager.getOngoingReplicaSyncRequests();
    }

    /**
     * @return copy of scheduled replica-sync requests
     */
    public List<ScheduledEntry<ReplicaFragmentSyncInfo, Void>> getScheduledReplicaSyncRequests() {
        return replicaManager.getScheduledReplicaSyncRequests();
    }

    public PartitionStateManager getPartitionStateManager() {
        return partitionStateManager;
    }

    public MigrationManager getMigrationManager() {
        return migrationManager;
    }

    public PartitionReplicaManager getReplicaManager() {
        return replicaManager;
    }

    @Override
    public PartitionReplicaStateChecker getPartitionReplicaStateChecker() {
        return partitionReplicaStateChecker;
    }

    public PartitionEventManager getPartitionEventManager() {
        return partitionEventManager;
    }

    boolean isFetchMostRecentPartitionTableTaskRequired() {
        return shouldFetchPartitionTables;
    }

    boolean scheduleFetchMostRecentPartitionTableTaskIfRequired() {
       lock.lock();
       try {
           if (shouldFetchPartitionTables) {
               migrationManager.schedule(new FetchMostRecentPartitionTableTask());
               return true;
           }

           return false;
       } finally {
           lock.unlock();
       }
    }

    public void replaceMember(Member oldMember, Member newMember) {
        lock.lock();
        try {
            partitionStateManager.replaceMember(oldMember, newMember);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PartitionTableView createPartitionTableView() {
        lock.lock();
        try {
            return partitionStateManager.getPartitionTable();
        } finally {
            lock.unlock();
        }
    }

    public boolean commitMigrationOnDestination(MigrationInfo migration, int newVersion, Address sender) {
        lock.lock();
        try {
            if (!validateSenderIsMaster(sender, "migration commit")) {
                return false;
            }

            Boolean versionCheckResult = checkIfPartitionStateVersionApplied(newVersion, sender);
            if (versionCheckResult != null) {
                return versionCheckResult;
            }

            MigrationInfo activeMigrationInfo = migrationManager.getActiveMigration();
            if (migration.equals(activeMigrationInfo)) {
                InternalPartitionImpl partition = partitionStateManager.getPartitionImpl(migration.getPartitionId());
                boolean added = migrationManager.addCompletedMigration(migration);
                assert added : "Could not add completed migration on destination: " + migration;
                migrationManager.applyMigration(partition, migration);
                partitionStateManager.setVersion(newVersion);
                activeMigrationInfo.setStatus(migration.getStatus());
                migrationManager.finalizeMigration(migration);
                if (logger.isFinestEnabled()) {
                    logger.finest("Committed " + migration + " on destination with partition state version: " + newVersion);
                }
                return true;
            }
        } finally {
            lock.unlock();
        }
        return false;
    }


    /**
     * Invoked on a node when it becomes master. It will receive partition states from all members and consolidate them into one.
     * It guarantees the monotonicity of the partition table.
     * <ul>
     * <li>Fetch partition tables from all cluster members</li>
     * <li>Pick the most up to date partition table and apply it to local</li>
     * <li>Complete the pending migration, if present</li>
     * <li>Send the new partition table to all cluster members</li>
     * </ul>
     */
    private class FetchMostRecentPartitionTableTask implements MigrationRunnable {

        private final Address thisAddress = node.getThisAddress();

        private int maxVersion;

        private PartitionRuntimeState newState;

        public void run() {
            ClusterState clusterState = node.getClusterService().getClusterState();
            if (!clusterState.isMigrationAllowed() && !clusterState.isPartitionPromotionAllowed()) {
                // If migrations and promotions are not allowed, partition table cannot be modified and we should have
                // the most recent partition table already. Because cluster state cannot be changed
                // when our partition table is stale.
                logger.fine("No need to fetch the latest partition table. "
                        + "Cluster state does not allow to modify partition table.");
                shouldFetchPartitionTables = false;
                return;
            }
            maxVersion = partitionStateManager.getVersion();
            logger.info("Fetching most recent partition table! my version: " + maxVersion);

            Collection<MigrationInfo> allCompletedMigrations = new HashSet<MigrationInfo>();
            Collection<MigrationInfo> allActiveMigrations = new HashSet<MigrationInfo>();

            collectAndProcessResults(allCompletedMigrations, allActiveMigrations);

            logger.info("Most recent partition table version: " + maxVersion);

            processNewState(allCompletedMigrations, allActiveMigrations);
            publishPartitionRuntimeState();
        }

        private Future<PartitionRuntimeState> fetchPartitionState(Member m) {
            return nodeEngine.getOperationService()
                    .invokeOnTarget(SERVICE_NAME, new FetchPartitionStateOperation(), m.getAddress());
        }

        /** Collects all completed and active migrations and sets the partition state to the latest version. */
        private void collectAndProcessResults(Collection<MigrationInfo> allCompletedMigrations,
                Collection<MigrationInfo> allActiveMigrations) {

            Collection<Member> members = node.clusterService.getMembers(NON_LOCAL_MEMBER_SELECTOR);
            Map<Member, Future<PartitionRuntimeState>> futures = new HashMap<Member, Future<PartitionRuntimeState>>();
            for (Member member : members) {
                Future<PartitionRuntimeState> future = fetchPartitionState(member);
                futures.put(member, future);
            }

            while (!futures.isEmpty()) {
                Iterator<Map.Entry<Member, Future<PartitionRuntimeState>>> iter = futures.entrySet().iterator();
                while (iter.hasNext()) {
                    PartitionRuntimeState state = collectNextPartitionState(iter);
                    if (state == null) {
                        // state can be null, if not initialized or operation is retried
                        continue;
                    }

                    if (maxVersion < state.getVersion()) {
                        newState = state;
                        maxVersion = state.getVersion();
                    }
                    allCompletedMigrations.addAll(state.getCompletedMigrations());

                    if (state.getActiveMigration() != null) {
                        allActiveMigrations.add(state.getActiveMigration());
                    }
                }
            }
        }

        /**
         * Fetches known partition state from next member and returns null if target member is left
         * and/or not a member of this cluster anymore.
         * If future timeouts, then fetch operation is retried until we learn target member's partition state
         * or it leaves the cluster.
         */
        private PartitionRuntimeState collectNextPartitionState(Iterator<Map.Entry<Member, Future<PartitionRuntimeState>>> iter) {
            Map.Entry<Member, Future<PartitionRuntimeState>> next = iter.next();
            Member member = next.getKey();
            Future<PartitionRuntimeState> future = next.getValue();
            boolean collectedState = true;

            try {
                PartitionRuntimeState state = future.get(FETCH_PARTITION_STATE_SECONDS, SECONDS);
                if (state == null) {
                    logger.fine("Received NULL partition state from " + member);
                } else {
                    logger.fine("Received partition state version: " + state.getVersion() + " from " + member);
                }
                return state;
            } catch (InterruptedException e) {
                logger.fine("FetchMostRecentPartitionTableTask is interrupted.");
                Thread.currentThread().interrupt();
            } catch (TimeoutException e) {
                collectedState = false;
                // Fetch partition state operation is idempotent.
                // We will retry it until it we learn the partition state or the member leaves the cluster.
                // We can't just rely on invocation retries, because if connection is dropped while
                // our operation is on the wire, invocation won't get any response and will eventually timeout.
                next.setValue(fetchPartitionState(member));
            } catch (Exception e) {
                Level level = Level.SEVERE;
                if ((e instanceof MemberLeftException) || (e.getCause() instanceof TargetNotMemberException)) {
                    level = Level.FINE;
                }
                logger.log(level, "Failed to fetch partition table from " + member, e);
            } finally {
                if (collectedState) {
                    iter.remove();
                }
            }
            return null;
        }

        /**
         * Applies a partition state and marks all migrations (including local) as complete, when a newer state is received.
         * The method will acquire the partition state lock.
         *
         * @param allCompletedMigrations received completed migrations from other nodes
         * @param allActiveMigrations    received active migrations from other nodes
         */
        private void processNewState(Collection<MigrationInfo> allCompletedMigrations,
                Collection<MigrationInfo> allActiveMigrations) {

            lock.lock();
            try {
                processMigrations(allCompletedMigrations, allActiveMigrations);

                if (newState != null) {
                    maxVersion = Math.max(maxVersion, getPartitionStateVersion()) + 1;
                    logger.info("Applying the most recent of partition state...");
                    applyNewPartitionTable(newState.getPartitionTable(), maxVersion, allCompletedMigrations, thisAddress);
                } else if (partitionStateManager.isInitialized()) {
                    partitionStateManager.incrementVersion();
                    node.getNodeExtension().onPartitionStateChange();

                    for (MigrationInfo migrationInfo : allCompletedMigrations) {
                        if (migrationManager.addCompletedMigration(migrationInfo)) {
                            if (logger.isFinestEnabled()) {
                                logger.finest("Scheduling migration finalization after finding most recent partition table: "
                                        + migrationInfo);
                            }
                            migrationManager.scheduleActiveMigrationFinalization(migrationInfo);
                        }
                    }
                }
                shouldFetchPartitionTables = false;
            } finally {
                lock.unlock();
            }
        }

        /** Moves all migrations to completed (including local) and marks active migrations as {@link MigrationStatus#FAILED}. */
        private void processMigrations(Collection<MigrationInfo> allCompletedMigrations,
                                       Collection<MigrationInfo> allActiveMigrations) {
            allCompletedMigrations.addAll(migrationManager.getCompletedMigrationsCopy());
            if (migrationManager.getActiveMigration() != null) {
                allActiveMigrations.add(migrationManager.getActiveMigration());
            }

            for (MigrationInfo activeMigration : allActiveMigrations) {
                activeMigration.setStatus(MigrationStatus.FAILED);
                if (allCompletedMigrations.add(activeMigration)) {
                    logger.info("Marked active migration " + activeMigration + " as " + MigrationStatus.FAILED);
                }
            }
        }

    }

    @Override
    public String toString() {
        return "InternalPartitionService {"
                + "version: " + getPartitionStateVersion() + ", migrationQ: " + getMigrationQueueSize() + "}";
    }
}
