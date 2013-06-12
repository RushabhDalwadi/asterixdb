/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.metadata.feeds;

import java.util.HashMap;
import java.util.Map;

import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;

/**
 * Represents the base class that is required to be extended by every
 * implementation of the IDatasourceAdapter interface.
 */
public abstract class AbstractDatasourceAdapter implements IDatasourceAdapter {

    private static final long serialVersionUID = 1L;

    protected Map<String, Object> configuration;
    protected transient AlgebricksPartitionConstraint partitionConstraint;
    protected IAType atype;
    protected IHyracksTaskContext ctx;

    protected static final Map<String, Object> formatToParserFactoryMap = initializeFormatParserFactoryMap();

    public static final String KEY_FORMAT = "format";
    public static final String KEY_PARSER_FACTORY = "parser";
    public static final String FORMAT_DELIMITED_TEXT = "delimited-text";
    public static final String FORMAT_ADM = "adm";

    private static Map<String, Object> initializeFormatParserFactoryMap() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(FORMAT_DELIMITED_TEXT, "edu.uci.ics.asterix.runtime.operators.file.NtDelimitedDataTupleParserFactory");
        map.put(FORMAT_ADM, "edu.uci.ics.asterix.runtime.operators.file.AdmSchemafullRecordParserFactory");
        return map;
    }

}
