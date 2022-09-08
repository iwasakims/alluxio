/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.raft;

import alluxio.Constants;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.CancelledException;
import alluxio.exception.status.UnavailableException;
import alluxio.grpc.AddQuorumServerRequest;
import alluxio.grpc.GrpcService;
import alluxio.grpc.JournalQueryRequest;
import alluxio.grpc.NetAddress;
import alluxio.grpc.NodeState;
import alluxio.grpc.QuorumServerInfo;
import alluxio.grpc.QuorumServerState;
import alluxio.grpc.TransferLeaderMessage;
import alluxio.master.Master;
import alluxio.master.PrimarySelector;
import alluxio.master.journal.AbstractJournalSystem;
import alluxio.master.journal.AsyncJournalWriter;
import alluxio.master.journal.CatchupFuture;
import alluxio.master.journal.Journal;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.metrics.sink.RatisDropwizardExports;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.util.CommonUtils;
import alluxio.util.ConfigurationUtils;
import alluxio.util.LogUtils;
import alluxio.util.WaitForOptions;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.util.network.NetworkAddressUtils.ServiceType;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import org.apache.commons.io.FileUtils;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.GroupInfoReply;
import org.apache.ratis.protocol.GroupInfoRequest;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.SetConfigurationRequest;
import org.apache.ratis.protocol.exceptions.LeaderNotReadyException;
import org.apache.ratis.retry.ExponentialBackoffRetry;
import org.apache.ratis.retry.RetryPolicy;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.thirdparty.com.google.protobuf.UnsafeByteOperations;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * System for multiplexing many logical journals into a single raft-based journal.
 *
 * This class embeds a RaftServer which implements the raft algorithm, replicating entries across
 * a majority of servers before applying them to the state machine. To make the Ratis system work
 * as an Alluxio journal system, we implement two non-standard behaviors: (1) pre-applying
 * operations on the primary and (2) tightly controlling primary snapshotting.
 * <h1>Pre-apply</h1>
 * <p>
 * Unlike the Ratis framework, Alluxio updates state machine state *before* writing to the
 * journal. This lets us avoid journaling operations which do not result in state modification. To
 * make this work in the Ratis framework, we allow RPCs to modify state directly, then write an
 * entry to Ratis afterwards. Once the entry is journaled, Ratis will attempt to apply the
 * journal entry to each master. The entry has already been applied on the primary, so we treat all
 * journal entries applied to the primary as no-ops to avoid double-application.
 *
 * <h2>Correctness of pre-apply</h2>
 * <p>
 * There are two cases to worry about: (1) incorrectly ignoring an entry and (2) incorrectly
 * applying an entry.
 *
 * <h3> Avoid incorrectly ignoring entries</h3>
 * <p>
 * This could happen if a server thinks it is the primary, and ignores a journal entry served from
 * the real primary. To prevent this, primaries wait for a quiet period before serving requests.
 * During this time, the previous primary will go through at least two election cycles without
 * successfully sending heartbeats to the majority of the cluster. If the old primary successfully
 * sent a heartbeat to a node which elected the new primary, the old primary would realize it isn't
 * primary and step down. Therefore, the old primary will step down and no longer ignore requests by
 * the time the new primary begins sending entries.
 *
 * <h3> Avoid incorrectly applying entries</h3>
 * <p>
 * Entries can never be double-applied to a primary's state because as long as it is the primary, it
 * will ignore all entries, and once it becomes standby, it will completely reset its state and
 * rejoin the cluster.
 *
 * <h1>Snapshot control</h1>
 * <p>
 * The way we apply journal entries to the primary makes it tricky to perform
 * primary state snapshots. Normally Ratis would decide when it wants a snapshot,
 * but with the pre-apply protocol we may be in the middle of modifying state
 * when the snapshot would happen. To manage this, we declare an AtomicBoolean field
 * which decides whether it will be allowed to take snapshots. Normally, snapshots
 * are prohibited on the primary. However, we don't want the primary's log to grow unbounded,
 * so we allow a snapshot to be taken once a day at a user-configured time. To support this,
 * all state changes must first acquire a read lock, and snapshotting requires the
 * corresponding write lock. Once we have the write lock for all state machines, we enable
 * snapshots in Copycat through our AtomicBoolean, then wait for any snapshot to complete.
 */
@ThreadSafe
public class RaftJournalSystem extends AbstractJournalSystem {
  public static final UUID RAFT_GROUP_UUID =
      UUID.fromString("02511d47-d67c-49a3-9011-abb3109a44c1");
  public static final RaftGroupId RAFT_GROUP_ID = RaftGroupId.valueOf(RAFT_GROUP_UUID);

  private static final Logger LOG = LoggerFactory.getLogger(RaftJournalSystem.class);

  private static final AtomicLong CALL_ID_COUNTER = new AtomicLong();
  // Election timeout to use in a single master cluster.
  private static final long SINGLE_MASTER_ELECTION_TIMEOUT_MS = 500;
  private static final String WAITING_FOR_ELECTION = "WAITING_FOR_ELECTION";

  /// Lifecycle: constant from when the journal system is constructed.

  private final File mPath;
  private final InetSocketAddress mLocalAddress;
  private final List<InetSocketAddress> mClusterAddresses;
  /** Controls whether state machine can take snapshots. */
  private final AtomicBoolean mSnapshotAllowed = new AtomicBoolean(true);
  /** Controls whether the quorum leadership can be transferred. */
  private final AtomicBoolean mTransferLeaderAllowed = new AtomicBoolean(false);

  private final Map<String, RatisDropwizardExports> mRatisMetricsMap =
      new ConcurrentHashMap<>();

  /**
   * Listens to the Ratis server to detect gaining or losing primacy. The lifecycle for this
   * object is the same as the lifecycle of the {@link RaftJournalSystem}. When the Ratis server
   * is reset during failover, this object must be re-initialized with the new server.
   */
  private final RaftPrimarySelector mPrimarySelector = new RaftPrimarySelector();

  /// Lifecycle: constant from when the journal system is started.

  /** Contains all journals created by this journal system. */
  private final ConcurrentHashMap<String, RaftJournal> mJournals = new ConcurrentHashMap<>();

