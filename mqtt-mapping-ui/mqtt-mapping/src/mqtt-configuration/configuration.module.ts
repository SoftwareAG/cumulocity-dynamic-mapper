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

import { NgModule } from "@angular/core";
import { CoreModule, hookRoute } from "@c8y/ngx-components";
import { BrokerConfigurationComponent } from "./broker-configuration.component";
import { TerminateBrokerConnectionModalComponent } from "./terminate/terminate-connection-modal.component";
import { AdminGuard } from "../shared/admin.guard";

@NgModule({
  declarations: [
    BrokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  imports: [CoreModule],
  entryComponents: [TerminateBrokerConnectionModalComponent],
  exports: [],
  providers: [
    hookRoute({
      path: "sag-ps-pkg-mqtt-mapping/configuration",
      component: BrokerConfigurationComponent,
      canActivate: [AdminGuard],
    }),
  ],
})
export class ConfigurationModule {}
