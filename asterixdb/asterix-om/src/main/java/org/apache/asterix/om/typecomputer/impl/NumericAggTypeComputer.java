/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.om.typecomputer.impl;

import org.apache.asterix.om.typecomputer.base.AbstractResultTypeComputer;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.AUnionType;
import org.apache.asterix.om.types.IAType;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.exceptions.NotImplementedException;

public class NumericAggTypeComputer extends AbstractResultTypeComputer {

    private static final String ERR_MSG = "Aggregator is not implemented for ";

    public static final NumericAggTypeComputer INSTANCE = new NumericAggTypeComputer();

    private NumericAggTypeComputer() {
    }

    @Override
    protected void checkArgType(int argIndex, IAType type) throws AlgebricksException {
        ATypeTag tag = type.getTypeTag();
        switch (tag) {
            case DOUBLE:
            case FLOAT:
            case INT64:
            case INT32:
            case INT16:
            case INT8:
            case ANY:
                break;
            default:
                throw new NotImplementedException(ERR_MSG + tag);
        }
    }

    @Override
    protected IAType getResultType(IAType... strippedInputTypes) {
        ATypeTag tag = strippedInputTypes[0].getTypeTag();
        IAType type;
        switch (tag) {
            case DOUBLE:
            case FLOAT:
            case INT64:
            case INT32:
            case INT16:
            case INT8:
            case ANY:
                type = strippedInputTypes[0];
                break;
            default:
                throw new NotImplementedException(ERR_MSG + tag);
        }
        return AUnionType.createNullableType(type, "AggResult");
    }
}
