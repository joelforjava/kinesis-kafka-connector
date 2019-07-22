package com.amazon.kinesis.kafka;

import java.util.*;

import com.amazon.kinesis.kafka.config.ClusterMapping;
import com.amazon.kinesis.kafka.config.ConfigParser;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClient;
import com.amazonaws.services.kinesisfirehose.model.AmazonKinesisFirehoseException;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamRequest;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordResult;
import com.amazonaws.services.kinesisfirehose.model.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author nehalmeh
 *
 */
public class FirehoseSinkTask extends SinkTask {

    private static Logger log = LoggerFactory.getLogger(FirehoseSinkTask.class);

	private AmazonKinesisFirehoseClient firehoseClient;

	private boolean batch;
	
	private int batchSize;
	
	private int batchSizeInBytes;

	private static Map<String, List<String>> lookup;

	@Override
	public String version() {
		return new FirehoseSinkConnector().version();
	}

	@Override
	public void flush(Map<TopicPartition, OffsetAndMetadata> arg0) {
	}

	@Override
	public void put(Collection<SinkRecord> sinkRecords) {

		if (batch) {
            putRecordsInBatch(sinkRecords);
        } else {
            putRecords(sinkRecords);
        }

	}

	@Override
	public void start(Map<String, String> props) {
        start(props, null);
    }

    protected void start(Map<String, String> props, AmazonKinesisFirehoseClient client) {

		String mappingFileUrl = props.get(FirehoseSinkConnector.MAPPING_FILE);
        String clusterName = props.get(FirehoseSinkConnector.CLUSTER_NAME);
		if (mappingFileUrl != null) {
		    log.info("Property for {} found. Attempting to load this configuration file.", FirehoseSinkConnector.MAPPING_FILE);
            Optional<ClusterMapping> optionalMapping = ConfigParser.parse(mappingFileUrl);
            if (optionalMapping.isPresent()) {
                ClusterMapping clusterMapping = optionalMapping.get();
                String cName = clusterMapping.getClusterName();
                log.info("Using cluster name: {}", cName);
                lookup = clusterMapping.getStreamsAsMap();
            } else {
                log.error("Parser could not correctly parse the mapping file at {}. Please verify the configuration.", mappingFileUrl);
                throw new ConfigException("Parser could not correctly parse the mapping file");
            }
        } else if (clusterName != null) {
		    log.warn("Using cluster name: {}", clusterName);
		    log.warn("Attempting to use hard-coded mappings. Please provide a YAML mapping file in the future");
            lookup = StreamMappings.lookup(clusterName);
        } else {
	        throw new ConfigException("Connector cannot start without required property value for either 'mappingFile' or 'clusterName'.");
        }

		batch = Boolean.parseBoolean(props.get(FirehoseSinkConnector.BATCH));
		
		batchSize = Integer.parseInt(props.get(FirehoseSinkConnector.BATCH_SIZE));
		
		batchSizeInBytes = Integer.parseInt(props.get(FirehoseSinkConnector.BATCH_SIZE_IN_BYTES));

		if (client != null) {
		    this.firehoseClient = client;
        } else {
            firehoseClient = new AmazonKinesisFirehoseClient(new DefaultAWSCredentialsProviderChain());
            firehoseClient.setRegion(RegionUtils.getRegion(props.get(FirehoseSinkConnector.REGION)));
        }

        log.info("[VALIDATING] all configured delivery streams");

		lookup.forEach((topic, streams) -> streams.forEach(this::validateDeliveryStream));

        log.info("[SUCCESS] all configured delivery streams are validated");
	}

	@Override
	public void stop() {

	}


	/**
	 * Validates status of given Amazon Kinesis Firehose Delivery Stream.
	 */
	private void validateDeliveryStream(String deliveryStreamName) {
		DescribeDeliveryStreamRequest describeDeliveryStreamRequest = new DescribeDeliveryStreamRequest();

		describeDeliveryStreamRequest.setDeliveryStreamName(deliveryStreamName);

		DescribeDeliveryStreamResult describeDeliveryStreamResult = firehoseClient
				.describeDeliveryStream(describeDeliveryStreamRequest);

		if (!describeDeliveryStreamResult.getDeliveryStreamDescription().getDeliveryStreamStatus().equals("ACTIVE")) {
            throw new ConfigException("Connector cannot start as configured delivery stream is not active"
                    + describeDeliveryStreamResult.getDeliveryStreamDescription().getDeliveryStreamStatus());
        }
	}

