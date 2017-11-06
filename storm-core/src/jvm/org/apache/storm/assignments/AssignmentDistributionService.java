/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.assignments;

import org.apache.storm.Config;
import org.apache.storm.daemon.supervisor.Supervisor;
import org.apache.storm.generated.Assignment;
import org.apache.storm.generated.SupervisorAssignments;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.SupervisorClient;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <p>A service for distributing master assignments to supervisors, this service makes the assignments notification asynchronous.
 * <p>We support multiple working threads to distribute assignment, every thread has a queue buffer.
 * <p>Master will shuffle its node request to the queues, if the target queue is full, we just discard the request, let the supervisors sync instead.
 * <p/>
 * <pre>{@code
 * Working mode
 *                      +--------+         +-----------------+
 *                      | queue1 |   ==>   | Working thread1 |
 * +--------+ shuffle   +--------+         +-----------------+
 * | Master |   ==>
 * +--------+           +--------+         +-----------------+
 *                      | queue2 |   ==>   | Working thread2 |
 *                      +--------+         +-----------------+
 * }
 * </pre>
 */
public class AssignmentDistributionService implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(AssignmentDistributionService.class);
    private ExecutorService service;

    /**
     * Flag to indicate if the service is active
     */
    private volatile boolean active = false;

    private Random random;
    /**
     * Working threads num.
     */
    private int threadsNum = 0;
    /**
     * Working thread queue size.
     */
    private int queueSize = 0;

    /**
     * Assignments request queue.
     */
    private volatile Map<Integer, LinkedBlockingQueue<NodeAssignments>> assignmentsQueue;

    /**
     * local supervisors for local cluster assignments distribution
     */
    private Map<String, Supervisor> localSupervisors;

    private Map conf;

    private boolean isLocalMode = false; // boolean cache for local mode decision

    /**
     * Function for initialization.
     *
     * @param conf
     */
    public void prepare(Map conf) {
        this.conf = conf;
        this.random = new Random(47);

        this.threadsNum = Utils.getInt(conf.get(Config.NIMBUS_ASSIGNMENTS_SERVICE_THREADS), 10);
        this.queueSize = Utils.getInt(conf.get(Config.NIMBUS_ASSIGNMENTS_SERVICE_THREAD_QUEUE_SIZE), 100);

        this.assignmentsQueue = new HashMap<>();
        for (int i = 0; i < threadsNum; i++) {
            this.assignmentsQueue.put(i, new LinkedBlockingQueue<NodeAssignments>(queueSize));
        }
        //start the thread pool
        this.service = Executors.newFixedThreadPool(threadsNum);
        this.active = true;
        //start the threads
        for (int i = 0; i < threadsNum; i++) {
            this.service.submit(new DistributeTask(this, i));
        }
        // for local cluster
        localSupervisors = new HashMap<>();
        if (ConfigUtils.isLocalMode(conf)) {
            isLocalMode = true;
        }
    }

    @Override
    public void close() throws IOException {
        this.active = false;
        this.service.shutdownNow();
        try {
            this.service.awaitTermination(10l, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.error("Failed to close assignments distribute service");
        }
        this.assignmentsQueue = null;
    }

    public void addAssignmentsForNode(String node, SupervisorAssignments assignments) {
        try {
            boolean success = nextQueue().offer(NodeAssignments.getInstance(node, assignments), 5l, TimeUnit.SECONDS);
            if (!success) {
                LOG.warn("Discard an assignment distribution for node {} because the target sub queue is full", node);
            }

        } catch (InterruptedException e) {
            LOG.error("Add node assignments interrupted: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    static class NodeAssignments {
        private String node;
        private SupervisorAssignments assignments;

        private NodeAssignments(String node, SupervisorAssignments assignments) {
            this.node = node;
            this.assignments = assignments;
        }

        public static NodeAssignments getInstance(String node, SupervisorAssignments assignments) {
            return new NodeAssignments(node, assignments);
        }

        //supervisor assignment id/supervisor id
        public String getNode() {
            return this.node;
        }

        public String getHost() {
            return this.assignments.get_storm_assignment().entrySet().iterator().next().getValue().get_node_host().get(this.node);
        }

        public SupervisorAssignments getAssignments() {
            return this.assignments;
        }

    }

    /**
     * Task to distribute assignments.
     */
    static class DistributeTask implements Runnable {
        private AssignmentDistributionService service;
        private Integer queueIndex;

        DistributeTask(AssignmentDistributionService service, Integer index) {
            this.service = service;
            this.queueIndex = index;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    NodeAssignments nodeAssignments = this.service.nextAssignments(queueIndex);
                    sendAssignmentsToNode(nodeAssignments);
                } catch (InterruptedException e) {
                    if (service.isActive()) {
                        LOG.error("Get an unexpected interrupt when distributing assignments to node, {}", e.getCause());
                    } else {
                        // service is off now just interrupt it.
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void sendAssignmentsToNode(NodeAssignments assignments) {
            if (this.service.isLocalMode) {
                //local node
                Supervisor supervisor = this.service.localSupervisors.get(assignments.getNode());
                if (supervisor != null) {
                    supervisor.sendSupervisorAssignments(assignments.getAssignments());
                } else {
                    LOG.error("Can not find node {} for assignments distribution");
                    throw new RuntimeException("null for node {} supervisor instance");
                }
            } else {
                // distributed mode
                try {
                    SupervisorClient client = SupervisorClient.getConfiguredClient(service.getConf(), assignments.getHost());
                    try {
                        client.getClient().sendSupervisorAssignments(assignments.getAssignments());
                    } catch (Exception e) {
                        //just ignore the exception.
                        LOG.error("{} Exception when trying to send assignments to node: {}", e.getMessage(), assignments.getNode());
                    } finally {
                        try {
                            if (client != null) {
                                client.close();
                            }
                        } catch (Exception e) {
                            LOG.error("Exception closing client for node: {}", assignments.getNode());
                        }
                    }
                } catch (Throwable e) {
                    //just ignore any error/exception.
                    LOG.error("Exception to create supervisor client for node: {}", assignments.getNode());
                }

            }
        }
    }

    public void addLocalSupervisor(Supervisor supervisor) {
        this.localSupervisors.put(supervisor.getId(), supervisor);
    }

    private Integer nextQueueID() {
        return this.random.nextInt(threadsNum);
    }

    private LinkedBlockingQueue<NodeAssignments> nextQueue() {
        return this.assignmentsQueue.get(nextQueueID());
    }

    private LinkedBlockingQueue<NodeAssignments> getQueueByID(Integer queueIndex) {
        return this.assignmentsQueue.get(queueIndex);
    }

    public NodeAssignments nextAssignments(Integer queueIndex) throws InterruptedException {
        NodeAssignments target = null;
        while (true) {
            target = getQueueByID(queueIndex).poll();
            if (target != null) {
                return target;
            }
            Thread.sleep(100L);
        }
    }

    public boolean isActive() {
        return this.active;
    }

    public Map getConf() {
        return this.conf;
    }
}