  /// Lifecycle: created at startup and re-created when master loses primacy and resets.

  /**
   * Interacts with Ratis, applying entries to masters, taking snapshots,
   * and installing snapshots.
   */
  private JournalStateMachine mStateMachine;
  /**
   * Ratis server.
   */
  private RaftServer mServer;

  /// Lifecycle: created when gaining primacy, destroyed when losing primacy.

  /**
   * Writer which uses a Ratis client to write journal entries. This field is only set when the
   * journal system is primary mode. When primacy is lost, the writer is closed and set to null.
   */
  private RaftJournalWriter mRaftJournalWriter;
  /**
   * Reference to the journal writer shared by all journals. When RPCs create journal contexts, they
   * will use the writer within this reference. The writer is null when the journal is in standby
   * mode.
   */
  private final AtomicReference<AsyncJournalWriter> mAsyncJournalWriter = new AtomicReference<>();
  /**
   * The id for submitting a normal raft client request.
   **/
  private final ClientId mClientId = ClientId.randomId();
  /**
   * The id for submitting a raw raft client request.
   * Should be used for any raft API call that requires a callId.
   **/
  private final ClientId mRawClientId = ClientId.randomId();
  private RaftGroup mRaftGroup;
  private RaftPeerId mPeerId;
  private final Map<String, TransferLeaderMessage> mErrorMessages = new ConcurrentHashMap<>();

  static long nextCallId() {
    return CALL_ID_COUNTER.getAndIncrement() & Long.MAX_VALUE;
  }

  /**
   * Creates a {@link RaftJournalSystem}.
   * @param path where the journal will be stored
   * @param serviceType is either MASTER_RAFT or JOB_MASTER_RAFT
   */
  public RaftJournalSystem(URI path, ServiceType serviceType) {
    this(path,
        NetworkAddressUtils.getConnectAddress(serviceType, Configuration.global()),
        ConfigurationUtils.getEmbeddedJournalAddresses(Configuration.global(), serviceType));
  }

  @VisibleForTesting
  RaftJournalSystem(URI path, InetSocketAddress localAddress,
      List<InetSocketAddress> clusterAddresses) {
    Preconditions.checkState(clusterAddresses.contains(localAddress)
        || NetworkAddressUtils.containsLocalIp(clusterAddresses, Configuration.global()),
        "The cluster addresses (%s) must contain the local master address (%s)",
        clusterAddresses, localAddress);

    mPath = new File(Objects.requireNonNull(path).getPath());
    mLocalAddress = Objects.requireNonNull(localAddress);
    mClusterAddresses = Objects.requireNonNull(clusterAddresses);
  }

  private void maybeMigrateOldJournal() {
    File oldJournalPath = new File(mPath, RAFT_GROUP_UUID.toString());
    File newJournalBasePath = RaftJournalUtils.getRaftJournalDir(mPath);
    File newJournalPath = new File(newJournalBasePath, RAFT_GROUP_UUID.toString());
    if (oldJournalPath.isDirectory() && !newJournalBasePath.exists()) {
      LOG.info("Old journal detected at {} . moving journal to {}", oldJournalPath, newJournalPath);
      if (!newJournalBasePath.mkdirs()) {
        LOG.warn("Cannot create journal directory {}", newJournalBasePath);
      }
      if (!oldJournalPath.renameTo(newJournalPath)) {
        LOG.warn("Failed to move journal from {} to {}", oldJournalPath, newJournalPath);
      }
    }
  }

  /**
   * @return a raft peer id for local raft server
   */
  public synchronized RaftPeerId getLocalPeerId() {
    return mPeerId;
  }

  private synchronized void initServer() throws IOException {
    if (mStateMachine != null) {
      mStateMachine.close();
    }
    mStateMachine = new JournalStateMachine(mJournals, this);

    RaftProperties properties = new RaftProperties();
    Parameters parameters = new Parameters();

    // TODO(feng): implement a custom RpcType to integrate with Alluxio authentication service
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);

    // RPC port
    GrpcConfigKeys.Server.setPort(properties, mLocalAddress.getPort());

    // storage path
    maybeMigrateOldJournal();
    RaftServerConfigKeys.setStorageDir(properties, Collections.singletonList(
        RaftJournalUtils.getRaftJournalDir(mPath)));

    // segment size
    long segmentSize = Configuration.getBytes(PropertyKey.MASTER_JOURNAL_LOG_SIZE_BYTES_MAX);
    LOG.debug("Creating journal with max segment size {}", segmentSize);
    if (segmentSize > Integer.MAX_VALUE) {
      LOG.warn("{} has value {} but must not exceed {}. Resetting to {}.",
          PropertyKey.MASTER_JOURNAL_LOG_SIZE_BYTES_MAX, segmentSize, Integer.MAX_VALUE,
          Integer.MAX_VALUE);
      segmentSize = Integer.MAX_VALUE;
    }
    RaftServerConfigKeys.Log.setSegmentSizeMax(properties, SizeInBytes.valueOf(segmentSize));

    // the following configurations need to be changed when the single journal entry
    // is unexpectedly big.
    RaftServerConfigKeys.Log.Appender.setBufferByteLimit(properties,
        SizeInBytes.valueOf(Configuration.global()
            .getBytes(PropertyKey.MASTER_EMBEDDED_JOURNAL_ENTRY_SIZE_MAX)));
    // this property defines the maximum allowed size of the concurrent journal flush requests.
    // if the total size of the journal entries contained in the flush requests
    // are bigger than the given threshold, Ratis may error out as
    // `Log entry size 117146048 exceeds the max buffer limit of 104857600`
    RaftServerConfigKeys.Write.setByteLimit(properties,
        SizeInBytes.valueOf(Configuration.global()
            .getBytes(PropertyKey.MASTER_EMBEDDED_JOURNAL_FLUSH_SIZE_MAX)));
    // this property defines the maximum allowed size of the concurrent journal write IO tasks.
    // if the total size of the journal entries contained in the write IO tasks
    // are bigger than the given threshold, ratis may error out as
    // `SegmentedRaftLogWorker: elementNumBytes = 78215699 > byteLimit = 67108864`
    RaftServerConfigKeys.Log.setQueueByteLimit(properties, (int) Configuration
        .global().getBytes(PropertyKey.MASTER_EMBEDDED_JOURNAL_FLUSH_SIZE_MAX));

