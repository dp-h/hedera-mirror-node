package com.hedera.mirror.importer.parser.record;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Named;
import javax.transaction.Transactional;
import lombok.extern.log4j.Log4j2;

import com.hedera.mirror.importer.domain.ApplicationStatusCode;
import com.hedera.mirror.importer.domain.RecordFile;
import com.hedera.mirror.importer.domain.TransactionTypeEnum;
import com.hedera.mirror.importer.exception.DuplicateFileException;
import com.hedera.mirror.importer.parser.FileParser;
import com.hedera.mirror.importer.parser.domain.RecordItem;
import com.hedera.mirror.importer.parser.domain.StreamFileData;
import com.hedera.mirror.importer.repository.ApplicationStatusRepository;
import com.hedera.mirror.importer.util.Utility;

/**
 * This is a utility file to read back service record file generated by Hedera node
 */
@Log4j2
@Named
@ConditionalOnRecordParser
public class RecordFileParser implements FileParser {

    private final ApplicationStatusRepository applicationStatusRepository;
    private final RecordParserProperties parserProperties;
    private final MeterRegistry meterRegistry;
    private final RecordItemListener recordItemListener;
    private final RecordStreamFileListener recordStreamFileListener;

    // Metrics
    private final Map<Boolean, Timer> parseDurationMetrics;
    private final Map<Integer, Timer> latencyMetrics;
    private final Map<Integer, DistributionSummary> sizeMetrics;
    private final Timer unknownLatencyMetric;
    private final DistributionSummary unknownSizeMetric;
    private final Timer parseLatencyMetric;

    public RecordFileParser(ApplicationStatusRepository applicationStatusRepository,
                            RecordParserProperties parserProperties, MeterRegistry meterRegistry,
                            RecordItemListener recordItemListener,
                            RecordStreamFileListener recordStreamFileListener) {
        this.applicationStatusRepository = applicationStatusRepository;
        this.parserProperties = parserProperties;
        this.meterRegistry = meterRegistry;
        this.recordItemListener = recordItemListener;
        this.recordStreamFileListener = recordStreamFileListener;

        // build parse metrics
        ImmutableMap.Builder<Boolean, Timer> parseDurationMetricsBuilder = ImmutableMap.builder();
        Timer.Builder parseDurationTimerBuilder = Timer.builder("hedera.mirror.parse.duration")
                .description("The duration in seconds it took to parse the file and store it in the database")
                .tag("type", parserProperties.getStreamType().toString());

        parseDurationMetricsBuilder.put(true, parseDurationTimerBuilder
                .tag("success", "true")
                .register(meterRegistry));

        parseDurationMetricsBuilder.put(false, parseDurationTimerBuilder
                .tag("success", "false")
                .register(meterRegistry));
        parseDurationMetrics = parseDurationMetricsBuilder.build();

        parseLatencyMetric = Timer.builder("hedera.mirror.parse.latency")
                .description("The difference in ms between the consensus time of the last transaction in the file " +
                        "and the time at which the file was processed successfully")
                .tag("type", parserProperties.getStreamType().toString())
                .register(meterRegistry);

        // build transaction latency metrics
        ImmutableMap.Builder<Integer, Timer> latencyMetricsBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<Integer, DistributionSummary> sizeMetricsBuilder = ImmutableMap.builder();

        for (TransactionTypeEnum type : TransactionTypeEnum.values()) {
            Timer timer = Timer.builder("hedera.mirror.transaction.latency")
                    .description("The difference in ms between the time consensus was achieved and the mirror node " +
                            "processed the transaction")
                    .tag("type", type.toString())
                    .register(meterRegistry);
            latencyMetricsBuilder.put(type.getProtoId(), timer);

            DistributionSummary distributionSummary = DistributionSummary.builder("hedera.mirror.transaction.size")
                    .description("The size of the transaction in bytes")
                    .baseUnit("bytes")
                    .tag("type", type.toString())
                    .register(meterRegistry);
            sizeMetricsBuilder.put(type.getProtoId(), distributionSummary);
        }

        latencyMetrics = latencyMetricsBuilder.build();
        sizeMetrics = sizeMetricsBuilder.build();
        unknownLatencyMetric = latencyMetrics.get(TransactionTypeEnum.UNKNOWN);
        unknownSizeMetric = sizeMetrics.get(TransactionTypeEnum.UNKNOWN);
    }

