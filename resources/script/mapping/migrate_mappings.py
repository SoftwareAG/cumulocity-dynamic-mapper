#  Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
#  and/or its subsidiaries and/or its affiliates and/or their licensors.
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christof Strack, Stefan Witschel
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christoph Strack, Stefan Witschel

import json
import sys
import getopt

def main(argv):
    try:
        opts, args = getopt.getopt(argv, "i:o:d", ["input=", "output=", "dummy="])
    except:
        print("migrate_mappings.py -i <input> -o <output>")
        sys.exit()

    for opt, arg in opts:
        if opt == "-h":
            print("migrate_mappings.py  -i <input> -o <output>")
            sys.exit()
        elif opt in ("-o", "--output"):
            output = arg
        elif opt in ("-i", "--input"):
            input = arg

    mappings = []
    with open(input, "r") as f:
        mappings = json.load(f)
    # Closing file
    f.close()

    print(f"Reading {str(len(mappings))} from file {input}")

    for index, mapping in enumerate(mappings):
        # step 1: create new mapping
        mapping["subscriptionTopic"] = mapping["templateTopic"]
        del mapping["templateTopic"]

    with open(output, "w") as f:
        f.write(json.dumps(mappings, indent=4))

    print(f"Migrated {str(len(mappings))} mappings, wrote result to {output}")


if __name__ == "__main__":
    main(sys.argv[1:])
