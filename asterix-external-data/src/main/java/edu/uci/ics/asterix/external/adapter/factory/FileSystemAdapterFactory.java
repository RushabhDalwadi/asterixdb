package edu.uci.ics.asterix.external.adapter.factory;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.external.util.DNSResolverFactory;
import edu.uci.ics.asterix.external.util.INodeResolver;
import edu.uci.ics.asterix.external.util.INodeResolverFactory;
import edu.uci.ics.asterix.metadata.feeds.IAdapterFactory;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.runtime.operators.file.AdmSchemafullRecordParserFactory;
import edu.uci.ics.asterix.runtime.operators.file.NtDelimitedDataTupleParserFactory;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.DoubleParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.FloatParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.IntegerParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.LongParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.UTF8StringParserFactory;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParser;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParserFactory;

public abstract class FileSystemAdapterFactory implements IAdapterFactory {

    protected Map<String, Object> configuration;
    protected static INodeResolver nodeResolver;
    private static final INodeResolver DEFAULT_NODE_RESOLVER = new DNSResolverFactory().createNodeResolver();

    public static final String KEY_FORMAT = "format";
    public static final String KEY_PARSER_FACTORY = "parser";
    public static final String KEY_DELIMITER = "delimiter";
    public static final String KEY_PATH = "path";
    public static final String KEY_SOURCE_DATATYPE = "source-datatype";

    public static final String FORMAT_DELIMITED_TEXT = "delimited-text";
    public static final String FORMAT_ADM = "adm";

    public static final String NODE_RESOLVER_FACTORY_PROPERTY = "node.Resolver";

    private static final Logger LOGGER = Logger.getLogger(FileSystemAdapterFactory.class.getName());

    protected ITupleParserFactory parserFactory;
    protected ITupleParser parser;

    protected static final HashMap<ATypeTag, IValueParserFactory> typeToValueParserFactMap = new HashMap<ATypeTag, IValueParserFactory>();
    static {
        typeToValueParserFactMap.put(ATypeTag.INT32, IntegerParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.FLOAT, FloatParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.DOUBLE, DoubleParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.INT64, LongParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.STRING, UTF8StringParserFactory.INSTANCE);
    }

    protected ITupleParserFactory getDelimitedDataTupleParserFactory(ARecordType recordType) throws AsterixException {
        int n = recordType.getFieldTypes().length;
        IValueParserFactory[] fieldParserFactories = new IValueParserFactory[n];
        for (int i = 0; i < n; i++) {
            ATypeTag tag = recordType.getFieldTypes()[i].getTypeTag();
            IValueParserFactory vpf = typeToValueParserFactMap.get(tag);
            if (vpf == null) {
                throw new NotImplementedException("No value parser factory for delimited fields of type " + tag);
            }
            fieldParserFactories[i] = vpf;
        }
        String delimiterValue = (String) configuration.get(KEY_DELIMITER);
        if (delimiterValue != null && delimiterValue.length() > 1) {
            throw new AsterixException("improper delimiter");
        }

        Character delimiter = delimiterValue.charAt(0);
        return new NtDelimitedDataTupleParserFactory(recordType, fieldParserFactories, delimiter);
    }

    protected ITupleParserFactory getADMDataTupleParserFactory(ARecordType recordType) throws AsterixException {
        try {
            return new AdmSchemafullRecordParserFactory(recordType);
        } catch (Exception e) {
            throw new AsterixException(e);
        }

    }

    protected void configureFormat(IAType sourceDatatype) throws Exception {
        String parserFactoryClassname = (String) configuration.get(KEY_PARSER_FACTORY);
        if (parserFactoryClassname == null) {
            String specifiedFormat = (String) configuration.get(KEY_FORMAT);
            if (specifiedFormat == null) {
                throw new IllegalArgumentException(" Unspecified data format");
            } else if (FORMAT_DELIMITED_TEXT.equalsIgnoreCase(specifiedFormat)) {
                parserFactory = getDelimitedDataTupleParserFactory((ARecordType) sourceDatatype);
            } else if (FORMAT_ADM.equalsIgnoreCase((String) configuration.get(KEY_FORMAT))) {
                parserFactory = getADMDataTupleParserFactory((ARecordType) sourceDatatype);
            } else {
                throw new IllegalArgumentException(" format " + configuration.get(KEY_FORMAT) + " not supported");
            }
        } else {
            parserFactory = (ITupleParserFactory) Class.forName(parserFactoryClassname).newInstance();
        }

    }

    protected INodeResolver getNodeResolver() {
        if (nodeResolver == null) {
            nodeResolver = initNodeResolver();
        }
        return nodeResolver;
    }

    private static INodeResolver initNodeResolver() {
        INodeResolver nodeResolver = null;
        String configuredNodeResolverFactory = System.getProperty(NODE_RESOLVER_FACTORY_PROPERTY);
        if (configuredNodeResolverFactory != null) {
            try {
                nodeResolver = ((INodeResolverFactory) (Class.forName(configuredNodeResolverFactory).newInstance()))
                        .createNodeResolver();

            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Unable to create node resolver from the configured classname "
                            + configuredNodeResolverFactory + "\n" + e.getMessage());
                }
                nodeResolver = DEFAULT_NODE_RESOLVER;
            }
        } else {
            nodeResolver = DEFAULT_NODE_RESOLVER;
        }
        return nodeResolver;
    }
}