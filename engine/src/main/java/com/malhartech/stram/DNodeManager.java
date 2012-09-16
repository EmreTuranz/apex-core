/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.stram;

import com.malhartech.dag.Node;
import com.malhartech.dag.NodeSerDe;
import com.malhartech.stram.NodeDeployInfo.NodeInputDeployInfo;
import com.malhartech.stram.NodeDeployInfo.NodeOutputDeployInfo;
import com.malhartech.stram.StramChildAgent.DeployRequest;
import com.malhartech.stram.StramChildAgent.UndeployRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StramToNodeRequest.RequestType;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingNodeHeartbeat.DNodeState;
import com.malhartech.stram.TopologyDeployer.PTComponent;
import com.malhartech.stram.TopologyDeployer.PTContainer;
import com.malhartech.stram.TopologyDeployer.PTInput;
import com.malhartech.stram.TopologyDeployer.PTNode;
import com.malhartech.stram.TopologyDeployer.PTOutput;
import com.malhartech.stram.conf.Topology;
import com.malhartech.stram.conf.Topology.InputPort;
import com.malhartech.stram.conf.Topology.NodeDecl;
import com.malhartech.stram.conf.Topology.StreamDecl;
import com.malhartech.stram.webapp.NodeInfo;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 *
 * Tracks topology provisioning/allocation to containers<p>
 * <br>
 * The tasks include<br>
 * Provisioning nodes one container at a time. Each container gets assigned the nodes, streams and its context<br>
 * Monitors run time operations including heartbeat protocol and node status<br>
 * Node recovery and restart<br>
 * <br>
 *
 */

public class DNodeManager
{
  private final static Logger LOG = LoggerFactory.getLogger(DNodeManager.class);
  private long windowStartMillis = System.currentTimeMillis();
  private int windowSizeMillis = 500;
  private final int heartbeatTimeoutMillis = 30000;
  private int checkpointIntervalMillis = 30000;
  private final NodeSerDe nodeSerDe = StramUtils.getNodeSerDe(null);

  private class NodeStatus
  {
    StreamingNodeHeartbeat lastHeartbeat;
    final PTComponent node;
    final PTContainer container;
    int tuplesTotal;
    int bytesTotal;

    private NodeStatus(PTContainer container, PTComponent node) {
      this.node = node;
      this.container = container;
    }

    boolean canShutdown()
    {
      // idle or output adapter
      if ((lastHeartbeat != null && DNodeState.IDLE.name().equals(lastHeartbeat.getState()))) {
        return true;
      }
      return false;
    }
  }

  protected final  Map<String, String> containerStopRequests = new ConcurrentHashMap<String, String>();
  protected final  ConcurrentLinkedQueue<DeployRequest> containerStartRequests = new ConcurrentLinkedQueue<DeployRequest>();
  private final Map<String, StramChildAgent> containers = new ConcurrentHashMap<String, StramChildAgent>();
  private final Map<String, NodeStatus> nodeStatusMap = new ConcurrentHashMap<String, NodeStatus>();
  private final TopologyDeployer deployer;
  private final String checkpointDir;

  public DNodeManager(Topology topology) {
    this.deployer = new TopologyDeployer(topology);

    this.windowSizeMillis = topology.getConf().getInt(Topology.STRAM_WINDOW_SIZE_MILLIS, 500);
    // try to align to it pleases eyes.
    windowStartMillis -= (windowStartMillis % 1000);
    checkpointDir = topology.getConf().get(Topology.STRAM_CHECKPOINT_DIR, "stram/" + System.currentTimeMillis() + "/checkpoints");
    this.checkpointIntervalMillis = topology.getConf().getInt(Topology.STRAM_CHECKPOINT_INTERVAL_MILLIS, this.checkpointIntervalMillis);

    // fill initial deploy requests
    for (PTContainer container : deployer.getContainers()) {
      this.containerStartRequests.add(new DeployRequest(container, null));
    }

  }

