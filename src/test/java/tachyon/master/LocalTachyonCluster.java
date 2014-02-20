/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tachyon.master;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import tachyon.Constants;
import tachyon.client.TachyonFS;
import tachyon.conf.CommonConf;
import tachyon.conf.MasterConf;
import tachyon.conf.UserConf;
import tachyon.conf.WorkerConf;
import tachyon.master.Master;
import tachyon.master.MasterInfo;
import tachyon.util.CommonUtils;
import tachyon.worker.Worker;

/**
 * Local Tachyon cluster for unit tests.
 */
public class LocalTachyonCluster {
  private Master mMaster = null;
  private Worker mWorker = null;

  private int mMasterPort;
  private int mWorkerPort;
  private long mWorkerCapacityBytes;

  private String mTachyonHome;
  private String mWorkerDataFolder;

  private Thread mMasterThread = null;
  private Thread mWorkerThread = null;

  private String mLocalhostName = null;
  private UnderFilesystemCluster cluster = null;

  private List<TachyonFS> mClients = new ArrayList<TachyonFS>();

  public LocalTachyonCluster(long workerCapacityBytes) {
    mMasterPort = Constants.DEFAULT_MASTER_PORT - 1000;
    mWorkerPort = Constants.DEFAULT_WORKER_PORT - 1000;
    mWorkerCapacityBytes = workerCapacityBytes;
  }

  public LocalTachyonCluster(int masterPort, int workerPort, long workerCapacityBytes) {
    mMasterPort = masterPort;
    mWorkerPort = workerPort;
    mWorkerCapacityBytes = workerCapacityBytes;
  }

  public synchronized TachyonFS getClient() throws IOException {
    mClients.add(TachyonFS.get(Constants.HEADER + mLocalhostName + ":" + mMasterPort));
    return mClients.get(mClients.size() - 1);
  }

  public String getMasterHostname() {
    return mLocalhostName;
  }

  public int getMasterPort() {
    return mMasterPort;
  }

  public int getWorkerPort() {
    return mWorkerPort;
  }

  public String getTachyonHome(){
    return mTachyonHome;
  }

  public Worker getWorker() {
    return mWorker;
  }

  public MasterInfo getMasterInfo() {
    return mMaster.getMasterInfo();
  }

  public String getEditLogPath() {
    return cluster.getUnderFilesystemAddress() + "/journal/log.data";
  }

  public String getImagePath() {
    return cluster.getUnderFilesystemAddress() + "/journal/image.data";
  }

  private void mkdir(String path) throws IOException {
    if (!CommonUtils.mkdirs(path)) {
      throw new IOException("Failed to make folder: " + path);
    }
  }

  public String getTempFolderInUnderFs() {
    return CommonConf.get().UNDERFS_ADDRESS;
  }

