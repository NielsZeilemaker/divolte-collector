/*
 * Copyright 2014 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server;

import static io.divolte.server.BaseEventHandler.*;
import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.*;
import io.divolte.record.DefaultEventRecord;
import io.divolte.server.CookieValues.CookieValue;
import io.divolte.server.hdfs.HdfsFlusher;
import io.divolte.server.hdfs.HdfsFlushingPool;
import io.divolte.server.ip2geo.LookupService;
import io.divolte.server.kafka.KafkaFlusher;
import io.divolte.server.kafka.KafkaFlushingPool;
import io.divolte.server.processing.ItemProcessor;
import io.divolte.server.processing.ProcessingPool;
import io.divolte.server.recordmapping.ConfigRecordMapper;
import io.divolte.server.recordmapping.DslRecordMapper;
import io.divolte.server.recordmapping.DslRecordMapping;
import io.divolte.server.recordmapping.RecordMapper;
import io.divolte.server.recordmapping.UserAgentParserAndCache;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.typesafe.config.Config;

@ParametersAreNonnullByDefault
public final class IncomingRequestProcessor implements ItemProcessor<HttpServerExchange> {
    private final static Logger logger = LoggerFactory.getLogger(IncomingRequestProcessor.class);

    public final static AttachmentKey<Boolean> CORRUPT_EVENT_KEY = AttachmentKey.create(Boolean.class);
    public final static AttachmentKey<Boolean> DUPLICATE_EVENT_KEY = AttachmentKey.create(Boolean.class);

    @Nullable
    private final ProcessingPool<KafkaFlusher, AvroRecordBuffer> kafkaFlushingPool;
    @Nullable
    private final ProcessingPool<HdfsFlusher, AvroRecordBuffer> hdfsFlushingPool;

    private final IncomingRequestListener listener;

    private final RecordMapper mapper;

    private final boolean keepCorrupted;

    private final ShortTermDuplicateMemory memory;
    private final boolean keepDuplicates;

    public IncomingRequestProcessor(final Config config,
                                    @Nullable final KafkaFlushingPool kafkaFlushingPool,
                                    @Nullable final HdfsFlushingPool hdfsFlushingPool,
                                    @Nullable final LookupService geoipLookupService,
                                    final Schema schema,
                                    final IncomingRequestListener listener) {

        this.kafkaFlushingPool = kafkaFlushingPool;
        this.hdfsFlushingPool = hdfsFlushingPool;
        this.listener = listener;

        keepCorrupted = !config.getBoolean("divolte.incoming_request_processor.discard_corrupted");

        memory = new ShortTermDuplicateMemory(config.getInt("divolte.incoming_request_processor.duplicate_memory_size"));
        keepDuplicates = !config.getBoolean("divolte.incoming_request_processor.discard_duplicates");

        if (config.hasPath("divolte.tracking.schema_mapping")) {
            final int version = config.getInt("divolte.tracking.schema_mapping.version");
            switch(version) {
            case 1:
                logger.info("Using configuration based schema mapping.");
                mapper = new ConfigRecordMapper(
                        Objects.requireNonNull(schema),
                        config,
                        Optional.ofNullable(geoipLookupService));
                break;
            case 2:
                logger.info("Using script based schema mapping.");
                mapper = new DslRecordMapper(
                        config,
                        Objects.requireNonNull(schema),
                        Optional.ofNullable(geoipLookupService));
                break;
            default:
                throw new RuntimeException("Unsupported schema mapping config version: " + version);
            }
        } else {
            logger.info("Using built in default schema mapping.");
            mapper = new DslRecordMapper(DefaultEventRecord.getClassSchema(), defaultRecordMapping(config));
        }
    }

    private DslRecordMapping defaultRecordMapping(final Config config) {
        final DslRecordMapping result = new DslRecordMapping(DefaultEventRecord.getClassSchema(), new UserAgentParserAndCache(config), Optional.empty());
        result.map("detectedCorruption", result.corrupt());
        result.map("detectedDuplicate", result.duplicate());
        result.map("firstInSession", result.firstInSession());
        result.map("timestamp", result.timestamp());
        result.map("remoteHost", result.remoteHost());
        result.map("referer", result.referer());
        result.map("location", result.location());
        result.map("viewportPixelWidth", result.viewportPixelWidth());
        result.map("viewportPixelHeight", result.viewportPixelHeight());
        result.map("screenPixelWidth", result.screenPixelWidth());
        result.map("screenPixelHeight", result.screenPixelHeight());
        result.map("partyId", result.partyId());
        result.map("sessionId", result.sessionId());
        result.map("pageViewId", result.pageViewId());
        result.map("eventType", result.eventType());
        result.map("userAgentString", result.userAgentString());
        result.map("userAgentName", result.userAgent().name());
        result.map("userAgentFamily", result.userAgent().family());
        result.map("userAgentVendor", result.userAgent().vendor());
        result.map("userAgentType", result.userAgent().type());
        result.map("userAgentVersion", result.userAgent().version());
        result.map("userAgentDeviceCategory", result.userAgent().deviceCategory());
        result.map("userAgentOsFamily", result.userAgent().osFamily());
        result.map("userAgentOsVersion", result.userAgent().osVersion());
        result.map("userAgentOsVendor", result.userAgent().osVendor());
        return result;
    }

    @Override
    public ProcessingDirective process(final HttpServerExchange exchange) {
        final boolean corrupt = !isRequestChecksumCorrect(exchange);
        exchange.putAttachment(CORRUPT_EVENT_KEY, corrupt);

        if (!corrupt || keepCorrupted) {
            final CookieValue party = exchange.getAttachment(PARTY_COOKIE_KEY);
            final CookieValue session = exchange.getAttachment(SESSION_COOKIE_KEY);
            final String pageView = exchange.getAttachment(PAGE_VIEW_ID_KEY);
            final String event = exchange.getAttachment(EVENT_ID_KEY);
            final Long requestStartTime = exchange.getAttachment(REQUEST_START_TIME_KEY);
            final Long cookieUtcOffset = exchange.getAttachment(COOKIE_UTC_OFFSET_KEY);

            /*
             * Note: we cannot use the actual query string here,
             * as the incoming request processor is agnostic of
             * that sort of thing. The request may have come from
             * an endpoint that doesn't require a query string,
             * but rather generates these IDs on the server side.
             */
            final boolean duplicate = memory.isProbableDuplicate(party.value, session.value, pageView, event);
            exchange.putAttachment(DUPLICATE_EVENT_KEY, duplicate);

            if (!duplicate || keepDuplicates) {
                final GenericRecord avroRecord = mapper.newRecordFromExchange(exchange);
                final AvroRecordBuffer avroBuffer = AvroRecordBuffer.fromRecord(
                        party,
                        session,
                        requestStartTime,
                        cookieUtcOffset,
                        avroRecord);
                doProcess(exchange, avroRecord, avroBuffer);
            }
        }

        return CONTINUE;
    }

    private void doProcess(final HttpServerExchange exchange, final GenericRecord avroRecord, final AvroRecordBuffer avroBuffer) {
        listener.incomingRequest(exchange, avroBuffer, avroRecord);

        if (null != kafkaFlushingPool) {
            kafkaFlushingPool.enqueue(avroBuffer.getPartyId().value, avroBuffer);
        }
        if (null != hdfsFlushingPool) {
            hdfsFlushingPool.enqueue(avroBuffer.getPartyId().value, avroBuffer);
        }
    }

    private static final HashFunction CHECKSUM_HASH = Hashing.murmur3_32();

    private static boolean isRequestChecksumCorrect(final HttpServerExchange exchange) {
        // This is not intended to be robust against intentional tampering; it is intended to guard
        // against proxies and the like that may have truncated the request.

        return queryParamFromExchange(exchange, CHECKSUM_QUERY_PARAM)
                .map(ClientSideCookieEventHandler::tryParseBase36Long)
                .map((expectedChecksum) -> {
                    /*
                     * We could optimize this by calculating the checksum directly, instead of building up
                     * the intermediate string representation. For now the debug value of the string exceeds
                     * the benefits of going slightly faster.
                     */
                    final String canonicalRequestString = buildNormalizedChecksumString(exchange.getQueryParameters());
                    final int requestChecksum =
                            CHECKSUM_HASH.hashString(canonicalRequestString, StandardCharsets.UTF_8).asInt();
                    final boolean isRequestChecksumCorrect = expectedChecksum == requestChecksum;
                    if (!isRequestChecksumCorrect && logger.isDebugEnabled()) {
                        logger.debug("Checksum mismatch detected; expected {} but was {} for request string: {}",
                                Long.toString(expectedChecksum, 36),
                                Integer.toString(requestChecksum, 36),
                                canonicalRequestString);
                    }
                    return isRequestChecksumCorrect;
                })
                .orElse(false);
    }

    private static String buildNormalizedChecksumString(final Map<String,Deque<String>> queryParameters) {
        return buildNormalizedChecksumString(queryParameters instanceof SortedMap
                ? (SortedMap)queryParameters
                : new TreeMap<>(queryParameters));
    }

    private static String buildNormalizedChecksumString(final SortedMap<String,Deque<String>> queryParameters) {
        /*
         * Build up a canonical representation of the query parameters. The canonical order is:
         *  1) Sort the query parameters by key, preserving multiple values (and their order).
         *  2) The magic parameter containing the checksum is discarded.
         *  3) Build up a string. For each parameter:
         *     a) Append the parameter name, followed by a '='.
         *     b) Append each value of the parameter, followed by a ','.
         *     c) Append a ';'.
         *  This is designed to be unambiguous in the face of many edge cases.
         */
        final StringBuilder builder = new StringBuilder();
        queryParameters.forEach((name, values) -> {
            if (!CHECKSUM_QUERY_PARAM.equals(name)) {
                builder.append(name).append('=');
                values.forEach((value) -> builder.append(value).append(','));
                builder.append(';');
            }
        });
        return builder.toString();
    }
}