    /**
     * Given a stream file data representing an rcd file from the service parse record items and persist changes
     *
     * @param streamFileData containing information about file to be processed
     */
    @Override
    @Transactional
    public void parse(StreamFileData streamFileData) {
        Instant startTime = Instant.now();

        recordStreamFileListener.onStart(streamFileData);
        String expectedPrevFileHash =
                applicationStatusRepository.findByStatusCode(ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH);
        AtomicInteger counter = new AtomicInteger(0);
        boolean success = false;
        try {
            Stopwatch stopwatch = Stopwatch.createStarted();
            RecordFile recordFile = Utility.parseRecordFile(
                    streamFileData.getFilename(), expectedPrevFileHash,
                    parserProperties.getMirrorProperties().getVerifyHashAfter(),
                    recordItem -> {
                        counter.incrementAndGet();
                        processRecordItem(recordItem);
                    });
            log.info("Time to parse record file: {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
            recordFile.setLoadStart(startTime.getEpochSecond());
            recordFile.setLoadEnd(Instant.now().getEpochSecond());
            recordStreamFileListener.onEnd(recordFile);
            applicationStatusRepository.updateStatusValue(
                    ApplicationStatusCode.LAST_PROCESSED_RECORD_HASH, recordFile.getFileHash());

            recordParserLatencyMetric(recordFile);
            success = true;
        } catch (DuplicateFileException ex) {
            log.warn("Skipping file {}", ex);
        } catch (Exception ex) {
            recordStreamFileListener.onError();
            throw ex;
        } finally {
            var elapsedTimeMillis = Duration.between(startTime, Instant.now()).toMillis();
            var rate = elapsedTimeMillis > 0 ? (int) (1000.0 * counter.get() / elapsedTimeMillis) : 0;
            log.info("Finished parsing {} transactions from record file {} in {}ms ({}/s)",
                    counter, streamFileData.getFilename(), elapsedTimeMillis, rate);
            parseDurationMetrics.get(success).record(elapsedTimeMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void processRecordItem(RecordItem recordItem) {
        if (log.isTraceEnabled()) {
            log.trace("Transaction = {}, Record = {}",
                    Utility.printProtoMessage(recordItem.getTransaction()),
                    Utility.printProtoMessage(recordItem.getRecord()));
        } else if (log.isDebugEnabled()) {
            log.debug("Storing transaction with consensus timestamp {}", recordItem.getConsensusTimestamp());
        }
        recordItemListener.onItem(recordItem);

        sizeMetrics.getOrDefault(recordItem.getTransactionType(), unknownSizeMetric)
                .record(recordItem.getTransactionBytes().length);

        Instant consensusTimestamp = Utility.convertToInstant(recordItem.getRecord().getConsensusTimestamp());
        latencyMetrics.getOrDefault(recordItem.getTransactionType(), unknownLatencyMetric)
                .record(Duration.between(consensusTimestamp, Instant.now()));
    }

    private void recordParserLatencyMetric(RecordFile recordFile) {
        try {
            long recordFileLoadEndMillis = recordFile.getLoadEnd() * 1_000; // s -> ms
            long consensusEndMillis = recordFile.getConsensusEnd() / 1_000_000; // ns -> ms
            parseLatencyMetric
                    .record(recordFileLoadEndMillis - consensusEndMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("Error calculating duration between recordFileLoadEnd: '{}' and recordFileConsensusEnd: {}",
                    recordFile.getLoadEnd(), recordFile.getConsensusEnd());
        }
    }
}
