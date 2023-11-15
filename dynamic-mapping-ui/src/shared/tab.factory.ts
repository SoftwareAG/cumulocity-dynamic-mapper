/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */
import { Injectable } from "@angular/core";
import { TabFactory, Tab } from "@c8y/ngx-components";
import { Router } from "@angular/router";
import { BrokerConfigurationService } from "../configuration/shared/broker-configuration.service";
@Injectable()
export class MappingTabFactory implements TabFactory {
  constructor(
    public router: Router,
    private brokerConfigurationService: BrokerConfigurationService
  ) {}

  async get() {
    //console.log("MappingTabFactory",this.router.url, this.router.url.match(/sag-ps-pkg-dynamic-mapping/g));
    const feature = await this.brokerConfigurationService.getFeatures();

    const tabs: Tab[] = [];
    if (this.router.url.match(/sag-ps-pkg-dynamic-mapping/g)) {
      if (feature?.userHasMappingAdminRole) {
        tabs.push({
          path: "sag-ps-pkg-dynamic-mapping/configuration",
          priority: 930,
          label: "Configuration",
          icon: "cog",
          orientation: "horizontal",
        } as Tab);
      }
      tabs.push({
        path: "sag-ps-pkg-dynamic-mapping/mappings/inbound",
        priority: 920,
        label: "Mapping Inbound",
        icon: "swipe-right",
        orientation: "horizontal",
      } as Tab);
      this.brokerConfigurationService.getFeatures();
      if (feature?.outputMappingEnabled) {
        tabs.push({
          path: "sag-ps-pkg-dynamic-mapping/mappings/outbound",
          priority: 920,
          label: "Mapping Outbound",
          icon: "swipe-left",
          orientation: "horizontal",
        } as Tab);
      }
      tabs.push({
        path: "sag-ps-pkg-dynamic-mapping/monitoring",
        priority: 910,
        label: "Monitoring",
        icon: "monitoring",
        orientation: "horizontal",
      } as Tab);
      tabs.push({
        path: "sag-ps-pkg-dynamic-mapping/testing",
        priority: 900,
        label: "Test Devices",
        icon: "reflector-bulb",
        orientation: "horizontal",
      } as Tab);
      tabs.push({
        path: "sag-ps-pkg-dynamic-mapping/tree",
        priority: 890,
        label: "Mapping Tree Inbound",
        icon: "tree-structure",
        orientation: "horizontal",
      } as Tab);
      if (feature?.userHasMappingAdminRole) {
        tabs.push({
          path: "sag-ps-pkg-dynamic-mapping/extensions",
          priority: 880,
          label: "Processor Extension",
          icon: "plugin",
          orientation: "horizontal",
        } as Tab);
      }
    }
    return tabs;
  }
}
