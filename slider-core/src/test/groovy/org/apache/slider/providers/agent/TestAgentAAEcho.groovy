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

package org.apache.slider.providers.agent

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.slider.agent.rest.RestAPIClientTestDelegates
import org.apache.slider.api.ResourceKeys
import org.apache.slider.api.types.ComponentInformation
import org.apache.slider.client.SliderClient
import org.apache.slider.client.rest.SliderApplicationApiRestClient
import org.apache.slider.common.SliderXmlConfKeys
import org.apache.slider.core.main.ServiceLauncher
import org.apache.slider.providers.PlacementPolicy
import org.junit.Test

import static org.apache.slider.common.params.Arguments.*
import static org.apache.slider.providers.agent.AgentKeys.*
import static org.apache.slider.server.appmaster.management.MetricsKeys.METRICS_LOGGING_ENABLED
import static org.apache.slider.server.appmaster.management.MetricsKeys.METRICS_LOGGING_LOG_INTERVAL

/**
 * Tests an echo command
 */
@CompileStatic
@Slf4j
class TestAgentAAEcho extends TestAgentEcho {

  @Test
  public void testAgentEcho() throws Throwable {
    assumeValidServerEnv()
    def conf = configuration
    conf.setBoolean(METRICS_LOGGING_ENABLED, true)
    conf.setInt(METRICS_LOGGING_LOG_INTERVAL, 1)
    String clustername = createMiniCluster("testaaecho",
        configuration,
        1,
        1,
        1,
        true,
        false)

    validatePaths()

    def echo = "echo"
    Map<String, Integer> roles = buildRoleMap(echo)
    ServiceLauncher<SliderClient> launcher = buildAgentCluster(clustername,
        roles,
        [
            ARG_OPTION, PACKAGE_PATH, slider_core.absolutePath,
            ARG_OPTION, APP_DEF, toURIArg(app_def_path),
            ARG_OPTION, AGENT_CONF, toURIArg(agt_conf_path),
            ARG_OPTION, AGENT_VERSION, toURIArg(agt_ver_path),
            ARG_RES_COMP_OPT, echo, ResourceKeys.COMPONENT_PRIORITY, "1",
            ARG_RES_COMP_OPT, echo, ResourceKeys.COMPONENT_PLACEMENT_POLICY,
              "" + PlacementPolicy.ANTI_AFFINITY_REQUIRED,
            ARG_COMP_OPT, echo, SCRIPT_PATH, echo_py,
            ARG_COMP_OPT, echo, SERVICE_NAME, "Agent",
            ARG_DEFINE, 
            SliderXmlConfKeys.KEY_SLIDER_AM_DEPENDENCY_CHECKS_DISABLED + "=false",
            ARG_COMP_OPT, echo, TEST_RELAX_VERIFICATION, "true",

        ],
        true, true,
        true)
    postLaunchActions(launcher.service, clustername, echo, roles)
  }

  /**
   * Build the role map to use when creating teh cluster
   * @param roleName the name used for the echo role
   * @return the map
   */
  protected Map<String, Integer> buildRoleMap(String roleName) {
    [
        (roleName): 3,
    ];
  }

  /**
   * Any actions to perform after starting the agent cluster
   * @param sliderClient client for the cluster
   * @param clustername cluster name
   * @param roleName name of the echo role
   * @parm original set of roles
   */
  protected void postLaunchActions(SliderClient sliderClient,
      String clustername,
      String roleName,
      Map<String, Integer> roles) {
    def onlyOneEcho = [(roleName): 1]
    waitForRoleCount(sliderClient, onlyOneEcho, AGENT_CLUSTER_STARTUP_TIME)
    //sleep a bit
    sleep(5000)
    //expect the role count to be the same
    waitForRoleCount(sliderClient, onlyOneEcho, 1000)

    queryRestAPI(sliderClient, roles)
    // flex size
    // while running, ask for many more, expect them to still be outstanding
    sleep(5000)
    sliderClient.flex(clustername, [(roleName): 50]);
    waitForRoleCount(sliderClient, onlyOneEcho, 1000)

    // while running, flex it to size = 1
    sleep(1000)
    sliderClient.flex(clustername, onlyOneEcho);
    waitForRoleCount(sliderClient, onlyOneEcho, 1000)

  }

  protected void queryRestAPI(SliderClient sliderClient, Map<String, Integer> roles) {
    initHttpTestSupport(sliderClient.config)
    def applicationReport = sliderClient.applicationReport
    def proxyAM = applicationReport.trackingUrl
    GET(proxyAM)
    describe "Proxy SliderRestClient Tests"
    SliderApplicationApiRestClient restAPI =
        new SliderApplicationApiRestClient(createUGIJerseyClient(), proxyAM)
    def echoInfo = restAPI.getComponent(ECHO)
    assert echoInfo.pendingAntiAffineRequestCount == 3
    // no active requests ... there's no capacity
    assert !echoInfo.isAARequestOutstanding
  }
}
