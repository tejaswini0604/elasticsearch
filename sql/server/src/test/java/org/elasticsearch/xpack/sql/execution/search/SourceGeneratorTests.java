/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.expression.RootFieldAttribute;
import org.elasticsearch.xpack.sql.expression.function.Score;
import org.elasticsearch.xpack.sql.querydsl.agg.Aggs;
import org.elasticsearch.xpack.sql.querydsl.agg.AvgAgg;
import org.elasticsearch.xpack.sql.querydsl.agg.GroupByColumnAgg;
import org.elasticsearch.xpack.sql.querydsl.container.AttributeSort;
import org.elasticsearch.xpack.sql.querydsl.container.QueryContainer;
import org.elasticsearch.xpack.sql.querydsl.container.ScoreSort;
import org.elasticsearch.xpack.sql.querydsl.container.Sort.Direction;
import org.elasticsearch.xpack.sql.querydsl.query.MatchQuery;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.type.DataTypes;

import static java.util.Collections.singletonList;
import static org.elasticsearch.search.sort.SortBuilders.fieldSort;
import static org.elasticsearch.search.sort.SortBuilders.scoreSort;

import static java.util.Collections.emptyList;

public class SourceGeneratorTests extends ESTestCase {

    public void testNoQueryNoFilter() {
        QueryContainer container = new QueryContainer();
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertNull(sourceBuilder.query());
    }

    public void testQueryNoFilter() {
        QueryContainer container = new QueryContainer().with(new MatchQuery(Location.EMPTY, "foo", "bar"));
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertEquals(new MatchQueryBuilder("foo", "bar").operator(Operator.AND), sourceBuilder.query());
    }

    public void testNoQueryFilter() {
        QueryContainer container = new QueryContainer();
        QueryBuilder filter = new MatchQueryBuilder("bar", "baz");
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, filter, randomIntBetween(1, 10));
        assertEquals(new ConstantScoreQueryBuilder(new MatchQueryBuilder("bar", "baz")), sourceBuilder.query());
    }

    public void testQueryFilter() {
        QueryContainer container = new QueryContainer().with(new MatchQuery(Location.EMPTY, "foo", "bar"));
        QueryBuilder filter = new MatchQueryBuilder("bar", "baz");
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, filter, randomIntBetween(1, 10));
        assertEquals(new BoolQueryBuilder().must(new MatchQueryBuilder("foo", "bar").operator(Operator.AND))
                .filter(new MatchQueryBuilder("bar", "baz")), sourceBuilder.query());
    }

    public void testLimit() {
        Aggs aggs = new Aggs(emptyList(), emptyList(), singletonList(new GroupByColumnAgg("1", "", "field")));
        QueryContainer container = new QueryContainer().withLimit(10).with(aggs);
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        Builder aggBuilder = sourceBuilder.aggregations();
        assertEquals(1, aggBuilder.count());
        TermsAggregationBuilder termsBuilder = (TermsAggregationBuilder) aggBuilder.getAggregatorFactories().get(0);
        assertEquals(10, termsBuilder.size());
    }

    public void testSortNoneSpecified() {
        QueryContainer container = new QueryContainer();
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertEquals(singletonList(fieldSort("_doc")), sourceBuilder.sorts());
    }

    public void testSelectScoreForcesTrackingScore() {
        QueryContainer container = new QueryContainer()
            .addColumn(new Score(new Location(1, 1)).toAttribute());
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertTrue(sourceBuilder.trackScores());
    }

    public void testSortScoreSpecified() {
        QueryContainer container = new QueryContainer()
            .sort(new ScoreSort(Direction.DESC));
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertEquals(singletonList(scoreSort()), sourceBuilder.sorts());
    }

    public void testSortFieldSpecified() {
        QueryContainer container = new QueryContainer()
            .sort(new AttributeSort(new RootFieldAttribute(new Location(1, 1), "test", DataTypes.KEYWORD), Direction.ASC));
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertEquals(singletonList(fieldSort("test").order(SortOrder.ASC)), sourceBuilder.sorts());

        container = new QueryContainer()
            .sort(new AttributeSort(new RootFieldAttribute(new Location(1, 1), "test", DataTypes.KEYWORD), Direction.DESC));
        sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertEquals(singletonList(fieldSort("test").order(SortOrder.DESC)), sourceBuilder.sorts());
    }

    public void testNoSort() {
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(new QueryContainer(), null, randomIntBetween(1, 10));
        assertEquals(singletonList(fieldSort("_doc").order(SortOrder.ASC)), sourceBuilder.sorts());
    }

    public void testNoSortIfAgg() {
        QueryContainer container = new QueryContainer()
            .addGroups(singletonList(new GroupByColumnAgg("group_id", "", "group_column")))
            .addAgg("group_id", new AvgAgg("agg_id", "", "avg_column"));
        SearchSourceBuilder sourceBuilder = SourceGenerator.sourceBuilder(container, null, randomIntBetween(1, 10));
        assertNull(sourceBuilder.sorts());
    }
}