  public void start() throws IOException {
    mTachyonHome = File.createTempFile("Tachyon", "").getAbsoluteFile() + "UnitTest";
    mWorkerDataFolder = mTachyonHome + "/ramdisk";
    String masterDataFolder = mTachyonHome + "/data";
    String masterLogFolder = mTachyonHome + "/logs";

    mkdir(mTachyonHome);
    mkdir(masterDataFolder);
    mkdir(masterLogFolder);

    mLocalhostName = InetAddress.getLocalHost().getCanonicalHostName();

    // To start the UFS either for integration or unit test. If it targets
    // the unit test, UFS is setup over the local file system (see also {@link
    // MockLocalFilesystemCluster} - under folder of
    // "mTachyonHome/tachyon".
    // Otherwise, it starts some distributed file system cluster e.g.,
    // miniDFSCluster (see also {@link tachyon.integration.LocalMiniDFScluster}
    // and setup the folder like "hdfs://xxx:xxx/tachyon".
    cluster = UnderFilesystemCluster.getUnderFilesystemCluster(mTachyonHome + "/dfs");
    if (!cluster.isStarted()) {
      cluster.start();
    }
    String underfsFolder = cluster.getUnderFilesystemAddress() + "/tachyon";
    // To setup the journalFolder under either local file system or distributed ufs
    // like miniDFSCluster
    String masterJournalFolder = cluster.getUnderFilesystemAddress() + "/journal";

    System.setProperty("tachyon.home", mTachyonHome);
    System.setProperty("tachyon.underfs.address", underfsFolder);
    System.setProperty("tachyon.master.hostname", mLocalhostName);
    System.setProperty("tachyon.master.port", mMasterPort + "");
    System.setProperty("tachyon.master.web.port", (mMasterPort + 1) + "");
    System.setProperty("tachyon.worker.port", mWorkerPort + "");
    System.setProperty("tachyon.worker.data.port", (mWorkerPort + 1) + "");
    System.setProperty("tachyon.worker.data.folder", mWorkerDataFolder);
    System.setProperty("tachyon.worker.memory.size", mWorkerCapacityBytes + "");
    System.setProperty("tachyon.user.remote.read.buffer.size.byte", 64 + "");
    System.setProperty("tachyon.worker.to.master.heartbeat.interval.ms", 15 + "");
    System.setProperty("tachyon.master.journal.folder", masterJournalFolder + "/");

    CommonConf.clear();
    MasterConf.clear();
    WorkerConf.clear();
    UserConf.clear();

    mkdir(masterJournalFolder);
    CommonUtils.touch(masterJournalFolder + "/_format_" + System.currentTimeMillis());

    mkdir(CommonConf.get().UNDERFS_DATA_FOLDER);
    mkdir(CommonConf.get().UNDERFS_WORKERS_FOLDER);

    mMaster = new Master(
        new InetSocketAddress(mLocalhostName, mMasterPort), mMasterPort + 1, 1, 1, 1);
    Runnable runMaster = new Runnable() {
      public void run() {
        mMaster.start();
      }
    };
    mMasterThread = new Thread(runMaster);
    mMasterThread.start();

    CommonUtils.sleepMs(null, 10);

    mWorker = Worker.createWorker(
        new InetSocketAddress(mLocalhostName, mMasterPort),
        new InetSocketAddress(mLocalhostName, mWorkerPort),
        mWorkerPort + 1, 1, 1, 1, mWorkerDataFolder, mWorkerCapacityBytes);
    Runnable runWorker = new Runnable() {
      public void run() {
        mWorker.start();
      }
    };
    mWorkerThread = new Thread(runWorker);
    mWorkerThread.start();
  }

  /**
   * Stop both of the tachyon and underfs service threads.
   * @throws Exception
   */
  public void stop() throws Exception {
    stopTFS();
    stopUFS();
  }

  /**
   * Stop the tachyon filesystem's service thread only
   * @throws Exception
   */
  public void stopTFS() throws Exception {
    for (TachyonFS fs : mClients) {
      fs.close();
    }

    mWorker.stop();
    mMaster.stop();

    System.clearProperty("tachyon.home");
    System.clearProperty("tachyon.master.hostname");
    System.clearProperty("tachyon.master.port");
    System.clearProperty("tachyon.master.web.port");
    System.clearProperty("tachyon.worker.port");
    System.clearProperty("tachyon.worker.data.port");
    System.clearProperty("tachyon.worker.data.folder");
    System.clearProperty("tachyon.worker.memory.size");
    System.clearProperty("tachyon.user.remote.read.buffer.size.byte");
    System.clearProperty("tachyon.worker.to.master.heartbeat.interval.ms");
  }

  /**
   * Stop the underfs cluster
   * @throws Exception
   */
  public void stopUFS() throws Exception {
    cluster.shutdown();
    System.clearProperty("tachyon.master.journal.folder");
    System.clearProperty("tachyon.underfs.address");
  }

  public void stopWorker() throws Exception {
    for (TachyonFS fs : mClients) {
      fs.close();
    }
    mClients.clear();
    mWorker.stop();
  }

  public InetSocketAddress getMasterAddress() {
    return new InetSocketAddress(mLocalhostName, mMasterPort);
  }

  public InetSocketAddress getWorkerAddress() {
    return new InetSocketAddress(mLocalhostName, mWorkerPort);
  }

  public String getWorkerDataFolder() {
    return mWorkerDataFolder;
  }

  public static void main(String[] args) throws Exception {
    LocalTachyonCluster cluster = new LocalTachyonCluster(100);
    cluster.start();
    CommonUtils.sleepMs(null, 1000);
    cluster.stop();
    CommonUtils.sleepMs(null, 1000);

    cluster = new LocalTachyonCluster(100);
    cluster.start();
    CommonUtils.sleepMs(null, 1000);
    cluster.stop();
    CommonUtils.sleepMs(null, 1000);
  }
}
