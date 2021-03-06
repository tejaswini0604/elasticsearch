/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.autoscaling.decision;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xpack.autoscaling.Autoscaling;
import org.elasticsearch.xpack.autoscaling.AutoscalingMetadata;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class AutoscalingDecisionService {
    private Map<String, AutoscalingDeciderService<? extends AutoscalingDeciderConfiguration>> deciderByName;

    public AutoscalingDecisionService(Set<AutoscalingDeciderService<? extends AutoscalingDeciderConfiguration>> deciders) {
        assert deciders.size() >= 1; // always have fixed
        this.deciderByName = deciders.stream().collect(Collectors.toMap(AutoscalingDeciderService::name, Function.identity()));
    }

    public static class Holder {
        private final Autoscaling autoscaling;
        private final SetOnce<AutoscalingDecisionService> servicesSetOnce = new SetOnce<>();

        public Holder(Autoscaling autoscaling) {
            this.autoscaling = autoscaling;
        }

        public AutoscalingDecisionService get() {
            // defer constructing services until transport action creation time.
            AutoscalingDecisionService autoscalingDecisionService = servicesSetOnce.get();
            if (autoscalingDecisionService == null) {
                autoscalingDecisionService = new AutoscalingDecisionService(autoscaling.createDeciderServices());
                servicesSetOnce.set(autoscalingDecisionService);
            }

            return autoscalingDecisionService;
        }
    }

    public SortedMap<String, AutoscalingDecisions> decide(ClusterState state, ClusterInfo clusterInfo) {

        AutoscalingMetadata autoscalingMetadata = state.metadata().custom(AutoscalingMetadata.NAME);
        if (autoscalingMetadata != null) {
            return new TreeMap<>(
                autoscalingMetadata.policies()
                    .entrySet()
                    .stream()
                    .map(e -> Tuple.tuple(e.getKey(), getDecision(e.getValue().policy(), state, clusterInfo)))
                    .collect(Collectors.toMap(Tuple::v1, Tuple::v2))
            );
        } else {
            return new TreeMap<>();
        }
    }

    private AutoscalingDecisions getDecision(AutoscalingPolicy policy, ClusterState state, ClusterInfo clusterInfo) {
        DecisionAutoscalingDeciderContext context = new DecisionAutoscalingDeciderContext(policy.name(), state, clusterInfo);
        SortedMap<String, AutoscalingDecision> decisions = policy.deciders()
            .entrySet()
            .stream()
            .map(entry -> Tuple.tuple(entry.getKey(), getDecision(entry.getValue(), context)))
            .collect(Collectors.toMap(Tuple::v1, Tuple::v2, (a, b) -> { throw new UnsupportedOperationException(); }, TreeMap::new));
        return new AutoscalingDecisions(context.tier, context.currentCapacity, decisions);
    }

    private <T extends AutoscalingDeciderConfiguration> AutoscalingDecision getDecision(T decider, AutoscalingDeciderContext context) {
        assert deciderByName.containsKey(decider.name());
        @SuppressWarnings("unchecked")
        AutoscalingDeciderService<T> service = (AutoscalingDeciderService<T>) deciderByName.get(decider.name());
        return service.scale(decider, context);
    }

    static class DecisionAutoscalingDeciderContext implements AutoscalingDeciderContext {

        private final String tier;
        private final ClusterState state;
        private final ClusterInfo clusterInfo;
        private final AutoscalingCapacity currentCapacity;
        private final boolean currentCapacityAccurate;

        DecisionAutoscalingDeciderContext(String tier, ClusterState state, ClusterInfo clusterInfo) {
            this.tier = tier;
            Objects.requireNonNull(state);
            Objects.requireNonNull(clusterInfo);
            this.state = state;
            this.clusterInfo = clusterInfo;
            this.currentCapacity = calculateCurrentCapacity();
            this.currentCapacityAccurate = calculateCurrentCapacityAccurate();
        }

        @Override
        public ClusterState state() {
            return state;
        }

        @Override
        public AutoscalingCapacity currentCapacity() {
            if (currentCapacityAccurate) {
                return currentCapacity;
            } else {
                return null;
            }
        }

        private boolean calculateCurrentCapacityAccurate() {
            return StreamSupport.stream(state.nodes().spliterator(), false)
                .filter(this::informalTierFilter)
                .allMatch(this::nodeHasAccurateCapacity);
        }

        private boolean nodeHasAccurateCapacity(DiscoveryNode node) {
            return totalStorage(clusterInfo.getNodeLeastAvailableDiskUsages(), node) >= 0
                && totalStorage(clusterInfo.getNodeMostAvailableDiskUsages(), node) >= 0;
        }

        private AutoscalingCapacity calculateCurrentCapacity() {
            return StreamSupport.stream(state.nodes().spliterator(), false)
                .filter(this::informalTierFilter)
                .map(this::resourcesFor)
                .map(c -> new AutoscalingCapacity(c, c))
                .reduce(
                    (c1, c2) -> new AutoscalingCapacity(
                        AutoscalingCapacity.AutoscalingResources.sum(c1.tier(), c2.tier()),
                        AutoscalingCapacity.AutoscalingResources.max(c1.node(), c2.node())
                    )
                )
                .orElse(AutoscalingCapacity.ZERO);
        }

        private AutoscalingCapacity.AutoscalingResources resourcesFor(DiscoveryNode node) {
            long storage = Math.max(
                totalStorage(clusterInfo.getNodeLeastAvailableDiskUsages(), node),
                totalStorage(clusterInfo.getNodeMostAvailableDiskUsages(), node)
            );

            // todo: also capture memory across cluster.
            return new AutoscalingCapacity.AutoscalingResources(
                storage == -1 ? ByteSizeValue.ZERO : new ByteSizeValue(storage),
                ByteSizeValue.ZERO
            );
        }

        private long totalStorage(ImmutableOpenMap<String, DiskUsage> diskUsages, DiscoveryNode node) {
            DiskUsage diskUsage = diskUsages.get(node.getId());
            return diskUsage != null ? diskUsage.getTotalBytes() : -1;
        }

        private boolean informalTierFilter(DiscoveryNode discoveryNode) {
            return discoveryNode.getRoles().stream().map(DiscoveryNodeRole::roleName).anyMatch(tier::equals)
                || tier.equals(discoveryNode.getAttributes().get("data"));
        }
    }
}
