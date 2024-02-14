package com.linkedin.openhouse.jobs.config;

import com.linkedin.openhouse.cluster.metrics.TagUtils;
import com.linkedin.openhouse.cluster.storage.filesystem.FsStorageProvider;
import com.linkedin.openhouse.common.config.BaseApplicationConfig;
import com.linkedin.openhouse.common.metrics.MetricsConstant;
import com.linkedin.openhouse.common.provider.HttpConnectionPoolProviderConfig;
import com.linkedin.openhouse.housetables.client.api.JobApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.jobs.services.HouseJobsCoordinator;
import com.linkedin.openhouse.jobs.services.JobsRegistry;
import com.linkedin.openhouse.jobs.services.livy.LivyJobsCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.util.ReflectionUtils;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Main Application Configuration to load cluster properties. */
@Slf4j
@Configuration
public class MainApplicationConfig extends BaseApplicationConfig {
  @Autowired private JobsProperties jobsProperties;
  @Autowired private FsStorageProvider fsStorageProvider;

  private static final int DNS_QUERY_TIMEOUT_SECONDS = 10;

  @Bean
  MeterRegistryCustomizer<MeterRegistry> provideMeterRegistry() {
    return registry ->
        registry
            .config()
            .commonTags(TagUtils.buildCommonTag(clusterProperties, MetricsConstant.JOBS_SERVICE));
  }

  @Bean
  JobsRegistry createJobsRegistry() {
    return JobsRegistry.from(jobsProperties, fsStorageProvider.storageProperties());
  }

  @Bean
  HouseJobsCoordinator createHouseJobCoordinator() {
    String coordinatorClassName = jobsProperties.getCoordinatorClassName();
    String baseEngineUrl = jobsProperties.getEngineUri();
    try {
      Class<?> coordinatorClass =
          ReflectionUtils.loadIfPresent(coordinatorClassName, getClass().getClassLoader());
      if (coordinatorClass != null) {
        Optional<Constructor<?>> cons =
            ReflectionUtils.findConstructor(coordinatorClass, baseEngineUrl);
        if (cons.isPresent()) {
          return (HouseJobsCoordinator) cons.get().newInstance(baseEngineUrl);
        }
      }
      log.warn(
          String.format(
              "Could not load class or find its constructor: %s, using Livy coordinator by default",
              coordinatorClassName));
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      log.warn(
          String.format(
              "Could not create instance of class: %s, using Livy coordinator by default",
              coordinatorClassName),
          e);
    }
    return new LivyJobsCoordinator(baseEngineUrl);
  }

  @Bean
  public JobApi provideApiInstance() {
    String htsBasePath = clusterProperties.getClusterHouseTablesBaseUri();
    // The default DNS query timeout is 5 sec for NameResolverProvider. Increasing this to 10 sec to
    // reduce intermittent
    // DNS lookup failure with timeout.
    HttpClient httpClient =
        HttpClient.create(
                HttpConnectionPoolProviderConfig.getCustomConnectionProvider(
                    "jobs-hts-custom-connection-pool"))
            .resolver(spec -> spec.queryTimeout(Duration.ofSeconds(DNS_QUERY_TIMEOUT_SECONDS)));
    WebClient webClient =
        ApiClient.buildWebClientBuilder()
            .baseUrl(htsBasePath)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    ApiClient apiClient = new ApiClient(webClient);
    apiClient.setBasePath(htsBasePath);
    return new JobApi(apiClient);
  }
}
