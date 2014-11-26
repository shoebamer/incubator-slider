/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.slider.server.appmaster.management;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.ganglia.GangliaReporter;
import com.google.common.base.Preconditions;
import info.ganglia.gmetric4j.gmetric.GMetric;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.CompositeService;
import org.apache.slider.server.services.workflow.ClosingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * YARN service which hooks up Codahale metrics to 
 * JMX, and, if enabled Ganglia and/or an SLF4J log.
 */
public class MetricsBindingService extends CompositeService
    implements MetricsKeys {
  protected static final Logger log =
      LoggerFactory.getLogger(MetricsBindingService.class);
  private final MetricRegistry metrics;

  private String reportingDetails = "not started";


  public MetricsBindingService(String name,
      MetricRegistry metrics) {
    super(name);
    Preconditions.checkArgument(metrics != null, "Null metrics");
    this.metrics = metrics;
  }

  /**
   * Instantiate...create a metric registry in the process
   * @param name service name
   */
  public MetricsBindingService(String name) {
    this(name, new MetricRegistry());
  }

  /**
   * Accessor for the metrics instance
   * @return the metrics
   */
  public MetricRegistry getMetrics() {
    return metrics;
  }

  @Override
  protected void serviceStart() throws Exception {
    super.serviceStart();

    StringBuilder summary = new StringBuilder();
    Configuration conf = getConfig();

    summary.append("Reporting to JMX");
    // always start the JMX binding
    JmxReporter jmxReporter;
    jmxReporter = JmxReporter.forRegistry(metrics).build();
    jmxReporter.start();
    addService(new ClosingService<JmxReporter>(jmxReporter));


    // Ganglia
    if (conf.getBoolean(METRICS_GANGLIA_ENABLED, false)) {
      GangliaReporter gangliaReporter;
      String host = conf.getTrimmed(METRICS_GANGLIA_HOST, "");
      int port = conf.getInt(METRICS_GANGLIA_PORT, DEFAULT_GANGLIA_PORT);
      int interval = conf.getInt(METRICS_GANGLIA_REPORT_INTERVAL, 60);
      int ttl = 1;
      GMetric.UDPAddressingMode
          mcast = GMetric.UDPAddressingMode.getModeForAddress(host);
      boolean ganglia31 = conf.getBoolean(METRICS_GANGLIA_VERSION_31, true);

      final GMetric ganglia =
          new GMetric(
              host,
              port,
              mcast,
              ttl,
              ganglia31);
      gangliaReporter = GangliaReporter.forRegistry(metrics)
                                       .convertRatesTo(TimeUnit.SECONDS)
                                       .convertDurationsTo(
                                           TimeUnit.MILLISECONDS)
                                       .build(ganglia);
      gangliaReporter.start(interval, TimeUnit.SECONDS);
      addService(new ClosingService<ScheduledReporter>(gangliaReporter));
      summary.append(String.format(", Ganglia at %s:%d interval=%d",
          host, port, interval));
    }

    // Logging
    if (conf.getBoolean(METRICS_LOGGING_ENABLED, false)) {
      ScheduledReporter reporter;
      String logName =
          conf.getTrimmed(METRICS_LOGGING_LOG, METRICS_DEFAULT_LOG);
      int interval = conf.getInt(METRICS_LOGGING_LOG_INTERVAL,
          METRICS_DEFAULT_LOG_INTERVAL);
      reporter = Slf4jReporter.forRegistry(metrics)
                              .convertRatesTo(TimeUnit.SECONDS)
                              .outputTo(LoggerFactory.getLogger(logName))
                              .convertDurationsTo(TimeUnit.MILLISECONDS)
                              .build();
      reporter.start(interval, TimeUnit.SECONDS);
      addService(new ClosingService<ScheduledReporter>(reporter));
      summary.append(String.format(", SLF4J to log %s interval=%d",
          logName, interval));
    }
    reportingDetails = summary.toString();
    log.info(reportingDetails);
  }

  @Override
  public String toString() {
    return super.toString() + " " + reportingDetails;
  }
}
