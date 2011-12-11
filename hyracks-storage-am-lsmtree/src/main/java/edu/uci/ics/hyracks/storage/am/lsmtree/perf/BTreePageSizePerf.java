package edu.uci.ics.hyracks.storage.am.lsmtree.perf;

import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparator;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import edu.uci.ics.hyracks.dataflow.common.util.SerdeUtils;
import edu.uci.ics.hyracks.storage.am.common.ophelpers.MultiComparator;
import edu.uci.ics.hyracks.storage.am.lsmtree.datagen.DataGenThread;

public class BTreePageSizePerf {
    public static void main(String[] args) throws Exception {
        // Disable logging so we can better see the output times.
        Enumeration<String> loggers = LogManager.getLogManager().getLoggerNames();
        while(loggers.hasMoreElements()) {
            String loggerName = loggers.nextElement();
            Logger logger = LogManager.getLogManager().getLogger(loggerName);
            logger.setLevel(Level.OFF);
        }
        
        int numTuples = 1000000;
        int batchSize = 10000;
        int numBatches = numTuples / batchSize;
        
        ISerializerDeserializer[] fieldSerdes = new ISerializerDeserializer[] { IntegerSerializerDeserializer.INSTANCE };
        ITypeTraits[] typeTraits = SerdeUtils.serdesToTypeTraits(fieldSerdes, 30);
        
        IBinaryComparator[] cmps = SerdeUtils.serdesToComparators(fieldSerdes, fieldSerdes.length);
        MultiComparator cmp = new MultiComparator(cmps);
        
        runExperiment(numBatches, batchSize, 1024, 100000, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 2048, 100000, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 4096, 25000, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 8192, 12500, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 16384, 6250, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 32768, 3125, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 65536, 1564, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 131072, 782, fieldSerdes, cmp, typeTraits);
        runExperiment(numBatches, batchSize, 262144, 391, fieldSerdes, cmp, typeTraits);
    }
    
    private static void runExperiment(int numBatches, int batchSize, int pageSize, int numPages, ISerializerDeserializer[] fieldSerdes, MultiComparator cmp, ITypeTraits[] typeTraits) throws Exception {
        System.out.println("PAGE SIZE: " + pageSize);
        System.out.println("NUM PAGES: " + numPages);
        System.out.println("MEMORY: " + (pageSize * numPages));
        int repeats = 5;
        long[] times = new long[repeats];
        //BTreeRunner runner = new BTreeRunner(numTuples, pageSize, numPages, typeTraits, cmp);
        InMemoryBTreeRunner runner = new InMemoryBTreeRunner(numBatches, pageSize, numPages, typeTraits, cmp);
        runner.init();
        int numThreads = 1;
        for (int i = 0; i < repeats; i++) {
            DataGenThread dataGen = new DataGenThread(numBatches, batchSize, 10, numThreads, fieldSerdes, 30, 50, false);
            dataGen.start();            
            times[i] = runner.runExperiment(dataGen, numThreads);
            System.out.println("TIME " + i + ": " + times[i] + "ms");
        }
        runner.deinit();
        long avgTime = 0;
        for (int i = 0; i < repeats; i++) {
            avgTime += times[i];
        }
        avgTime /= repeats;
        System.out.println("AVG TIME: " + avgTime + "ms");
        System.out.println("-------------------------------");
    }
}
