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
import {
  Component,
  Input,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation
} from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { C8yStepper, ModalLabels } from '@c8y/ngx-components';
import { Subject } from 'rxjs';
import { Direction, MappingType } from '../../shared';
import { isDisabled } from '../shared/util';

@Component({
  selector: 'd11r-mapping-type',
  templateUrl: './mapping-type.component.html',
  encapsulation: ViewEncapsulation.None
})
export class MappingTypeComponent implements OnInit, OnDestroy {
  @Input() direction: Direction;

  isDisabled = isDisabled;
  formGroupStep: FormGroup;
  @ViewChild(C8yStepper, { static: true }) closeSubject: Subject<MappingType>;
  labels: ModalLabels = { cancel: 'Cancel' };
  canOpenInBrowser: boolean = false;
  errorMessage: string;
  MappingType = MappingType;
  Direction = Direction;
  mappingType: MappingType.JSON;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.closeSubject = new Subject();
    console.log('Subject:', this.closeSubject, this.labels);
    this.formGroupStep = this.fb.group({
      mappingType: ['', Validators.required]
    });
  }

  onDismiss() {
    this.closeSubject.next(undefined);
    this.closeSubject.complete();
  }

  onClose() {
    this.closeSubject.next(this.mappingType);
    this.closeSubject.complete();
  }

  onSelectMappingType(t) {
    this.mappingType = t;
    this.closeSubject.next(this.mappingType);
    this.closeSubject.complete();
  }

  ngOnDestroy() {
    this.closeSubject.complete();
  }
}