  public int getNumRequiredContainers()
  {
    return containerStartRequests.size();
  }

  protected TopologyDeployer getTopologyDeployer() {
    return deployer;
  }

  /**
   * Check periodically that child containers phone home
   *
   */
  public void monitorHeartbeat() {
    long currentTms = System.currentTimeMillis();
    for (Map.Entry<String,StramChildAgent> cse : containers.entrySet()) {
       String containerId = cse.getKey();
       StramChildAgent cs = cse.getValue();
       if (!cs.isComplete && cs.lastHeartbeatMillis + heartbeatTimeoutMillis < currentTms) {
         // TODO: separate startup timeout handling
         if (cs.createdMillis + heartbeatTimeoutMillis < currentTms) {
           // issue stop as probably process is still hanging around (would have been detected by Yarn otherwise)
           LOG.info("Triggering restart for container {} after heartbeat timeout.", containerId);
           containerStopRequests.put(containerId, containerId);
           //restartContainer(containerId);
         }
       }
    }
  }

  /**
   * Schedule container restart. Resolves downstream dependencies and checkpoint states.
   * Called by Stram after a failed container is reported by the RM,
   * or after heartbeat timeout occurs.
   * @param containerId
   */
  public void scheduleContainerRestart(String containerId) {
    LOG.info("Initiating recovery for container {}", containerId);

    StramChildAgent cs = getContainerAgent(containerId);

    // building the checkpoint dependency,
    // downstream nodes will appear first in map
    // TODO: traversal needs to include inline upstream nodes
    Map<PTNode, Long> checkpoints = new LinkedHashMap<PTNode, Long>();
    for (PTNode node : cs.container.nodes) {
      getRecoveryCheckpoint(node, checkpoints);
    }

    Map<PTContainer, List<PTNode>> resetNodes = new HashMap<PTContainer, List<TopologyDeployer.PTNode>>();
    // group by container
    for (PTNode node : checkpoints.keySet()) {
        List<PTNode> nodes = resetNodes.get(node.container);
        if (nodes == null) {
          nodes = new ArrayList<TopologyDeployer.PTNode>();
          resetNodes.put(node.container, nodes);
        }
        nodes.add(node);
    }

    // stop affected downstream dependency nodes (all except failed container)
    AtomicInteger undeployAckCountdown = new AtomicInteger();
    for (Map.Entry<PTContainer, List<PTNode>> e : resetNodes.entrySet()) {
      if (e.getKey() != cs.container) {
        StreamingContainerContext ctx = createStramChildInitContext(e.getValue(), e.getKey(), checkpoints);
        UndeployRequest r = new UndeployRequest(e.getKey(), undeployAckCountdown, null);
        r.setNodes(ctx.nodeList);
        undeployAckCountdown.incrementAndGet();
        StramChildAgent downstreamContainer = getContainerAgent(e.getKey().containerId);
        downstreamContainer.addRequest(r);
      }
    }

    // schedule deployment for replacement container, depends on above downstream nodes stop
    AtomicInteger failedContainerDeployCnt = new AtomicInteger(1);
    DeployRequest dr = new DeployRequest(cs.container, failedContainerDeployCnt, undeployAckCountdown);
    dr.checkpoints = checkpoints;
    // launch replacement container, the deploy request will be queued with new container agent in assignContainer
    containerStartRequests.add(dr);

    // (re)deploy affected downstream nodes
    AtomicInteger redeployAckCountdown = new AtomicInteger();
    for (Map.Entry<PTContainer, List<PTNode>> e : resetNodes.entrySet()) {
      if (e.getKey() != cs.container) {
        StreamingContainerContext ctx = createStramChildInitContext(e.getValue(), e.getKey(), checkpoints);
        DeployRequest r = new DeployRequest(e.getKey(), redeployAckCountdown, failedContainerDeployCnt);
        r.setNodes(ctx.nodeList);
        redeployAckCountdown.incrementAndGet();
        StramChildAgent downstreamContainer = getContainerAgent(e.getKey().containerId);
        downstreamContainer.addRequest(r);
      }
    }

  }

