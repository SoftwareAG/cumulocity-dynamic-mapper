import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { Router } from '@angular/router';
import { IManagedObject } from '@c8y/client';
import { AlertService } from '@c8y/ngx-components';
import { BsModalRef } from 'ngx-bootstrap/modal';
import { ProcessorService } from './processor.service';

@Component({
  selector: 'c8y-processor-card',
  templateUrl: './processor-card.component.html'
})
export class ProcessorCardComponent implements OnInit {
  @Input() app: IManagedObject;
  @Output() onAppDeleted: EventEmitter<void> = new EventEmitter();

  isPackage: boolean = false;
  isMicroservice: boolean = false;
  isFeature: boolean = true;
  isExternal: boolean = false;
  isUnpacked: boolean = false;

  constructor(
    private processorService: ProcessorService,
    private alertService: AlertService,
    private router: Router,
    private modal: BsModalRef
  ) {}

  async ngOnInit() {
  }

  detail() {
  }

  async delete() {
    try {
      await this.processorService.deleteExtension(this.app);
      this.onAppDeleted.emit();
    } catch (ex) {
      if (ex) {
        this.alertService.addServerFailure(ex);
      }
    }
  }
}
