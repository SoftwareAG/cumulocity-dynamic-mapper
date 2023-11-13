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
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.core;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
// @NoArgsConstructor
// @AllArgsConstructor
public class ConnectorStatus implements Serializable {
    @NotNull
    public Status status;

    public ConnectorStatus(Status status) {
        this.status = status;
    }

    public ConnectorStatus() {
        this.status = Status.NOT_READY;
    }

    public static ConnectorStatus connected() {
        return new ConnectorStatus(Status.CONNECTED);
    }

    public static ConnectorStatus enabled() {
        return new ConnectorStatus(Status.ENABLED);
    }

    public static ConnectorStatus configured() {
        return new ConnectorStatus(Status.CONFIGURED);
    }

    public static ConnectorStatus notReady() {
        return new ConnectorStatus(Status.NOT_READY);
    }
}