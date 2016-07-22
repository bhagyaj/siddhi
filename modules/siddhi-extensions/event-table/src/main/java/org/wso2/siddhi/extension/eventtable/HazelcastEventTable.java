/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.extension.eventtable;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.event.stream.converter.ZeroStreamEventConverter;
import org.wso2.siddhi.core.exception.OperationNotSupportedException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.table.EventTable;
import org.wso2.siddhi.core.table.holder.EventHolder;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.collection.OverwritingStreamEventExtractor;
import org.wso2.siddhi.core.util.collection.UpdateAttributeMapper;
import org.wso2.siddhi.core.util.collection.operator.Finder;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaStateHolder;
import org.wso2.siddhi.core.util.collection.operator.Operator;
import org.wso2.siddhi.extension.eventtable.hazelcast.HazelcastCollectionEventHolder;
import org.wso2.siddhi.extension.eventtable.hazelcast.HazelcastEventTableConstants;
import org.wso2.siddhi.extension.eventtable.hazelcast.HazelcastOperatorParser;
import org.wso2.siddhi.extension.eventtable.hazelcast.HazelcastPrimaryKeyEventHolder;
import org.wso2.siddhi.extension.eventtable.hazelcast.internal.ds.HazelcastEventTableServiceValueHolder;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.expression.Expression;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.util.List;
import java.util.Map;

/**
 * Hazelcast event table implementation of SiddhiQL.
 */
public class HazelcastEventTable implements EventTable {
    private static final Logger logger = Logger.getLogger(HazelcastEventTable.class);
    private final ZeroStreamEventConverter eventConverter = new ZeroStreamEventConverter();
    private TableDefinition tableDefinition;
    private ExecutionPlanContext executionPlanContext;
    private StreamEventCloner tableStreamEventCloner;
    private String elementId;
    private EventHolder eventHolder = null;

    /**
     * Event Table initialization method, it checks the annotation and do necessary pre configuration tasks.
     *
     * @param tableDefinition        Definition of event table.
     * @param tableMetaStreamEvent
     * @param tableStreamEventPool
     * @param tableStreamEventCloner
     * @param executionPlanContext   ExecutionPlan related meta information.
     */
    @Override
    public void init(TableDefinition tableDefinition, MetaStreamEvent tableMetaStreamEvent, StreamEventPool tableStreamEventPool, StreamEventCloner tableStreamEventCloner, ExecutionPlanContext executionPlanContext) {
        this.tableDefinition = tableDefinition;
        this.tableStreamEventCloner = tableStreamEventCloner;
        this.executionPlanContext = executionPlanContext;

        Annotation fromAnnotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_FROM,
                tableDefinition.getAnnotations());
        String clusterName = fromAnnotation.getElement(HazelcastEventTableConstants.ANNOTATION_ELEMENT_CLUSTER_NAME);
        String clusterPassword = fromAnnotation.getElement(
                HazelcastEventTableConstants.ANNOTATION_ELEMENT_CLUSTER_PASSWORD);
        String clusterAddresses = fromAnnotation.getElement(
                HazelcastEventTableConstants.ANNOTATION_ELEMENT_CLUSTER_ADDRESSES);
        String instanceName = fromAnnotation.getElement(HazelcastEventTableConstants.ANNOTATION_ELEMENT_INSTANCE_NAME);
        HazelcastInstance hcInstance = getHazelcastInstance(clusterName, clusterPassword, clusterAddresses, instanceName);

        MetaStreamEvent metaStreamEvent = new MetaStreamEvent();
        metaStreamEvent.addInputDefinition(tableDefinition);
        for (Attribute attribute : tableDefinition.getAttributeList()) {
            metaStreamEvent.addOutputData(attribute);
        }

        Annotation annotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_INDEX_BY,
                tableDefinition.getAnnotations());
        if (annotation != null) {
            if (annotation.getElements().size() != 1) {
                throw new OperationNotSupportedException(SiddhiConstants.ANNOTATION_INDEX_BY + " annotation of table " +
                        tableDefinition.getId() + " contains " + annotation.getElements().size() +
                        " elements, Siddhi Hazelcast event table only supports indexing based on a single attribute");
            }
            String indexAttribute = annotation.getElements().get(0).getValue();
            int indexPosition = tableDefinition.getAttributePosition(indexAttribute);
            eventHolder = new HazelcastPrimaryKeyEventHolder(hcInstance.getMap(HazelcastEventTableConstants.HAZELCAST_MAP_INSTANCE_PREFIX +
                    executionPlanContext.getName() + '_' + tableDefinition.getId()), tableStreamEventPool, eventConverter, indexPosition, indexAttribute);
        } else {
            eventHolder = new HazelcastCollectionEventHolder(hcInstance.getList(HazelcastEventTableConstants.HAZELCAST_LIST_INSTANCE_PREFIX +
                    executionPlanContext.getName() + '_' + tableDefinition.getId()), tableStreamEventPool, eventConverter);
        }
