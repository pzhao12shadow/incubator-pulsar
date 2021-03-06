/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.common.util.Codec.decode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.Encoded;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.nonpersistent.NonPersistentTopic;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.util.FutureUtil;
import org.apache.pulsar.common.naming.DestinationDomain;
import org.apache.pulsar.common.naming.DestinationName;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.NonPersistentTopicStats;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 */
@Path("/non-persistent")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "/non-persistent", description = "Non-Persistent topic admin apis", tags = "non-persistent topic")
public class NonPersistentTopics extends PersistentTopics {
    private static final Logger log = LoggerFactory.getLogger(NonPersistentTopics.class);
    
    @GET
    @Path("/{property}/{cluster}/{namespace}/{destination}/partitions")
    @ApiOperation(value = "Get partitioned topic metadata.")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public PartitionedTopicMetadata getPartitionedMetadata(@PathParam("property") String property,
            @PathParam("cluster") String cluster, @PathParam("namespace") String namespace,
            @PathParam("destination") @Encoded String destination,
            @QueryParam("authoritative") @DefaultValue("false") boolean authoritative) {
        destination = decode(destination);
        return getPartitionedTopicMetadata(property, cluster, namespace, destination, authoritative);
    }

    @GET
    @Path("{property}/{cluster}/{namespace}/{destination}/stats")
    @ApiOperation(value = "Get the stats for the topic.")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Topic does not exist") })
    public NonPersistentTopicStats getStats(@PathParam("property") String property, @PathParam("cluster") String cluster,
            @PathParam("namespace") String namespace, @PathParam("destination") @Encoded String destination,
            @QueryParam("authoritative") @DefaultValue("false") boolean authoritative) {
        destination = decode(destination);
        DestinationName dn = DestinationName.get(domain(), property, cluster, namespace, destination);
        validateAdminOperationOnDestination(dn, authoritative);
        Topic topic = getTopicReference(dn);
        return ((NonPersistentTopic)topic).getStats();
    }

    @GET
    @Path("{property}/{cluster}/{namespace}/{destination}/internalStats")
    @ApiOperation(value = "Get the internal stats for the topic.")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Topic does not exist") })
    public PersistentTopicInternalStats getInternalStats(@PathParam("property") String property,
            @PathParam("cluster") String cluster, @PathParam("namespace") String namespace,
            @PathParam("destination") @Encoded String destination,
            @QueryParam("authoritative") @DefaultValue("false") boolean authoritative) {
        destination = decode(destination);
        DestinationName dn = DestinationName.get(domain(), property, cluster, namespace, destination);
        validateAdminOperationOnDestination(dn, authoritative);
        Topic topic = getTopicReference(dn);
        return topic.getInternalStats();
    }