  public void markComplete(String containerId) {
    StramChildAgent cs = containers.get(containerId);
    if (cs == null) {
      LOG.warn("Completion status for unknown container {}", containerId);
      return;
    }
    cs.isComplete = true;
  }

  /**
   * Create deploy info for logical node.<p>
   * <br>
   * @param dnodeId
   * @param nodeDecl
   * @return {@link com.malhartech.stram.NodeDeployInfo}
   *
   */
  private NodeDeployInfo createNodeContext(String dnodeId, NodeDecl nodeDecl)
  {
    NodeDeployInfo ndi = new NodeDeployInfo();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      // populate custom properties
      Node node = StramUtils.initNode(nodeDecl.getNodeClass(), dnodeId, nodeDecl.getProperties());
      this.nodeSerDe.write(node, os);
      ndi.serializedNode = os.toByteArray();
      os.close();
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize " + nodeDecl + "(" + nodeDecl.getNodeClass() + ")", e);
    }
    ndi.properties = nodeDecl.getProperties();
    ndi.declaredId = nodeDecl.getId();
    ndi.id = dnodeId;
    return ndi;
  }

  public StreamingContainerContext assignContainerForTest(String containerId, InetSocketAddress bufferServerAddress)
  {
    for (PTContainer container : this.deployer.getContainers()) {
      if (container.containerId == null) {
        container.containerId = containerId;
        container.bufferServerAddress = bufferServerAddress;
        StreamingContainerContext scc = createStramChildInitContext(container.nodes, container, Collections.<PTNode, Long>emptyMap());
        containers.put(containerId, new StramChildAgent(container, scc));
        return scc;
      }
    }
    throw new IllegalStateException("There are no more containers to deploy.");
  }

  /**
   * Get nodes/streams for next container. Multiple nodes can share a container.
   *
   * @param containerId
   * @param bufferServerAddress Buffer server for publishers on the container.
   * @param cdr
   */
  public void assignContainer(DeployRequest cdr, String containerId, InetSocketAddress bufferServerAddress) {
    PTContainer container = cdr.container;
    if (container.containerId != null) {
      LOG.info("Removing existing container agent {}", cdr.container.containerId);
      this.containers.remove(container.containerId);
    } else {
      container.bufferServerAddress = bufferServerAddress;
    }
    container.containerId = containerId;

    Map<PTNode, Long> checkpoints = cdr.checkpoints;
    if (checkpoints == null) {
      checkpoints = Collections.emptyMap();
    }
    StreamingContainerContext initCtx = createStramChildInitContext(container.nodes, container, checkpoints);
    cdr.setNodes(initCtx.nodeList);
    initCtx.nodeList = new ArrayList<NodeDeployInfo>(0);

    StramChildAgent sca = new StramChildAgent(container, initCtx);
    containers.put(containerId, sca);
    sca.addRequest(cdr);
  }

  /**
   * Create the protocol mandated node/stream info for bootstrapping StramChild.
   * @param container
   * @param deployNodes
   * @param checkpoints
   * @return StreamingContainerContext
   */
  private StreamingContainerContext createStramChildInitContext(List<PTNode> deployNodes, PTContainer container, Map<PTNode, Long> checkpoints) {
    StreamingContainerContext scc = new StreamingContainerContext();
    scc.setWindowSizeMillis(this.windowSizeMillis);
    scc.setStartWindowMillis(this.windowStartMillis);
    scc.setCheckpointDfsPath(this.checkpointDir);

//    List<StreamPConf> streams = new ArrayList<StreamPConf>();
    Map<NodeDeployInfo, PTNode> nodes = new LinkedHashMap<NodeDeployInfo, PTNode>();
    Map<String, NodeOutputDeployInfo> publishers = new LinkedHashMap<String, NodeOutputDeployInfo>();

    for (PTNode node : deployNodes) {
      NodeDeployInfo ndi = createNodeContext(node.id, node.getLogicalNode());
      Long checkpointWindowId = checkpoints.get(node);
      if (checkpointWindowId != null) {
        LOG.debug("Node {} has checkpoint state {}", node.id, checkpointWindowId);
        ndi.checkpointWindowId = checkpointWindowId;
      }
      nodes.put(ndi, node);
      ndi.inputs = new ArrayList<NodeInputDeployInfo>(node.inputs.size());
      ndi.outputs = new ArrayList<NodeOutputDeployInfo>(node.outputs.size());

      for (PTOutput out : node.outputs) {
        final StreamDecl streamDecl = out.logicalStream;
        // buffer server or inline publisher
        NodeOutputDeployInfo portInfo = new NodeOutputDeployInfo();
        portInfo.declaredStreamId = streamDecl.getId();
        portInfo.portName = out.portName;

        if (!(streamDecl.isInline() && this.deployer.isDownStreamInline(out))) {
          portInfo.bufferServerHost = node.container.bufferServerAddress.getHostName();
          portInfo.bufferServerPort = node.container.bufferServerAddress.getPort();
          if (streamDecl.getSerDeClass() != null) {
            portInfo.serDeClassName = streamDecl.getSerDeClass().getName();
          }
        } else {
          // target set below
          //portInfo.inlineTargetNodeId = "-1subscriberInOtherContainer";
        }
        //portInfo.setBufferServerChannelType(streamDecl.getSource().getNode().getId());

        ndi.outputs.add(portInfo);
        publishers.put(node.id + "/" + streamDecl.getId(), portInfo);
      }
    }

    // after we know all publishers within container, determine subscribers

    for (Map.Entry<NodeDeployInfo, PTNode> nodeEntry : nodes.entrySet()) {
      NodeDeployInfo ndi = nodeEntry.getKey();
      PTNode node = nodeEntry.getValue();
      for (PTInput in : node.inputs) {
        final StreamDecl streamDecl = in.logicalStream;
        // input from other node(s) OR input adapter
        if (streamDecl.getSource() == null) {
          throw new IllegalStateException("source is null: " + in);
        }
        PTComponent sourceNode = in.source;

        NodeInputDeployInfo inputInfo = new NodeInputDeployInfo();
        inputInfo.declaredStreamId = streamDecl.getId();
        inputInfo.portName = in.portName;
        inputInfo.sourceNodeId = sourceNode.id;
        inputInfo.sourcePortName = in.logicalStream.getSource().getPortName();
        String partSuffix = "";
        if (in.partition != null) {
          inputInfo.partitionKeys = Arrays.asList(in.partition);
          partSuffix = "/" + ndi.id; // each partition is separate group
        }

        if (streamDecl.isInline() && sourceNode.container == node.container) {
          // inline input (both nodes in same container and inline hint set)
          NodeOutputDeployInfo outputInfo = publishers.get(sourceNode.id + "/" + streamDecl.getId());
          if (outputInfo == null) {
            throw new IllegalStateException("Missing publisher for inline stream " + streamDecl);
          }
        } else {
          // buffer server input
          // FIXME: address to come from upstream node, should be guaranteed assigned first
          InetSocketAddress addr = in.source.container.bufferServerAddress;
          if (addr == null) {
            LOG.warn("upstream address not assigned: " + in.source);
            addr = container.bufferServerAddress;
          }
          inputInfo.bufferServerHost = addr.getHostName();
          inputInfo.bufferServerPort = addr.getPort();
          if (streamDecl.getSerDeClass() != null) {
            inputInfo.serDeClassName = streamDecl.getSerDeClass().getName();
          }
          // buffer server wide unique subscriber grouping:
          // publisher id + stream name + partition identifier (if any)
          inputInfo.bufferServerSubscriberType = sourceNode.id + "/" + streamDecl.getId() + partSuffix;
        }
        ndi.inputs.add(inputInfo);
      }
    }

    scc.nodeList = new ArrayList<NodeDeployInfo>(nodes.keySet());

    for (Map.Entry<NodeDeployInfo, PTNode> e : nodes.entrySet()) {
      this.nodeStatusMap.put(e.getKey().id, new NodeStatus(container, e.getValue()));
    }

    return scc;

  }

  public StramChildAgent getContainerAgent(String containerId) {
    StramChildAgent cs = containers.get(containerId);
    if (cs == null) {
      throw new AssertionError("Unknown container " + containerId);
    }
    return cs;
  }

  public ContainerHeartbeatResponse processHeartbeat(ContainerHeartbeat heartbeat)
  {
    boolean containerIdle = true;
    long currentTimeMillis = System.currentTimeMillis();

    for (StreamingNodeHeartbeat shb : heartbeat.getDnodeEntries()) {

      NodeStatus status = nodeStatusMap.get(shb.getNodeId());
      if (status == null) {
        LOG.error("Heartbeat for unknown node {} (container {})", shb.getNodeId(), heartbeat.getContainerId());
        continue;
      }

      //ReflectionToStringBuilder b = new ReflectionToStringBuilder(shb);
      //LOG.info("node {} ({}) heartbeat: {}, totalTuples: {}, totalBytes: {} - {}",
      //         new Object[]{shb.getNodeId(), status.node.getLogicalId(), b.toString(), status.tuplesTotal, status.bytesTotal, heartbeat.getContainerId()});

      status.lastHeartbeat = shb;
      if (!status.canShutdown()) {
        containerIdle = false;
        status.bytesTotal += shb.getNumberBytesProcessed();
        status.tuplesTotal += shb.getNumberTuplesProcessed();

        // checkpoint tracking
        PTNode node = (PTNode)status.node;
        if (shb.getLastBackupWindowId() != 0) {
          synchronized (node.checkpointWindows) {
            if (!node.checkpointWindows.isEmpty()) {
              Long lastCheckpoint = node.checkpointWindows.get(node.checkpointWindows.size()-1);
              // no need to do any work unless checkpoint moves
              if (lastCheckpoint.longValue() != shb.getLastBackupWindowId()) {
                // keep track of current
                node.checkpointWindows.add(shb.getLastBackupWindowId());
                // TODO: purge older checkpoints, if no longer needed downstream
              }
            } else {
              node.checkpointWindows.add(shb.getLastBackupWindowId());
            }
          }
        }
      }
    }

    StramChildAgent cs = getContainerAgent(heartbeat.getContainerId());
    cs.lastHeartbeatMillis = currentTimeMillis;

    ContainerHeartbeatResponse rsp = cs.pollRequest();
    if (rsp == null) {
      rsp = new ContainerHeartbeatResponse();
    }

    // below should be merged into pollRequest
    if (containerIdle && isApplicationIdle()) {
      LOG.info("requesting idle shutdown for container {}", heartbeat.getContainerId());
      rsp.shutdown = true;
    } else {
      if (cs != null && cs.shutdownRequested) {
        LOG.info("requesting idle shutdown for container {}", heartbeat.getContainerId());
        rsp.shutdown = true;
      }
    }

    List<StramToNodeRequest> requests = new ArrayList<StramToNodeRequest>();
    if (checkpointIntervalMillis > 0) {
      if (cs.lastCheckpointRequestMillis + checkpointIntervalMillis < currentTimeMillis) {
        for (PTNode node : cs.container.nodes) {
          StramToNodeRequest backupRequest = new StramToNodeRequest();
          backupRequest.setNodeId(node.id);
          backupRequest.setRequestType(RequestType.CHECKPOINT);
          requests.add(backupRequest);
        }
        cs.lastCheckpointRequestMillis = currentTimeMillis;
      }
    }
    rsp.nodeRequests = requests;
    return rsp;
  }

  private boolean isApplicationIdle()
  {
    for (NodeStatus nodeStatus : this.nodeStatusMap.values()) {
      if (!nodeStatus.canShutdown()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Compute checkpoint required for a given node to be recovered.
   * This is done by looking at checkpoints available for downstream dependencies first,
   * and then selecting the most recent available checkpoint that is smaller than downstream.
   * @param node Node for which to find recovery checkpoint
   * @param recoveryCheckpoints Map to collect all downstream recovery checkpoints
   * @return Checkpoint that can be used to recover node (along with dependent nodes in recoveryCheckpoints).
   */
  public long getRecoveryCheckpoint(PTNode node, Map<PTNode, Long> recoveryCheckpoints) {
    long maxCheckpoint = node.getRecentCheckpoint();
    // find smallest most recent subscriber checkpoint
    for (PTOutput out : node.outputs) {
      for (InputPort targetPort : out.logicalStream.getSinks()) {
        NodeDecl lDownNode = targetPort.getNode();
        if (lDownNode != null) {
          List<PTNode> downNodes = deployer.getNodes(lDownNode);
          for (PTNode downNode : downNodes) {
            Long downstreamCheckpoint = recoveryCheckpoints.get(downNode);
            if (downstreamCheckpoint == null) {
              // downstream traversal
              downstreamCheckpoint = getRecoveryCheckpoint(downNode, recoveryCheckpoints);
            }
            maxCheckpoint = Math.min(maxCheckpoint, downstreamCheckpoint);
          }
        }
      }
    }
    // find most recent checkpoint for downstream dependency
    long c1 = 0;
    synchronized (node.checkpointWindows) {
      if (node.checkpointWindows != null && !node.checkpointWindows.isEmpty()) {
        if ((c1 = node.checkpointWindows.getFirst().longValue()) <= maxCheckpoint) {
          long c2 = 0;
          while (node.checkpointWindows.size() > 1 && (c2 = node.checkpointWindows.get(1).longValue()) <= maxCheckpoint) {
            node.checkpointWindows.removeFirst();
            // TODO: async hdfs cleanup task
            LOG.warn("Checkpoint purging not implemented node={} windowId={}", node.id,  c1);
            c1 = c2;
          }
        } else {
          c1 = 0;
        }
      }
    }
    recoveryCheckpoints.put(node, c1);
    return c1;
  }

  /**
   * Mark all containers for shutdown, next container heartbeat response
   * will propagate the shutdown request. This is controlled soft shutdown.
   * If containers don't respond, the application can be forcefully terminated
   * via yarn using forceKillApplication.
   */
  public void shutdownAllContainers() {
    for (StramChildAgent cs : this.containers.values()) {
      cs.shutdownRequested = true;
    }
  }

  public void addContainerStopRequest(String containerId) {
    containerStopRequests.put(containerId, containerId);
  }

  public ArrayList<NodeInfo> getNodeInfoList() {
    ArrayList<NodeInfo> nodeInfoList = new ArrayList<NodeInfo>(this.nodeStatusMap.size());
    for (NodeStatus ns : this.nodeStatusMap.values()) {
      NodeInfo ni = new NodeInfo();
      ni.containerId = ns.container.containerId;
      ni.id = ns.node.id;
      ni.name = ns.node.getLogicalId();
      StreamingNodeHeartbeat hb = ns.lastHeartbeat;
      if (hb != null) {
        // initial heartbeat not yet received
        ni.status = hb.getState();
        ni.totalBytes = ns.bytesTotal;
        ni.totalTuples = ns.tuplesTotal;
        ni.lastHeartbeat = ns.lastHeartbeat.getGeneratedTms();
      } else {
        // TODO: proper node status tracking
        StramChildAgent cs = containers.get(ns.container.containerId);
        if (cs != null) {
          ni.status = cs.isComplete ? "CONTAINER_COMPLETE" : "CONTAINER_NEW";
        }
      }
      nodeInfoList.add(ni);
    }
    return nodeInfoList;
  }

}