//        streamEventPool = new StreamEventPool(metaStreamEvent, HazelcastEventTableConstants.STREAM_EVENT_POOL_SIZE);
//        tableStreamEventCloner = new StreamEventCloner(metaStreamEvent, streamEventPool);
        if (elementId == null) {
            elementId = executionPlanContext.getElementIdGenerator().createNewId();
        }
    }

    /**
     * Called to get the most suitable Hazelcast Instance for the given set of parameters.
     *
     * @param clusterName      Hazelcast cluster name.
     * @param clusterPassword  Hazelcast cluster password.
     * @param clusterAddresses Hazelcast node addresses (ip:port).
     * @param instanceName     Hazelcast instance name.
     * @return Hazelcast Instance
     */
    protected HazelcastInstance getHazelcastInstance(String clusterName, String clusterPassword, String clusterAddresses,
                                                     String instanceName) {
        HazelcastInstance hazelcastInstance;
        if (instanceName == null) {
            instanceName = HazelcastEventTableConstants.HAZELCAST_INSTANCE_PREFIX + this.executionPlanContext.getName();
        }

        if (clusterAddresses == null) {
            if (HazelcastEventTableServiceValueHolder.getHazelcastInstance() != null) {
                // Take instance from osgi.
                hazelcastInstance = HazelcastEventTableServiceValueHolder.getHazelcastInstance();
                logger.info("Shared hazelcast server instance retrieved : " + hazelcastInstance.getName());
            } else {
                // Create a new server with default cluster name.
                Config config = new Config();
                config.setInstanceName(instanceName);
                config.setProperty("hazelcast.logging.type", "log4j");
                if (clusterName != null && !clusterName.isEmpty()) {
                    config.getGroupConfig().setName(clusterName);
                }
                if (clusterPassword != null && !clusterPassword.isEmpty()) {
                    config.getGroupConfig().setPassword(clusterPassword);
                }
                hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance(config);
                logger.info("Hazelcast server instance started: " + instanceName);
            }
        } else {
            // Client mode.
            ClientConfig clientConfig = new ClientConfig();
            clientConfig.setProperty("hazelcast.logging.type", "log4j");
            if (clusterName != null && !clusterName.isEmpty()) {
                clientConfig.getGroupConfig().setName(clusterName);
            }
            if (clusterPassword != null && !clusterPassword.isEmpty()) {
                clientConfig.getGroupConfig().setPassword(clusterPassword);
            }
            clientConfig.setNetworkConfig(clientConfig.getNetworkConfig().addAddress(clusterAddresses.split(",")));
            hazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig);
        }
        return hazelcastInstance;
    }

    @Override
    public TableDefinition getTableDefinition() {
        return tableDefinition;
    }

    @Override
    public synchronized void add(ComplexEventChunk<StreamEvent> addingEventChunk) {
        eventHolder.add(addingEventChunk);
    }

    @Override
    public synchronized void delete(ComplexEventChunk<StateEvent> deletingEventChunk, Operator operator) {
        operator.delete(deletingEventChunk, eventHolder);
    }

    @Override
    public synchronized void update(ComplexEventChunk<StateEvent> updatingEventChunk, Operator operator,
                                    UpdateAttributeMapper[] updateAttributeMappers) {
        operator.update(updatingEventChunk, eventHolder, updateAttributeMappers);

    }

    @Override
    public synchronized void overwriteOrAdd(ComplexEventChunk<StateEvent> overwritingOrAddingEventChunk, Operator operator,
                                            UpdateAttributeMapper[] updateAttributeMappers,
                                            OverwritingStreamEventExtractor overwritingStreamEventExtractor) {
        ComplexEventChunk<StreamEvent> failedEvents = operator.overwriteOrAdd(overwritingOrAddingEventChunk,
                eventHolder, updateAttributeMappers, overwritingStreamEventExtractor);
        eventHolder.add(failedEvents);

    }

    @Override
    public synchronized boolean contains(StateEvent matchingEvent, Finder finder) {
        return finder.contains(matchingEvent, eventHolder);
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, Finder finder) {
        return finder.find(matchingEvent, eventHolder, tableStreamEventCloner);
    }

    @Override
    public Finder constructFinder(Expression expression, MatchingMetaStateHolder matchingMetaStateHolder,
                                  ExecutionPlanContext executionPlanContext,
                                  List<VariableExpressionExecutor> variableExpressionExecutors,
                                  Map<String, EventTable> eventTableMap) {
        return HazelcastOperatorParser.constructOperator(eventHolder, expression, matchingMetaStateHolder,
                executionPlanContext, variableExpressionExecutors, eventTableMap,tableDefinition.getId());
    }


    @Override
    public Operator constructOperator(Expression expression, MatchingMetaStateHolder matchingMetaStateHolder,
                                      ExecutionPlanContext executionPlanContext,
                                      List<VariableExpressionExecutor> variableExpressionExecutors,
                                      Map<String, EventTable> eventTableMap) {
        return HazelcastOperatorParser.constructOperator(eventHolder, expression, matchingMetaStateHolder,
                executionPlanContext, variableExpressionExecutors, eventTableMap,tableDefinition.getId());
    }
}
