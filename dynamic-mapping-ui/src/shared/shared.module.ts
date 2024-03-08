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
import { NgModule } from '@angular/core';
import { CoreModule } from '@c8y/ngx-components';
import { JsonEditor2Component } from './editor2/jsoneditor2.component';
import { BsDatepickerModule } from 'ngx-bootstrap/datepicker';
import { PaginationModule } from 'ngx-bootstrap/pagination';
import { ConfirmationModalComponent } from './confirmation/confirmation-modal.component';
import { CamelCasePipe } from './camel-case.pipe';
import { CapitalizeCasePipe } from './capitazilze-case.pipe';

@NgModule({
  declarations: [
    JsonEditor2Component,
    ConfirmationModalComponent,
    CamelCasePipe,
    CapitalizeCasePipe
  ],
  imports: [CoreModule, BsDatepickerModule, PaginationModule],
  exports: [
    JsonEditor2Component,
    ConfirmationModalComponent,
    CamelCasePipe,
    CapitalizeCasePipe
  ]
})
export class SharedModule {}