    // Override election/heartbeat timeouts for single master cluster if election timeout is not
    // set explicitly. This is to speed up single master cluster boot-up.
    long min = Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_MIN_ELECTION_TIMEOUT);
    long max = Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_MAX_ELECTION_TIMEOUT);
    if (mClusterAddresses.size() == 1
        && !Configuration.isSetByUser(
        PropertyKey.MASTER_EMBEDDED_JOURNAL_MIN_ELECTION_TIMEOUT)
        && !Configuration.isSetByUser(
        PropertyKey.MASTER_EMBEDDED_JOURNAL_MAX_ELECTION_TIMEOUT)) {
      LOG.info("Overriding election timeout to {}ms for single master cluster.",
          SINGLE_MASTER_ELECTION_TIMEOUT_MS);
      min = SINGLE_MASTER_ELECTION_TIMEOUT_MS;
      max = 2 * min;
    }
    Preconditions.checkState(min < max,
        "Min election timeout (%sms) should be less than max election timeout (%sms)", min, max);

    // election timeout, heartbeat timeout is automatically 1/2 of the value
    RaftServerConfigKeys.Rpc.setTimeoutMin(properties,
        TimeDuration.valueOf(min, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMax(properties,
        TimeDuration.valueOf(max, TimeUnit.MILLISECONDS));

    // request timeout
    RaftServerConfigKeys.Rpc.setRequestTimeout(properties, TimeDuration.valueOf(
        Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_TRANSPORT_REQUEST_TIMEOUT_MS),
        TimeUnit.MILLISECONDS));

    RaftServerConfigKeys.RetryCache.setExpiryTime(properties, TimeDuration.valueOf(
        Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_RETRY_CACHE_EXPIRY_TIME),
        TimeUnit.MILLISECONDS));

    // snapshot retention
    RaftServerConfigKeys.Snapshot.setRetentionFileNum(properties, 3);

    // snapshot interval
    RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(
        properties, true);
    int snapshotAutoTriggerThreshold =
        Configuration.getInt(PropertyKey.MASTER_JOURNAL_CHECKPOINT_PERIOD_ENTRIES);
    RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(properties,
        snapshotAutoTriggerThreshold);

    if (Configuration.getBoolean(PropertyKey.MASTER_JOURNAL_LOCAL_LOG_COMPACTION)) {
      // purges log files after taking a snapshot successfully
      RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(properties, true);
      // leaves no gap between log file purges: all log files included in a newly installed
      // snapshot are purged right away
      RaftServerConfigKeys.Log.setPurgeGap(properties, 1);
    }

    RaftServerConfigKeys.Log.Appender.setInstallSnapshotEnabled(
        properties, false);

    // if left enabled, the System.exit() called by Ratis can deadlock with the AlluxioMaster
    // process shutdown hook. Description:
    // * The AlluxioMaster starts the RaftJournalSystem using RaftJournalSystem.startInternal().
    //   It now holds a synchronized lock on RaftJournalSystem.
    // * startInternal calls mServer.start() and fails for any reason, calling System.exit(int) -->
    //   Runtime.getRuntime().exit(int) in Ratis.
    // * Runtime.getRuntime().exit(int) calls the shutdown hooks, including the {@link ProcessUtils)
    //   --> process.stop() --> RaftJournalSystem.stopInternal(), which cannot proceed because of
    //   the synchronized lock on RaftJournalSystem.
    // This line disables the System.exit(int) call in Ratis internally in favor of an
    // Exception being thrown. This prevents the deadlock.
    org.apache.ratis.util.ExitUtils.disableSystemExit();

    /*
     * Soft disable RPC level safety.
     *
     * Without these overrides, the leader will step down upon detecting a long running GC over
     * 10sec. This is not desirable for a single master cluster. Additionally, reduced safety should
     * be provided via standard leader election in clustered mode.
     */
    RaftServerConfigKeys.Rpc.setSlownessTimeout(properties,
        TimeDuration.valueOf(Long.MAX_VALUE, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.LeaderElection.setLeaderStepDownWaitTime(properties,
        TimeDuration.valueOf(Long.MAX_VALUE, TimeUnit.MILLISECONDS));

    long messageSize = Configuration.getBytes(
        PropertyKey.MASTER_EMBEDDED_JOURNAL_TRANSPORT_MAX_INBOUND_MESSAGE_SIZE);
    GrpcConfigKeys.setMessageSizeMax(properties,
        SizeInBytes.valueOf(messageSize));
    RatisDropwizardExports.registerRatisMetricReporters(mRatisMetricsMap);

    // TODO(feng): clean up embedded journal configuration
    // build server
    mServer = RaftServer.newBuilder()
        .setServerId(mPeerId)
        .setGroup(mRaftGroup)
        .setStateMachine(mStateMachine)
        .setProperties(properties)
        .setParameters(parameters)
        .build();
    super.registerMetrics();
    MetricsSystem.registerGaugeIfAbsent(MetricKey.CLUSTER_LEADER_INDEX.getName(),
        this::getLeaderIndex);
    MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_ROLE_ID.getName(), this::getRoleId);
    MetricsSystem.registerGaugeIfAbsent(MetricKey.CLUSTER_LEADER_ID.getName(), this::getLeaderId);
  }

  /**
   * @return current raft group
   */
  @VisibleForTesting
  public synchronized RaftGroup getCurrentGroup() {
    try {
      Iterator<RaftGroup> groupIter = mServer.getGroups().iterator();
      Preconditions.checkState(groupIter.hasNext(), "no group info found");
      RaftGroup group = groupIter.next();
      Preconditions.checkState(group.getGroupId() == RAFT_GROUP_ID,
          String.format("Invalid group id %s, expecting %s", group.getGroupId(), RAFT_GROUP_ID));
      return group;
    } catch (IOException | IllegalStateException e) {
      LogUtils.warnWithException(LOG, "Failed to get raft group, falling back to initial group", e);
      return mRaftGroup;
    }
  }

  private RaftClient createClient() {
    return createClient(Configuration.getMs(
        PropertyKey.MASTER_EMBEDDED_JOURNAL_RAFT_CLIENT_REQUEST_TIMEOUT));
  }

  private RaftClient createClient(long timeoutMs) {
    long retryBaseMs =
        Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_RAFT_CLIENT_REQUEST_INTERVAL);
    long maxSleepTimeMs =
        Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_MAX_ELECTION_TIMEOUT);
    RaftProperties properties = new RaftProperties();
    Parameters parameters = new Parameters();
    RaftClientConfigKeys.Rpc.setRequestTimeout(properties,
        TimeDuration.valueOf(timeoutMs, TimeUnit.MILLISECONDS));
    RetryPolicy retryPolicy = ExponentialBackoffRetry.newBuilder()
        .setBaseSleepTime(TimeDuration.valueOf(retryBaseMs, TimeUnit.MILLISECONDS))
        .setMaxSleepTime(TimeDuration.valueOf(maxSleepTimeMs, TimeUnit.MILLISECONDS))
        .build();
    return RaftClient.newBuilder()
        .setRaftGroup(mRaftGroup)
        .setClientId(mClientId)
        .setLeaderId(null)
        .setProperties(properties)
        .setParameters(parameters)
        .setRetryPolicy(retryPolicy)
        .build();
  }

  @Override
  public synchronized Journal createJournal(Master master) {
    RaftJournal journal = new RaftJournal(master, mPath.toURI(), mAsyncJournalWriter);
    mJournals.put(master.getName(), journal);
    return journal;
  }

  @Override
  public synchronized void gainPrimacy() {
    LOG.info("Gaining primacy.");
    mSnapshotAllowed.set(false);
    RaftJournalAppender client = new RaftJournalAppender(mServer, this::createClient, mRawClientId);

    Runnable closeClient = () -> {
      try {
        client.close();
      } catch (IOException e) {
        LOG.warn("Failed to close raft client: {}", e.toString());
      }
    };

    try {
      catchUp(mStateMachine, client);
    } catch (TimeoutException e) {
      closeClient.run();
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      closeClient.run();
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    long nextSN = mStateMachine.upgrade() + 1;

    Preconditions.checkState(mRaftJournalWriter == null);
    mRaftJournalWriter = new RaftJournalWriter(nextSN, client);
    mAsyncJournalWriter
        .set(new AsyncJournalWriter(mRaftJournalWriter, () -> getJournalSinks(null)));
    mTransferLeaderAllowed.set(true);
    LOG.info("Gained primacy.");
  }

  @Override
  public synchronized void losePrimacy() {
    LOG.info("Losing primacy.");
    if (mServer.getLifeCycleState() != LifeCycle.State.RUNNING) {
      // Avoid duplicate shut down Ratis server
      return;
    }
    mTransferLeaderAllowed.set(false);
    try {
      // Close async writer first to flush pending entries.
      mAsyncJournalWriter.get().close();
      mRaftJournalWriter.close();
    } catch (IOException e) {
      LOG.warn("Error closing journal writer: {}", e.toString());
    } finally {
      mAsyncJournalWriter.set(null);
      mRaftJournalWriter = null;
    }
    LOG.info("Shutting down Raft server");
    try {
      mServer.close();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Fatal error: failed to leave Raft cluster while stepping down", e);
    }
    LOG.info("Shut down Raft server");
    try {
      mSnapshotAllowed.set(true);
      initServer();
    } catch (IOException e) {
      throw new IllegalStateException(String.format(
          "Fatal error: failed to init Raft cluster with addresses %s while stepping down",
          mClusterAddresses), e);
    }
    LOG.info("Bootstrapping new Raft server");
    try {
      mServer.start();
    } catch (IOException e) {
      throw new IllegalStateException(String.format(
          "Fatal error: failed to start Raft cluster with addresses %s while stepping down",
          mClusterAddresses), e);
    }

    LOG.info("Raft server successfully restarted and lost primacy");
  }

  @Override
  public synchronized Map<String, Long> getCurrentSequenceNumbers() {
    Preconditions.checkState(mStateMachine != null, "State machine not initialized");
    long currentGlobalState = mStateMachine.getLastAppliedSequenceNumber();
    Map<String, Long> sequenceMap = new HashMap<>();
    for (String master : mJournals.keySet()) {
      // Return the same global sequence for each master.
      sequenceMap.put(master, currentGlobalState);
    }
    return sequenceMap;
  }

  @Override
  public synchronized void suspend(Runnable interruptCallback) throws IOException {
    mSnapshotAllowed.set(false);
    mStateMachine.suspend(interruptCallback);
  }

  @Override
  public synchronized void resume() throws IOException {
    try {
      mStateMachine.resume();
    } finally {
      mSnapshotAllowed.set(true);
    }
  }

  /**
   * @return whether the journal is suspended
   */
  public synchronized boolean isSuspended() {
    return mStateMachine.isSuspended();
  }

  @Override
  public synchronized CatchupFuture catchup(Map<String, Long> journalSequenceNumbers) {
    // Given sequences should be the same for each master for embedded journal.
    List<Long> distinctSequences =
        journalSequenceNumbers.values().stream().distinct().collect(Collectors.toList());
    Preconditions.checkState(distinctSequences.size() == 1, "incorrect journal sequences");
    return mStateMachine.catchup(distinctSequences.get(0));
  }

  @Override
  public synchronized void checkpoint() throws IOException {
    // TODO(feng): consider removing this once we can automatically propagate
    //             snapshots from standby master
    try (RaftJournalAppender client = new RaftJournalAppender(mServer, this::createClient,
        mRawClientId)) {
      mSnapshotAllowed.set(true);
      catchUp(mStateMachine, client);
      mStateMachine.takeLocalSnapshot();
      // TODO(feng): maybe prune logs after snapshot
    } catch (TimeoutException e) {
      LOG.warn("Timeout while performing snapshot: {}", e.toString());
      throw new IOException("Timeout while performing snapshot", e);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while performing snapshot: {}", e.toString());
      Thread.currentThread().interrupt();
      throw new CancelledException("Interrupted while performing snapshot", e);
    } finally {
      mSnapshotAllowed.set(false);
    }
  }

  @Override
  public synchronized Map<alluxio.grpc.ServiceType, GrpcService> getJournalServices() {
    Map<alluxio.grpc.ServiceType, GrpcService> services = new HashMap<>();
    services.put(alluxio.grpc.ServiceType.RAFT_JOURNAL_SERVICE, new GrpcService(
        new RaftJournalServiceHandler(mStateMachine.getSnapshotReplicationManager())));
    return services;
  }

  /**
   * Attempts to catch up. If the master loses leadership during this method, it will return early.
   *
   * The caller is responsible for detecting and responding to leadership changes.
   */
  private void catchUp(JournalStateMachine stateMachine, RaftJournalAppender client)
      throws TimeoutException, InterruptedException {
    long startTime = System.currentTimeMillis();
    long waitBeforeRetry =
        Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_CATCHUP_RETRY_WAIT);
    // Wait for any outstanding snapshot to complete.
    CommonUtils.waitFor("snapshotting to finish", () -> !stateMachine.isSnapshotting(),
        WaitForOptions.defaults().setTimeoutMs(10 * Constants.MINUTE_MS));
    OptionalLong endCommitIndex = OptionalLong.empty();
    try {
      // raft peer IDs are unique, so there should really only ever be one result.
      // If for some reason there is more than one..it should be fine as it only
      // affects the completion time estimate in the logs.
      synchronized (this) { // synchronized to appease findbugs; shouldn't make any difference
        RaftPeerId serverId = mServer.getId();
        Optional<RaftProtos.CommitInfoProto> commitInfo = getGroupInfo().getCommitInfos().stream()
            .filter(commit -> serverId.equals(RaftPeerId.valueOf(commit.getServer().getId())))
            .findFirst();
        if (commitInfo.isPresent()) {
          endCommitIndex = OptionalLong.of(commitInfo.get().getCommitIndex());
        } else {
          throw new IOException("Commit info was not present. Couldn't find the current server's "
              + "latest commit");
        }
      }
    } catch (IOException e) {
      LogUtils.warnWithException(LOG, "Failed to get raft log information before replay."
          + " Replay statistics will not be available", e);
    }

    RaftJournalProgressLogger progressLogger =
        new RaftJournalProgressLogger(mStateMachine, endCommitIndex);

    // Loop until we lose leadership or convince ourselves that we are caught up and we are the only
    // master serving. To convince ourselves of this, we need to accomplish three steps:
    //
    // 1. Write a unique ID to Ratis.
    // 2. Wait for the ID to by applied to the state machine. This proves that we are
    //    caught up since Ratis cannot apply commits from a previous term after applying
    //    commits from a later term.
    // 3. Wait for a quiet period to elapse without anything new being written to Ratis. This is a
    //    heuristic to account for the time it takes for a node to realize it is no longer the
    //    leader. If two nodes think they are leader at the same time, they will both write unique
    //    IDs to the journal, but only the second one has a chance of becoming leader. The first
    //    will see that an entry was written after its ID, and double check that it is still the
    //    leader before trying again.
    while (true) {
      if (mPrimarySelector.getState() != NodeState.PRIMARY) {
        return;
      }
      long lastAppliedSN = stateMachine.getLastAppliedSequenceNumber();
      long gainPrimacySN = ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, 0);
      LOG.info("Performing catchup. Last applied SN: {}. Catchup ID: {}",
          lastAppliedSN, gainPrimacySN);
      Exception ex;
      try {
        JournalEntry entry = JournalEntry.newBuilder().setSequenceNumber(gainPrimacySN).build();
        CompletableFuture<RaftClientReply> future = client.sendAsync(
            Message.valueOf(UnsafeByteOperations.unsafeWrap(entry.toByteArray())));
        RaftClientReply reply = future.get(5, TimeUnit.SECONDS);
        ex = reply.getException();
      } catch (TimeoutException | ExecutionException | IOException e) {
        ex = e;
      }

      if (ex != null) {
        // LeaderNotReadyException typically indicates Ratis is still replaying the journal.
        if (ex instanceof LeaderNotReadyException) {
          progressLogger.logProgress();
        } else {
          LOG.info("Exception submitting term start entry: {}", ex.toString());
        }
        // avoid excessive retries when server is not ready
        Thread.sleep(waitBeforeRetry);
        continue;
      }

      // Wait election timeout so that this master and other masters have time to realize they
      // are not leader.
      try {
        long maxElectionTimeoutMs =
            Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_MAX_ELECTION_TIMEOUT);
        CommonUtils.waitFor("check primacySN " + gainPrimacySN + " and lastAppliedSN "
            + lastAppliedSN + " to be applied to leader", () ->
            stateMachine.getLastAppliedSequenceNumber() == lastAppliedSN
                && stateMachine.getLastPrimaryStartSequenceNumber() == gainPrimacySN,
            WaitForOptions.defaults()
                .setInterval(Constants.SECOND_MS)
                .setTimeoutMs((int) maxElectionTimeoutMs));
      } catch (TimeoutException e) {
        // Someone has committed a journal entry since we started trying to catch up.
        // Restart the catchup process.
        continue;
      }
      LOG.info("Caught up in {}ms. Last sequence number from previous term: {}.",
          System.currentTimeMillis() - startTime, stateMachine.getLastAppliedSequenceNumber());
      return;
    }
  }

  @Override
  public synchronized void startInternal() throws IOException {
    LOG.info("Initializing Raft Journal System");
    mPeerId = RaftJournalUtils.getPeerId(mLocalAddress);
    Set<RaftPeer> peers = mClusterAddresses.stream()
        .map(addr -> RaftPeer.newBuilder()
                .setId(RaftJournalUtils.getPeerId(addr))
                .setAddress(addr)
                .build()
        )
        .collect(Collectors.toSet());
    mRaftGroup = RaftGroup.valueOf(RAFT_GROUP_ID, peers);
    initServer();
    super.registerMetrics();
    LOG.info("Starting Raft journal system. Cluster addresses: {}. Local address: {}",
        mClusterAddresses, mLocalAddress);
    long startTime = System.currentTimeMillis();
    try {
      mServer.start();
    } catch (IOException e) {
      String errorMessage =
          MessageFormat.format("Failed to bootstrap raft cluster with addresses {0}: {1}",
              Arrays.toString(mClusterAddresses.toArray()),
              e.getCause() == null ? e : e.getCause().toString());
      throw new IOException(errorMessage, e.getCause());
    }
    LOG.info("Started Raft Journal System in {}ms", System.currentTimeMillis() - startTime);
    joinQuorum();
  }

  private void joinQuorum() {
    // Send a request to join the quorum.
    // If the server is already part of the quorum, this operation is a noop.
    AddQuorumServerRequest request = AddQuorumServerRequest.newBuilder()
        .setServerAddress(NetAddress.newBuilder()
            .setHost(mLocalAddress.getHostString())
            .setRpcPort(mLocalAddress.getPort()))
        .build();
    RaftClient client = createClient();
    client.async().sendReadOnly(Message.valueOf(
        UnsafeByteOperations.unsafeWrap(
            JournalQueryRequest
                .newBuilder()
                .setAddQuorumServerRequest(request)
                .build().toByteArray()
        ))).whenComplete((reply, t) -> {
          if (t != null) {
            LogUtils.warnWithException(LOG, "Exception occurred while joining quorum", t);
          }
          if (reply != null && reply.getException() != null) {
            LogUtils.warnWithException(LOG,
                "Received an error while joining quorum", reply.getException());
          }
          try {
            client.close();
          } catch (IOException e) {
            LogUtils.warnWithException(LOG, "Exception occurred closing raft client", e);
          }
        });
  }

  @Override
  public synchronized void stopInternal() throws IOException {
    LOG.info("Shutting down raft journal");
    if (mRaftJournalWriter != null) {
      mRaftJournalWriter.close();
    }
    mStateMachine.setServerClosing();
    try {
      mServer.close();
    } catch (IOException e) {
      throw new RuntimeException("Failed to shut down Raft server", e);
    } finally {
      mStateMachine.afterServerClosing();
    }
    LOG.info("Journal shutdown complete");
  }

  /**
   * Used to get information of internal RAFT quorum.
   *
   * @return list of information for participating servers in RAFT quorum
   */
  public synchronized List<QuorumServerInfo> getQuorumServerInfoList() throws IOException {
    List<QuorumServerInfo> quorumMemberStateList = new LinkedList<>();
    GroupInfoReply groupInfo = getGroupInfo();
    if (groupInfo == null) {
      throw new UnavailableException("Cannot get raft group info");
    }
    if (groupInfo.getException() != null) {
      throw groupInfo.getException();
    }
    RaftProtos.RoleInfoProto roleInfo = groupInfo.getRoleInfoProto();
    if (roleInfo == null) {
      throw new UnavailableException("Cannot get server role info");
    }
    RaftProtos.LeaderInfoProto leaderInfo = roleInfo.getLeaderInfo();
    if (leaderInfo == null) {
      throw new UnavailableException("Cannot get server leader info");
    }
    for (RaftProtos.ServerRpcProto member : leaderInfo.getFollowerInfoList()) {
      HostAndPort hp = HostAndPort.fromString(member.getId().getAddress());
      NetAddress memberAddress = NetAddress.newBuilder().setHost(hp.getHost())
          .setRpcPort(hp.getPort()).build();

      long maxElectionTimeoutMs =
          Configuration.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_MAX_ELECTION_TIMEOUT);
      quorumMemberStateList.add(QuorumServerInfo.newBuilder()
              .setIsLeader(false)
              .setPriority(member.getId().getPriority())
              .setServerAddress(memberAddress)
          .setServerState(member.getLastRpcElapsedTimeMs() > maxElectionTimeoutMs
              ? QuorumServerState.UNAVAILABLE : QuorumServerState.AVAILABLE).build());
    }
    NetAddress self = NetAddress.newBuilder()
        .setHost(mLocalAddress.getHostString())
        .setRpcPort(mLocalAddress.getPort())
        .build();
    quorumMemberStateList.add(QuorumServerInfo.newBuilder()
            .setIsLeader(true)
            .setPriority(roleInfo.getSelf().getPriority())
            .setServerAddress(self)
        .setServerState(QuorumServerState.AVAILABLE).build());
    quorumMemberStateList.sort(Comparator.comparing(info -> info.getServerAddress().toString()));
    return quorumMemberStateList;
  }

  /**
   * Sends a message to a raft server asynchronously.
   *
   * @param server the raft peer id of the target server
   * @param message the message to send
   * @return a future to be completed with the client reply
   */
  public synchronized CompletableFuture<RaftClientReply> sendMessageAsync(
      RaftPeerId server, Message message) {
    return sendMessageAsync(server, message, Configuration.getMs(
        PropertyKey.MASTER_EMBEDDED_JOURNAL_RAFT_CLIENT_REQUEST_TIMEOUT));
  }

  /**
   * Sends a message to a raft server asynchronously.
   *
   * @param server the raft peer id of the target server
   * @param message the message to send
   * @param timeoutMs the message timeout in milliseconds
   * @return a future to be completed with the client reply
   */
  public synchronized CompletableFuture<RaftClientReply> sendMessageAsync(
      RaftPeerId server, Message message, long timeoutMs) {
    RaftClient client = createClient(timeoutMs);
    RaftClientRequest request = RaftClientRequest.newBuilder()
            .setClientId(mRawClientId)
            .setServerId(server)
            .setGroupId(RAFT_GROUP_ID)
            .setCallId(nextCallId())
            .setMessage(message)
            .setType(RaftClientRequest.staleReadRequestType(0))
            .setSlidingWindowEntry(null)
            .build();
    return client.getClientRpc().sendRequestAsync(request)
            .whenComplete((reply, t) -> {
              try {
                client.close();
              } catch (IOException e) {
                throw new CompletionException(e);
              }
            });
  }

  private GroupInfoReply getGroupInfo() throws IOException {
    GroupInfoRequest groupInfoRequest = new GroupInfoRequest(mRawClientId, getLocalPeerId(),
        RAFT_GROUP_ID, nextCallId());
    return getRaftServer().getGroupInfo(groupInfoRequest);
  }

  /**
   * @return {@code true} if this journal system is the leader
   */
  @VisibleForTesting
  public synchronized boolean isLeader() {
    return mServer != null
        && mServer.getLifeCycleState() == LifeCycle.State.RUNNING
        && mPrimarySelector.getState() == NodeState.PRIMARY;
  }

  /**
   * Removes from RAFT quorum, a server with given address.
   * For server to be removed, it should be in unavailable state in quorum.
   *
   * @param serverNetAddress address of the server to remove from the quorum
   * @throws IOException raft exception
   */
  public synchronized void removeQuorumServer(NetAddress serverNetAddress) throws IOException {
    InetSocketAddress serverAddress = InetSocketAddress
        .createUnresolved(serverNetAddress.getHost(), serverNetAddress.getRpcPort());
    RaftPeerId peerId = RaftJournalUtils.getPeerId(serverAddress);
    try (RaftClient client = createClient()) {
      Collection<RaftPeer> peers = mServer.getGroups().iterator().next().getPeers();
      RaftClientReply reply = client.admin().setConfiguration(peers.stream()
          .filter(peer -> !peer.getId().equals(peerId))
          .collect(Collectors.toList()));
      if (reply.getException() != null) {
        throw reply.getException();
      }
    }
  }

  /**
   * Resets RaftPeer priorities.
   *
   * @throws IOException raft exception
   */
  public synchronized void resetPriorities() throws IOException {
    List<RaftPeer> resetPeers = new ArrayList<>();
    final int NEUTRAL_PRIORITY = 1;
    for (RaftPeer peer : mRaftGroup.getPeers()) {
      resetPeers.add(
              RaftPeer.newBuilder(peer)
              .setPriority(NEUTRAL_PRIORITY)
              .build()
      );
    }
    LOG.info("Resetting RaftPeer priorities");
    try (RaftClient client = createClient()) {
      RaftClientReply reply = client.admin().setConfiguration(resetPeers);
      processReply(reply, "failed to reset master priorities to 1");
    }
  }

  /**
   * Transfers the leadership of the quorum to another server.
   *
   * @param newLeaderNetAddress the address of the server
   * @return the guid of transfer leader command
   */
  public synchronized String transferLeadership(NetAddress newLeaderNetAddress) {
    final boolean allowed = mTransferLeaderAllowed.getAndSet(false);
    String transferId = UUID.randomUUID().toString();
    if (!allowed) {
      String msg = "transfer is not allowed at the moment because the master is "
          + (mRaftJournalWriter == null ? "still gaining primacy" : "already transferring the ")
          + "leadership";
      mErrorMessages.put(transferId, TransferLeaderMessage.newBuilder().setMsg(msg).build());
      return transferId;
    }
    try {
      InetSocketAddress serverAddress = InetSocketAddress
          .createUnresolved(newLeaderNetAddress.getHost(), newLeaderNetAddress.getRpcPort());
      List<RaftPeer> oldPeers = new ArrayList<>(mRaftGroup.getPeers());
      // The NetUtil function is used by Ratis to convert InetSocketAddress to string
      String strAddr = NetUtils.address2String(serverAddress);
      // if you cannot find the address in the quorum, throw exception.
      if (oldPeers.stream().map(RaftPeer::getAddress).noneMatch(addr -> addr.equals(strAddr))) {
        throw new IOException(String.format("<%s> is not part of the quorum <%s>.",
                strAddr, oldPeers.stream().map(RaftPeer::getAddress).collect(Collectors.toList())));
      }
      if (strAddr.equals(mRaftGroup.getPeer(mPeerId).getAddress())) {
        throw new IOException(String.format("%s is already the leader", strAddr));
      }

      RaftPeerId newLeaderPeerId = RaftJournalUtils.getPeerId(serverAddress);
      /* update priorities to enable transfer */
      List<RaftPeer> peersWithNewPriorities = new ArrayList<>();
      for (RaftPeer peer : oldPeers) {
        peersWithNewPriorities.add(
            RaftPeer.newBuilder(peer)
                .setPriority(peer.getId().equals(newLeaderPeerId) ? 2 : 1)
                .build()
        );
      }
      try (RaftClient client = createClient()) {
        String stringPeers = "[" + peersWithNewPriorities.stream().map(RaftPeer::toString)
            .collect(Collectors.joining(", ")) + "]";
        LOG.info("Applying new peer state before transferring leadership: {}", stringPeers);
        RaftClientReply reply = client.admin().setConfiguration(peersWithNewPriorities);
        processReply(reply, "failed to set master priorities before initiating election");
      }
      /* transfer leadership */
      LOG.info("Transferring leadership to master with address <{}> and with RaftPeerId <{}>",
          serverAddress, newLeaderPeerId);
      // fire and forget: need to immediately return as the master will shut down its RPC servers
      // once the TransferLeadershipRequest is initiated.
      final int SLEEP_TIME_MS = 3_000;
      final int TRANSFER_LEADER_WAIT_MS = 30_000;
      new Thread(() -> {
        try (RaftClient client = createClient()) {
          Thread.sleep(SLEEP_TIME_MS);
          RaftClientReply reply1 = client.admin().transferLeadership(newLeaderPeerId,
              TRANSFER_LEADER_WAIT_MS);
          processReply(reply1, "election failed");
        } catch (Throwable t) {
          LOG.error("caught an error when executing transfer: {}", t.getMessage());
          // we only allow transfers again if the transfer is unsuccessful: a success means it
          // will soon lose primacy
          mTransferLeaderAllowed.set(true);
          mErrorMessages.put(transferId, TransferLeaderMessage.newBuilder()
              .setMsg(t.getMessage()).build());
          /* checking the transfer happens in {@link QuorumElectCommand} */
        }
      }).start();
      LOG.info("Transferring leadership initiated");
    } catch (Throwable t) {
      mTransferLeaderAllowed.set(true);
      LOG.warn(t.getMessage());
      mErrorMessages.put(transferId, TransferLeaderMessage.newBuilder()
          .setMsg(t.getMessage()).build());
    }
    return transferId;
  }

  /**
   * @param reply from the ratis operation
   * @throws IOException raft exception
   */
  private void processReply(RaftClientReply reply, String msgToUser) throws IOException {
    if (!reply.isSuccess()) {
      IOException ioe = reply.getException() != null
              ? reply.getException()
              : new IOException(String.format("reply <%s> failed", reply));
      LOG.error("{}. Error: {}", msgToUser, ioe);
      throw new IOException(msgToUser);
    }
  }

  /**
   * Gets exception message throwing when transfer leader.
   * @param transferId the guid of transferLeader command
   * @return the exception
   */
  public synchronized TransferLeaderMessage getTransferLeaderMessage(String transferId) {
    if (mErrorMessages.get(transferId) != null) {
      return mErrorMessages.get(transferId);
    } else {
      return TransferLeaderMessage.newBuilder().setMsg("").build();
    }
  }

  /**
   * Adds a server to the quorum.
   *
   * @param serverNetAddress the address of the server
   * @throws IOException if error occurred while performing the operation
   */
  public synchronized void addQuorumServer(NetAddress serverNetAddress) throws IOException {
    InetSocketAddress serverAddress = InetSocketAddress
        .createUnresolved(serverNetAddress.getHost(), serverNetAddress.getRpcPort());
    RaftPeerId peerId = RaftJournalUtils.getPeerId(serverAddress);
    Collection<RaftPeer> peers = mServer.getGroups().iterator().next().getPeers();
    if (peers.stream().anyMatch((peer) -> peer.getId().equals(peerId))) {
      return;
    }
    RaftPeer newPeer = RaftPeer.newBuilder()
            .setId(peerId)
            .setAddress(serverAddress)
            .build();
    List<RaftPeer> newPeers = new ArrayList<>(peers);
    newPeers.add(newPeer);
    RaftClientReply reply = mServer.setConfiguration(
        new SetConfigurationRequest(mRawClientId, mPeerId, RAFT_GROUP_ID, nextCallId(), newPeers));
    if (reply.getException() != null) {
      throw reply.getException();
    }
  }

  @Override
  public synchronized boolean isEmpty() {
    return mRaftJournalWriter != null && mRaftJournalWriter.getNextSequenceNumberToWrite() == 0;
  }

  @Override
  public boolean isFormatted() {
    return mPath.exists();
  }

  @Override
  public void format() throws IOException {
    if (mPath.isDirectory()) {
      if (alluxio.util.io.FileUtils.isStorageDirAccessible(mPath.getPath())) {
        FileUtils.cleanDirectory(mPath);
      } else {
        throw new AccessDeniedException(mPath.getPath());
      }
    } else {
      if (mPath.exists()) {
        FileUtils.forceDelete(mPath);
      }
      if (!mPath.mkdirs()) {
        throw new AccessDeniedException(mPath.getPath());
      }
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("JournalPath", mPath)
        .add("Address", mLocalAddress)
        .add("State", mPrimarySelector.getState())
        .add("Cluster", mClusterAddresses)
        .add("RaftGroup", mRaftGroup)
        .toString();
  }

  /**
   * @return a primary selector that reflects the Raft quorum status
   */
  public PrimarySelector getPrimarySelector() {
    return mPrimarySelector;
  }

  /**
   * @return whether it is allowed to take a local snapshot
   */
  public boolean isSnapshotAllowed() {
    return mSnapshotAllowed.get();
  }

  /**
   * Notifies the journal that the leadership state has changed.
   * @param isLeader whether the local server is teh current leader
   */
  public void notifyLeadershipStateChanged(boolean isLeader) {
    mPrimarySelector.notifyStateChanged(
        isLeader ? NodeState.PRIMARY : NodeState.STANDBY);
  }

  @VisibleForTesting
  synchronized RaftServer getRaftServer() {
    return mServer;
  }

  /**
   * Updates raft group with the current values from raft server.
   */
  public synchronized void updateGroup() {
    RaftGroup newGroup = getCurrentGroup();
    if (!newGroup.equals(mRaftGroup)) {
      LOG.info("Raft group updated: old {}, new {}", mRaftGroup, newGroup);
      mRaftGroup = newGroup;
    }
  }

  @Nullable
  private RaftProtos.RoleInfoProto getRaftRoleInfo() {
    GroupInfoReply groupInfo = null;
    try {
      groupInfo = getGroupInfo();
    } catch (IOException e) {
      LOG.error("Error while getting RAFT group info", e);
    }
    if (groupInfo == null || groupInfo.getException() != null) {
      return null;
    }
    return groupInfo.getRoleInfoProto();
  }

  /**
   * Get the role index. {@link RaftProtos.RaftPeerRole}.
   *
   * @return the role enum
   */
  public int getRoleId() {
    RaftProtos.RoleInfoProto roleInfo = getRaftRoleInfo();
    if (roleInfo != null) {
      return roleInfo.getRoleValue();
    } else {
      return -1;
    }
  }

  /**
   * Get the leader id. {@link RaftProtos.RaftPeerRole}.
   *
   * @return the leader id
   */
  public String getLeaderId() {
    RaftProtos.RoleInfoProto roleInfo = getRaftRoleInfo();
    if (roleInfo == null) {
      return WAITING_FOR_ELECTION;
    }
    if (roleInfo.getRole() == RaftProtos.RaftPeerRole.LEADER) {
      return getLocalPeerId().toString();
    }
    RaftProtos.FollowerInfoProto followerInfo = roleInfo.getFollowerInfo();
    if (followerInfo == null) {
      return WAITING_FOR_ELECTION;
    }
    if (followerInfo.getLeaderInfo().getId() == null
        || followerInfo.getLeaderInfo().getId().getId() == null) {
      return WAITING_FOR_ELECTION;
    }
    return followerInfo.getLeaderInfo().getId().getId().toStringUtf8();
  }

  /**
   * Gets leader index. The return integer means the leader index of embedded journal addresses
   * -1 means leader not found.
   *
   * @return the leader index
   */
  protected int getLeaderIndex() {
    // -1 means leader not found
    String leaderId = getLeaderId();
    if (WAITING_FOR_ELECTION.equals(leaderId)) {
      return -1;
    }
    String leaderAddress = leaderId.replace('_', ':');
    int index = 0;
    for (InetSocketAddress address : mClusterAddresses) {
      if (address.toString().equals(leaderAddress)) {
        return index;
      }
      index++;
    }
    return -1;
  }
}
