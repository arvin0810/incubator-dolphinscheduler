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

package org.apache.dolphinscheduler.server.master.dispatch.host;

import org.apache.dolphinscheduler.common.utils.CollectionUtils;
import org.apache.dolphinscheduler.common.utils.StringUtils;
import org.apache.dolphinscheduler.remote.utils.Host;
import org.apache.dolphinscheduler.remote.utils.NamedThreadFactory;
import org.apache.dolphinscheduler.server.master.dispatch.context.ExecutionContext;
import org.apache.dolphinscheduler.server.master.dispatch.host.assign.HostWeight;
import org.apache.dolphinscheduler.server.master.dispatch.host.assign.LowerWeightRoundRobin;
import org.apache.dolphinscheduler.server.registry.ZookeeperRegistryCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dolphinscheduler.common.Constants.COMMA;


/**
 *  round robin host manager
 */
public class LowerWeightHostManager extends CommonHostManager {

    private final Logger logger = LoggerFactory.getLogger(LowerWeightHostManager.class);

    /**
     * zookeeper registry center
     */
    @Autowired
    private ZookeeperRegistryCenter registryCenter;

    /**
     * round robin host manager
     */
    private RoundRobinHostManager roundRobinHostManager;

    /**
     * selector
     */
    private LowerWeightRoundRobin selector;

    /**
     * worker host weights
     */
    private ConcurrentHashMap<String, Set<HostWeight>> workerHostWeights;

    /**
     * worker group host lock
     */
    private Lock lock;

    /**
     * executor service
     */
    private ScheduledExecutorService executorService;

    @PostConstruct
    public void init(){
        this.selector = new LowerWeightRoundRobin();
        this.workerHostWeights = new ConcurrentHashMap<>();
        this.lock = new ReentrantLock();
        this.executorService = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("LowerWeightHostManagerExecutor"));
        this.executorService.scheduleWithFixedDelay(new RefreshResourceTask(),35, 40, TimeUnit.SECONDS);
        this.roundRobinHostManager = new RoundRobinHostManager();
        this.roundRobinHostManager.setZookeeperNodeManager(getZookeeperNodeManager());
    }

    @PreDestroy
    public void close(){
        this.executorService.shutdownNow();
    }

    /**
     * select host
     * @param context context
     * @return host
     */
    @Override
    public Host select(ExecutionContext context){
        Set<HostWeight> workerHostWeights = getWorkerHostWeights(context.getWorkerGroup());
        if(CollectionUtils.isNotEmpty(workerHostWeights)){
            return selector.select(workerHostWeights).getHost();
        } else{
            return roundRobinHostManager.select(context);
        }
    }

    @Override
    public Host select(Collection<Host> nodes) {
        throw new UnsupportedOperationException("not support");
    }

    private void syncWorkerHostWeight(Map<String, Set<HostWeight>> workerHostWeights){
        lock.lock();
        try {
            workerHostWeights.clear();
            workerHostWeights.putAll(workerHostWeights);
        } finally {
            lock.unlock();
        }
    }

    private Set<HostWeight> getWorkerHostWeights(String workerGroup){
        lock.lock();
        try {
            return workerHostWeights.get(workerGroup);
        } finally {
            lock.unlock();
        }
    }

    class RefreshResourceTask implements Runnable{

        @Override
        public void run() {
            try {
                Map<String, Set<String>> workerGroupNodes = zookeeperNodeManager.getWorkerGroupNodes();
                Set<Map.Entry<String, Set<String>>> entries = workerGroupNodes.entrySet();
                Map<String, Set<HostWeight>> workerHostWeights = new HashMap<>();
                for(Map.Entry<String, Set<String>> entry : entries){
                    String workerGroup = entry.getKey();
                    Set<String> nodes = entry.getValue();
                    String workerGroupPath = registryCenter.getWorkerGroupPath(workerGroup);
                    Set<HostWeight> hostWeights = new HashSet<>(nodes.size());
                    for(String node : nodes){
                        String heartbeat = registryCenter.getZookeeperCachedOperator().get(workerGroupPath + "/" + node);
                        if(StringUtils.isNotEmpty(heartbeat) && heartbeat.contains(COMMA) && heartbeat.split(COMMA).length == 5){
                            String[] parts = heartbeat.split(COMMA);
                            double cpu = Double.parseDouble(parts[0]);
                            double memory = Double.parseDouble(parts[1]);
                            double loadAverage = Double.parseDouble(parts[2]);
                            HostWeight hostWeight = new HostWeight(Host.of(node), cpu, memory, loadAverage);
                            hostWeights.add(hostWeight);
                        }
                    }
                    workerHostWeights.put(workerGroup, hostWeights);
                }
                syncWorkerHostWeight(workerHostWeights);
            } catch (Throwable ex){
                logger.error("RefreshResourceTask error", ex);
            }
        }
    }

}