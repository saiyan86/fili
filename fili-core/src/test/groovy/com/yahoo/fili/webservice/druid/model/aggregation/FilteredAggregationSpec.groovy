// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.fili.webservice.druid.model.aggregation

import static com.yahoo.fili.webservice.data.time.DefaultTimeGrain.DAY
import static org.joda.time.DateTimeZone.UTC

import com.yahoo.fili.webservice.data.config.metric.MetricInstance
import com.yahoo.fili.webservice.data.config.metric.makers.ThetaSketchMaker
import com.yahoo.fili.webservice.data.config.names.ApiMetricName
import com.yahoo.fili.webservice.data.dimension.FiliDimensionField
import com.yahoo.fili.webservice.data.dimension.Dimension
import com.yahoo.fili.webservice.data.dimension.DimensionColumn
import com.yahoo.fili.webservice.data.dimension.DimensionDictionary
import com.yahoo.fili.webservice.data.dimension.MapStoreManager
import com.yahoo.fili.webservice.data.dimension.impl.KeyValueStoreDimension
import com.yahoo.fili.webservice.data.dimension.impl.ScanSearchProviderManager
import com.yahoo.fili.webservice.data.filterbuilders.DefaultDruidFilterBuilder
import com.yahoo.fili.webservice.data.filterbuilders.DruidFilterBuilder
import com.yahoo.fili.webservice.data.metric.LogicalMetric
import com.yahoo.fili.webservice.data.metric.MetricDictionary
import com.yahoo.fili.webservice.druid.model.filter.Filter
import com.yahoo.fili.webservice.druid.util.FieldConverterSupplier
import com.yahoo.fili.webservice.metadata.DataSourceMetadataService
import com.yahoo.fili.webservice.table.Column
import com.yahoo.fili.webservice.table.StrictPhysicalTable
import com.yahoo.fili.webservice.table.PhysicalTable
import com.yahoo.fili.webservice.web.ApiFilter
import com.yahoo.fili.webservice.web.FilteredThetaSketchMetricsHelper
import com.yahoo.fili.webservice.web.MetricsFilterSetBuilder

import spock.lang.Specification

class FilteredAggregationSpec extends Specification{

    FilteredAggregation filteredAgg
    FilteredAggregation filteredAgg2
    Filter filter1
    Filter filter2
    Aggregation metricAgg
    Aggregation genderDependentMetricAgg
    KeyValueStoreDimension ageDimension
    KeyValueStoreDimension genderDimension
    static MetricsFilterSetBuilder oldBuilder = FieldConverterSupplier.metricsFilterSetBuilder

    def setupSpec() {
        FieldConverterSupplier.metricsFilterSetBuilder = new FilteredThetaSketchMetricsHelper()
    }

    def setup() {
        MetricDictionary metricDictionary = new MetricDictionary()

        def filtered_metric_name = "FOO_NO_BAR"
        Set<ApiMetricName> metricNames = (["FOO", filtered_metric_name].collect { ApiMetricName.of(it)}) as Set

        ageDimension = buildSimpleDimension("age")
        genderDimension = buildSimpleDimension("gender")

        DimensionDictionary dimensionDictionary = new DimensionDictionary([ageDimension] as Set)

        ageDimension.addDimensionRow(FiliDimensionField.makeDimensionRow(ageDimension, "114"))
        ageDimension.addDimensionRow(FiliDimensionField.makeDimensionRow(ageDimension, "125"))

        Set<Column> columns = [new DimensionColumn(ageDimension)] as Set

        PhysicalTable physicalTable = new StrictPhysicalTable(
                "NETWORK",
                DAY.buildZonedTimeGrain(UTC),
                columns,
                [:],
                Mock(DataSourceMetadataService)
        )

        ThetaSketchMaker sketchCountMaker = new ThetaSketchMaker(new MetricDictionary(), 16384)
        MetricInstance fooNoBarSketchPm = new MetricInstance(filtered_metric_name,sketchCountMaker,"FOO_NO_BAR_SKETCH")
        LogicalMetric fooNoBarSketch = fooNoBarSketchPm.make()
        metricDictionary.put(filtered_metric_name, fooNoBarSketch)

        metricAgg = fooNoBarSketch.getTemplateDruidQuery().getAggregations().first()
        genderDependentMetricAgg = Mock(Aggregation)
        genderDependentMetricAgg.getDependentDimensions() >> ([genderDimension] as Set)
        genderDependentMetricAgg.withName(_) >> genderDependentMetricAgg
        genderDependentMetricAgg.withFieldName(_) >> genderDependentMetricAgg

        LogicalMetric logicalMetric = new LogicalMetric(null, null, filtered_metric_name)

        Set<ApiFilter> filterSet = [new ApiFilter("age|id-in[114,125]", dimensionDictionary)] as Set

        DruidFilterBuilder filterBuilder = new DefaultDruidFilterBuilder()
        filter1  = filterBuilder.buildFilters([(ageDimension): filterSet])

        filter2 = filterBuilder.buildFilters(
                [(ageDimension): [new ApiFilter("age|id-in[114]", dimensionDictionary)] as Set]
        )

        filteredAgg = new FilteredAggregation("FOO_NO_BAR-114_127", metricAgg, filter1)
        filteredAgg2 = new FilteredAggregation("FOO_NO_BAR-114_127", genderDependentMetricAgg, filter1)
    }

