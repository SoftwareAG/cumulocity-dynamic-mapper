import { IIdentified } from "@c8y/client";

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

export enum ExtensionStatus {
  COMPLETE = "COMPLETE",
  PARTIALLY = "PARTIALLY",
  NOT_LOADED = "NOT_LOADED",
  UNKNOWN = "UNKNOWN",
}

export interface PayloadWrapper {
  message: string;
}

export enum ValidationError {
  Only_One_Multi_Level_Wildcard,
  Only_One_Single_Level_Wildcard,
  Multi_Level_Wildcard_Only_At_End,
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used,
  One_Substitution_Defining_Device_Identifier_Must_Be_Used,
  TemplateTopic_Must_Match_The_SubscriptionTopic,
  TemplateTopic_Not_Unique,
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic,
  Target_Template_Must_Be_Valid_JSON,
  Source_Template_Must_Be_Valid_JSON,
  No_Multi_Level_Wildcard_Allowed_In_TemplateTopic,
  Device_Identifier_Must_Be_Selected,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name,
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name,
  FilterOutbound_Must_Be_Unique
}

export const ValidationFormlyError = {
  Only_One_Multi_Level_Wildcard: {
    message: 'Only one MultiLevel wildcard "#" is allowed.',
  },
  Only_One_Single_Level_Wildcard: {
    message: 'Only one SingleLevel wildcard "+" is allowed.',
  },
  Multi_Level_Wildcard_Only_At_End: {
    message: 'MultiLevel wildcard "#" can only appear at the end.',
  },
  Only_One_Substitution_Defining_Device_Identifier_Can_Be_Used: {
    message: "Only one substitution defining the DeviceIdentifier can be used.",
  },
  One_Substitution_Defining_Device_Identifier_Must_Be_Used: {
    message: 'Only one MultiLevel wildcard "#" is allowed.',
  },
  TemplateTopic_Must_Match_The_SubscriptionTopic: {
    message: "The TemplateTopic must match the SubscriptionTopic.",
  },
  TemplateTopic_Not_Unique: {
    message: "This TemplateTopic must be unique across other TemplateTopics.",
  },
  TemplateTopic_Must_Not_Be_Substring_Of_Other_TemplateTopic: {
    message:
      "This TemplateTopic can't be the starting part of another  TemplateTopic or vice.",
  },
  Target_Template_Must_Be_Valid_JSON: {
    message: "TargetTemplate must be valid JSON.",
  },
  Source_Template_Must_Be_Valid_JSON: {
    message: "SourceTemplate must be valid JSON..",
  },
  No_Multi_Level_Wildcard_Allowed_In_TemplateTopic: {
    message: "No MultiLevel wildcard is allowed in TemplateTopic.",
  },
  Device_Identifier_Must_Be_Selected: {
    message: "DeviceIdentifier must be selected.",
  },
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
    {
      message:
        "The TemplateTopic and TemplateTopicSample do not have same number of levels in the Topic Name",
    },
  TemplateTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
    {
      message:
        "TemplateTopic and TemplateTopicSample do not have same structure in the Topic Name.",
    },
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name:
    {
      message:
        "The PublishTopic and TemplateTopicSample do not have same number of levels in the Topic Name",
    },
  PublishTopic_And_TemplateTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name:
    {
      message:
        "PublishTopic and TemplateTopicSample do not have same structure in the Topic Name.",
    },
  FilterOutbound_Must_Be_Unique:
    {
      message:
        "FilterOutbound must be unique within all outbound mappings.",
    },
};

export class C8YAPISubscription {
  api: string;
  devices: IIdentified[];
}