    @PUT
    @Path("/{property}/{cluster}/{namespace}/{destination}/partitions")
    @ApiOperation(value = "Create a partitioned topic.", notes = "It needs to be called before creating a producer on a partitioned topic.")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 409, message = "Partitioned topic already exist") })
    public void createPartitionedTopic(@PathParam("property") String property, @PathParam("cluster") String cluster,
            @PathParam("namespace") String namespace, @PathParam("destination") @Encoded String destination, int numPartitions,
            @QueryParam("authoritative") @DefaultValue("false") boolean authoritative) {
        destination = decode(destination);
        DestinationName dn = DestinationName.get(domain(), property, cluster, namespace, destination);
        validateAdminAccessOnProperty(dn.getProperty());
        if (numPartitions <= 1) {
            throw new RestException(Status.NOT_ACCEPTABLE, "Number of partitions should be more than 1");
        }
        try {
            String path = path(PARTITIONED_TOPIC_PATH_ZNODE, property, cluster, namespace, domain(),
                    dn.getEncodedLocalName());
            byte[] data = jsonMapper().writeValueAsBytes(new PartitionedTopicMetadata(numPartitions));
            zkCreateOptimistic(path, data);
            // we wait for the data to be synced in all quorums and the observers
            Thread.sleep(PARTITIONED_TOPIC_WAIT_SYNC_TIME_MS);
            log.info("[{}] Successfully created partitioned topic {}", clientAppId(), dn);
        } catch (KeeperException.NodeExistsException e) {
            log.warn("[{}] Failed to create already existing partitioned topic {}", clientAppId(), dn);
            throw new RestException(Status.CONFLICT, "Partitioned topic already exist");
        } catch (Exception e) {
            log.error("[{}] Failed to create partitioned topic {}", clientAppId(), dn, e);
            throw new RestException(e);
        }
    }

    @PUT
    @Path("/{property}/{cluster}/{namespace}/{destination}/unload")
    @ApiOperation(value = "Unload a topic")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Topic does not exist") })
    public void unloadTopic(@PathParam("property") String property, @PathParam("cluster") String cluster,
            @PathParam("namespace") String namespace, @PathParam("destination") @Encoded String destination,
            @QueryParam("authoritative") @DefaultValue("false") boolean authoritative) {
        log.info("[{}] Unloading topic {}/{}/{}/{}", clientAppId(), property, cluster, namespace, destination);
        destination = decode(destination);
        DestinationName dn = DestinationName.get(domain(), property, cluster, namespace, destination);
        if (cluster.equals(Namespaces.GLOBAL_CLUSTER)) {
            validateGlobalNamespaceOwnership(NamespaceName.get(property, cluster, namespace));
        }
        unloadTopic(dn, authoritative);
    }

    @GET
    @Path("/{property}/{cluster}/{namespace}")
    @ApiOperation(value = "Get the list of non-persistent topics under a namespace.", response = String.class, responseContainer = "List")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Namespace doesn't exist") })
    public List<String> getList(@PathParam("property") String property, @PathParam("cluster") String cluster,
            @PathParam("namespace") String namespace) {
        log.info("[{}] list of topics on namespace {}/{}/{}/{}", clientAppId(), property, cluster, namespace);
        validateAdminAccessOnProperty(property);
        Policies policies = getNamespacePolicies(property, cluster, namespace);
        NamespaceName nsName = NamespaceName.get(property, cluster, namespace);

        if (!cluster.equals(Namespaces.GLOBAL_CLUSTER)) {
            validateClusterOwnership(cluster);
            validateClusterForProperty(property, cluster);
        } else {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(nsName);
        }
        final List<CompletableFuture<List<String>>> futures = Lists.newArrayList();
        final List<String> boundaries = policies.bundles.getBoundaries();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            final String bundle = String.format("%s_%s", boundaries.get(i), boundaries.get(i + 1));
            try {
                futures.add(pulsar().getAdminClient().nonPersistentTopics().getListInBundleAsync(nsName.toString(),
                        bundle));
            } catch (PulsarServerException e) {
                log.error(String.format("[%s] Failed to get list of topics under namespace %s/%s/%s/%s", clientAppId(),
                        property, cluster, namespace, bundle), e);
                throw new RestException(e);
            }
        }
        final List<String> topics = Lists.newArrayList();
        try {
            FutureUtil.waitForAll(futures).get();
            futures.forEach(topicListFuture -> {
                try {
                    if (topicListFuture.isDone() && topicListFuture.get() != null) {
                        topics.addAll(topicListFuture.get());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error(String.format("[%s] Failed to get list of topics under namespace %s/%s/%s", clientAppId(),
                            property, cluster, namespace), e);
                }
            });
        } catch (InterruptedException | ExecutionException e) {
            log.error(String.format("[%s] Failed to get list of topics under namespace %s/%s/%s", clientAppId(),
                    property, cluster, namespace), e);
            throw new RestException(e instanceof ExecutionException ? e.getCause() : e);
        }
        return topics;
    }
    
    @GET
    @Path("/{property}/{cluster}/{namespace}/{bundle}")
    @ApiOperation(value = "Get the list of non-persistent topics under a namespace bundle.", response = String.class, responseContainer = "List")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Namespace doesn't exist") })
    public List<String> getListFromBundle(@PathParam("property") String property, @PathParam("cluster") String cluster,
            @PathParam("namespace") String namespace, @PathParam("bundle") String bundleRange) {
        log.info("[{}] list of topics on namespace bundle {}/{}/{}/{}", clientAppId(), property, cluster, namespace,
                bundleRange);
        validateAdminAccessOnProperty(property);
        Policies policies = getNamespacePolicies(property, cluster, namespace);
        if (!cluster.equals(Namespaces.GLOBAL_CLUSTER)) {
            validateClusterOwnership(cluster);
            validateClusterForProperty(property, cluster);
        } else {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(NamespaceName.get(property, cluster, namespace));
        }
        NamespaceName fqnn = NamespaceName.get(property, cluster, namespace);
        if (!isBundleOwnedByAnyBroker(fqnn, policies.bundles, bundleRange)) {
            log.info("[{}] Namespace bundle is not owned by any broker {}/{}/{}/{}", clientAppId(), property, cluster,
                    namespace, bundleRange);
            return null;
        }
        NamespaceBundle nsBundle = validateNamespaceBundleOwnership(fqnn, policies.bundles, bundleRange, true, true);
        try {
            final List<String> topicList = Lists.newArrayList();
            pulsar().getBrokerService().getTopics().forEach((name, topicFuture) -> {
                DestinationName topicName = DestinationName.get(name);
                if (nsBundle.includes(topicName)) {
                    topicList.add(name);
                }
            });
            return topicList;
        } catch (Exception e) {
            log.error("[{}] Failed to unload namespace bundle {}/{}", clientAppId(), fqnn.toString(), bundleRange, e);
            throw new RestException(e);
        }
    }

    protected void validateAdminOperationOnDestination(DestinationName fqdn, boolean authoritative) {
        validateAdminAccessOnProperty(fqdn.getProperty());
        validateDestinationOwnership(fqdn, authoritative);
    }

    private Topic getTopicReference(DestinationName dn) {
        try {
            Topic topic = pulsar().getBrokerService().getTopicReference(dn.toString());
            checkNotNull(topic);
            return topic;
        } catch (Exception e) {
            throw new RestException(Status.NOT_FOUND, "Topic not found");
        }
    }
}