    def cleanupSpec() {
        FieldConverterSupplier.metricsFilterSetBuilder = oldBuilder
    }

    def "test the filtered aggregator constructor" (){
        expect:
        filteredAgg.getFieldName() == "FOO_NO_BAR_SKETCH"
        filteredAgg.getName() == "FOO_NO_BAR-114_127"
        filteredAgg.getFilter() == filter1
        filteredAgg.getAggregation() == metricAgg.withName("FOO_NO_BAR-114_127")
    }

    def "test FilteredAggregation withFieldName method" (){
        FilteredAggregation newFilteredAggregation = filteredAgg.withFieldName("NEW_FIELD_NAME");

        expect:
        newFilteredAggregation.getFieldName() == "NEW_FIELD_NAME"
        newFilteredAggregation.getName() == filteredAgg.getName()
        newFilteredAggregation.getFilter() == filteredAgg.getFilter()
        newFilteredAggregation.getAggregation() == metricAgg.withName(filteredAgg.getName()).withFieldName("NEW_FIELD_NAME")
    }

    def "test FilteredAggregation withName method" (){
        FilteredAggregation newFilteredAggregation = filteredAgg.withName("FOO_NO_BAR-US_IN");

        expect:
        newFilteredAggregation.getFieldName() == filteredAgg.getFieldName()
        newFilteredAggregation.getName() == "FOO_NO_BAR-US_IN"
        newFilteredAggregation.getFilter() == filteredAgg.getFilter()
        newFilteredAggregation.getAggregation() == metricAgg.withName("FOO_NO_BAR-US_IN").withFieldName(filteredAgg.getFieldName())
    }

    def "test FilteredAggregation withFilter method" (){
        FilteredAggregation newFilteredAggregation = filteredAgg.withFilter(filter2);

        expect:
        newFilteredAggregation.getFieldName() == filteredAgg.getFieldName()
        newFilteredAggregation.getName() == filteredAgg.getName()
        newFilteredAggregation.getFilter() == filter2
        newFilteredAggregation.getAggregation() == metricAgg.withName(filteredAgg.getName()).withFieldName(filteredAgg.getFieldName())
    }

    def "test FilteredAggregation withAggregation method" (){
        FilteredAggregation newFilteredAggregation = filteredAgg.withAggregation(metricAgg.withName("NEW_AGG").
                withFieldName("NEW_FIELD_NAME"));

        expect:
        newFilteredAggregation.getFieldName() == "NEW_FIELD_NAME"
        newFilteredAggregation.getName() == "NEW_AGG"
        newFilteredAggregation.getFilter() == filteredAgg.getFilter()
        newFilteredAggregation.getAggregation() == metricAgg.withName("NEW_AGG").
                withFieldName("NEW_FIELD_NAME")
    }

    def "Test dependant metric"() {
        expect:
        filteredAgg.dependentDimensions == [ageDimension] as Set
        filteredAgg2.dependentDimensions == [genderDimension, ageDimension] as Set
    }

    def "test serialization" (){
        expect:
        filteredAgg.getAggregation().getFieldName() == "FOO_NO_BAR_SKETCH"
        filteredAgg.getAggregation().getName() == "FOO_NO_BAR-114_127"
    }

    def Dimension buildSimpleDimension(String name) {
        return new KeyValueStoreDimension(
                name,
                null,
                [FiliDimensionField.ID] as LinkedHashSet,
                MapStoreManager.getInstance(name),
                ScanSearchProviderManager.getInstance(name)
        )

    }
}