    /**
     * Method to perform PutRecordBatch operation with the given record list.
     *
     * @param recordList
     *            the collection of records
     * @return the output of PutRecordBatch
     */
    private PutRecordBatchResult putRecordBatch(List<Record> recordList, String deliveryStreamName) {
        PutRecordBatchRequest putRecordBatchRequest = new PutRecordBatchRequest();
        putRecordBatchRequest.setDeliveryStreamName(deliveryStreamName);
        putRecordBatchRequest.setRecords(recordList);

        log.debug("[TRYING] stream: " + deliveryStreamName + " record count: " + recordList.size());

        // Put Record Batch records. Max No.Of Records we can put in a
        // single put record batch request is 500 and total size < 4MB
        PutRecordBatchResult putRecordBatchResult = null;
        try {
            putRecordBatchResult = firehoseClient.putRecordBatch(putRecordBatchRequest);
        }catch(AmazonKinesisFirehoseException akfe){
            log.error("Amazon Kinesis Firehose Exception:" + akfe.getLocalizedMessage());
            throw akfe;
        }catch(Exception e){
            log.error("Connector Exception" + e.getLocalizedMessage());
            throw e;
        }

        log.info("[SUCCESS] stream: " + deliveryStreamName + " record count: " + recordList.size());

        return putRecordBatchResult;
    }

    /**
	 * @param sinkRecords
	 */
	private void putRecordsInBatch(Collection<SinkRecord> sinkRecords) {
		Map<String, List<Record>> recordList = new LinkedHashMap<>();
		int recordsInBatch = 0;
		int recordsSizeInBytes = 0;

		for (SinkRecord sinkRecord : sinkRecords) {
		    String topic = sinkRecord.topic();
		    List<String> streams = lookup.get(topic);
		    if (streams == null || streams.size() == 0) {
		        String error = "No streams found for topic: " + topic;
		        log.error(error);
		        throw new ConfigException(error);
            }

			Record record = DataUtility.createRecord(sinkRecord);
		    for (String s: streams) {
		        if (recordList.containsKey(s)) {
		            recordList.get(s).add(record);
                } else {
		            ArrayList<Record> al = new ArrayList<>();
		            al.add(record);
		            recordList.put(s, al);
                }
            }

			recordsInBatch++;
			recordsSizeInBytes += record.getData().capacity();
						
			if (recordsInBatch == batchSize || recordsSizeInBytes > batchSizeInBytes) {
				putBatch(recordList);
				recordList.clear();
				recordsInBatch = 0;
				recordsSizeInBytes = 0;
			}
		}

		if (recordsInBatch > 0) {
			// putRecordBatch(recordList);
            putBatch(recordList);
		}
	}


	private void putBatch(Map<String, List<Record>> recordList) {
        recordList.forEach((key, value) -> putRecordBatch(value, key));
    }

	/**
	 * @param sinkRecords
	 */
	private void putRecords(Collection<SinkRecord> sinkRecords) {

	    String deliveryStreamName = "todo";

		for (SinkRecord sinkRecord : sinkRecords) {

			PutRecordRequest putRecordRequest = new PutRecordRequest();
			putRecordRequest.setDeliveryStreamName(deliveryStreamName);
			putRecordRequest.setRecord(DataUtility.createRecord(sinkRecord));
			
			PutRecordResult putRecordResult;
			try {
				firehoseClient.putRecord(putRecordRequest);
			}catch(AmazonKinesisFirehoseException akfe){
				 System.out.println("Amazon Kinesis Firehose Exception:" + akfe.getLocalizedMessage());
			}catch(Exception e){
				 System.out.println("Connector Exception" + e.getLocalizedMessage());
			}
		}
	}
}
