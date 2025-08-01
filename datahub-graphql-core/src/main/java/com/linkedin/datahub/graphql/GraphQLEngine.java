package com.linkedin.datahub.graphql;

import static graphql.schema.idl.RuntimeWiring.*;

import com.linkedin.datahub.graphql.exception.DataHubDataFetcherExceptionHandler;
import com.linkedin.datahub.graphql.instrumentation.DataHubFieldComplexityCalculator;
import com.linkedin.metadata.config.GraphQLConfiguration;
import com.linkedin.metadata.system_telemetry.GraphQLTimingInstrumentation;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.tracing.TracingInstrumentation;
import graphql.execution.values.InputInterceptor;
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.dataloader.DataLoader;

/**
 * Simple wrapper around a {@link GraphQL} instance providing APIs for building an engine and
 * executing GQL queries.
 *
 * <p>This class provides a {@link Builder} builder for constructing {@link GraphQL} instances
 * provided one or more schemas, {@link DataLoader}s, & a configured {@link RuntimeWiring}.
 *
 * <p>In addition, it provides a simplified 'execute' API that accepts a 1) query string and 2) set
 * of variables.
 */
public class GraphQLEngine {

  private final GraphQL _graphQL;
  private final Map<String, Function<QueryContext, DataLoader<?, ?>>> _dataLoaderSuppliers;
  private final int graphQLQueryComplexityLimit;
  private final int graphQLQueryDepthLimit;

  private GraphQLEngine(
      @Nonnull final List<String> schemas,
      @Nonnull final RuntimeWiring runtimeWiring,
      @Nonnull final Map<String, Function<QueryContext, DataLoader<?, ?>>> dataLoaderSuppliers,
      @Nonnull GraphQLConfiguration graphQLConfiguration,
      MetricUtils metricUtils) {
    this.graphQLQueryComplexityLimit = graphQLConfiguration.getQuery().getComplexityLimit();
    this.graphQLQueryDepthLimit = graphQLConfiguration.getQuery().getDepthLimit();

    _dataLoaderSuppliers = dataLoaderSuppliers;

    /*
     * Parse schema
     */
    SchemaParser schemaParser = new SchemaParser();
    TypeDefinitionRegistry typeDefinitionRegistry = new TypeDefinitionRegistry();
    schemas.forEach(schema -> typeDefinitionRegistry.merge(schemaParser.parse(schema)));

    /*
     * Configure resolvers (data fetchers)
     */
    SchemaGenerator schemaGenerator = new SchemaGenerator();
    GraphQLSchema graphQLSchema =
        schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    /*
     * Instantiate engine
     */
    List<Instrumentation> instrumentations = new ArrayList<>();
    instrumentations.add(new TracingInstrumentation());
    instrumentations.add(new MaxQueryDepthInstrumentation(graphQLQueryDepthLimit));
    instrumentations.add(
        new MaxQueryComplexityInstrumentation(
            graphQLQueryComplexityLimit, new DataHubFieldComplexityCalculator()));

    if (metricUtils != null && graphQLConfiguration.getMetrics().isEnabled()) {
      metricUtils
          .getRegistry()
          .ifPresent(
              meterRegistry ->
                  instrumentations.add(
                      new GraphQLTimingInstrumentation(
                          meterRegistry, graphQLConfiguration.getMetrics())));
    }

    ChainedInstrumentation chainedInstrumentation = new ChainedInstrumentation(instrumentations);
    _graphQL =
        new GraphQL.Builder(graphQLSchema)
            .defaultDataFetcherExceptionHandler(new DataHubDataFetcherExceptionHandler())
            .instrumentation(chainedInstrumentation)
            .build();
  }

  public ExecutionResult execute(
      @Nonnull final String query,
      @Nullable final String operationName,
      @Nullable final Map<String, Object> variables,
      @Nonnull final QueryContext context) {
    /*
     * Init DataLoaderRegistry - should be created for each request.
     */
    LazyDataLoaderRegistry register = new LazyDataLoaderRegistry(context, _dataLoaderSuppliers);

    /*
     * Construct execution input
     */
    ExecutionInput executionInput =
        ExecutionInput.newExecutionInput()
            .query(query)
            .operationName(operationName)
            .variables(variables)
            .dataLoaderRegistry(register)
            .context(context)
            // https://www.graphql-java.com/documentation/upgrade-notes/#how-to-use-the-inputinterceptor-to-use-the-legacy-parsevalue-behaviour-prior-to-v220
            .graphQLContext(
                Map.of(InputInterceptor.class, LegacyCoercingInputInterceptor.migratesValues()))
            .build();

    /*
     * Execute GraphQL Query
     */
    return _graphQL.execute(executionInput);
  }

  public GraphQL getGraphQL() {
    return _graphQL;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Used to construct a {@link GraphQLEngine}. */
  public static class Builder {

    private final List<String> _schemas = new ArrayList<>();
    private final Map<String, Function<QueryContext, DataLoader<?, ?>>> _loaderSuppliers =
        new HashMap<>();
    private final RuntimeWiring.Builder _runtimeWiringBuilder = newRuntimeWiring();
    private GraphQLConfiguration graphQLConfiguration;
    private MetricUtils metricUtils;

    /**
     * Used to add a schema file containing the GQL types resolved by the engine.
     *
     * <p>If multiple files are provided, their schemas will be merged together.
     */
    public Builder addSchema(final String schema) {
      _schemas.add(schema);
      return this;
    }

    /**
     * Used to register a {@link DataLoader} to be used within the configured resolvers.
     *
     * <p>The {@link Supplier} provided is expected to return a new instance of {@link DataLoader}
     * when invoked.
     *
     * <p>If multiple loaders are registered with the name, the latter will override the former.
     */
    public Builder addDataLoader(
        final String name, final Function<QueryContext, DataLoader<?, ?>> dataLoaderSupplier) {
      _loaderSuppliers.put(name, dataLoaderSupplier);
      return this;
    }

    /**
     * Used to register multiple {@link DataLoader}s for use within the configured resolvers.
     *
     * <p>The included {@link Supplier} provided is expected to return a new instance of {@link
     * DataLoader} when invoked.
     *
     * <p>If multiple loaders are registered with the name, the latter will override the former.
     */
    public Builder addDataLoaders(
        Map<String, Function<QueryContext, DataLoader<?, ?>>> dataLoaderSuppliers) {
      _loaderSuppliers.putAll(dataLoaderSuppliers);
      return this;
    }

    /**
     * Used to configure the runtime wiring (data fetchers & type resolvers) used in resolving the
     * Graph QL schema.
     *
     * <p>The {@link Consumer} provided accepts a {@link RuntimeWiring.Builder} and should register
     * any required data + type resolvers.
     */
    public Builder configureRuntimeWiring(final Consumer<RuntimeWiring.Builder> builderFunc) {
      if (!this.graphQLConfiguration.getQuery().isIntrospectionEnabled())
        _runtimeWiringBuilder.fieldVisibility(
            NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY);
      builderFunc.accept(_runtimeWiringBuilder);
      return this;
    }

    public Builder setGraphQLConfiguration(final GraphQLConfiguration graphQLConfiguration) {
      this.graphQLConfiguration = graphQLConfiguration;
      return this;
    }

    public Builder setMetricUtils(MetricUtils metricUtils) {
      this.metricUtils = metricUtils;
      return this;
    }

    /** Builds a {@link GraphQLEngine}. */
    public GraphQLEngine build() {
      return new GraphQLEngine(
          _schemas,
          _runtimeWiringBuilder.build(),
          _loaderSuppliers,
          graphQLConfiguration,
          metricUtils);
    }
  }
}
