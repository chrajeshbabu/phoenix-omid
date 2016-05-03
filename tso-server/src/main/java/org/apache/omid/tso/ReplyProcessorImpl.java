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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.omid.tso;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import org.apache.omid.metrics.Meter;
import org.apache.omid.metrics.MetricsRegistry;
import org.apache.omid.proto.TSOProto;

import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import static com.codahale.metrics.MetricRegistry.name;

class ReplyProcessorImpl implements EventHandler<ReplyProcessorImpl.ReplyBatchEvent>, ReplyProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ReplyProcessorImpl.class);

    private static final int NO_ORDER = (-1);

    private final RingBuffer<ReplyBatchEvent> replyRing;

    private AtomicLong nextIDToHandle = new AtomicLong();

    private PriorityQueue<ReplyBatchEvent> futureEvents;

    // Metrics
    private final Meter abortMeter;
    private final Meter commitMeter;
    private final Meter timestampMeter;

    @Inject
    ReplyProcessorImpl(MetricsRegistry metrics, Panicker panicker) {

        this.nextIDToHandle.set(0);

        this.replyRing = RingBuffer.createMultiProducer(ReplyBatchEvent.EVENT_FACTORY, 1 << 12, new BusySpinWaitStrategy());

        SequenceBarrier replySequenceBarrier = replyRing.newBarrier();
        BatchEventProcessor<ReplyBatchEvent> replyProcessor = new BatchEventProcessor<>(replyRing, replySequenceBarrier, this);
        replyProcessor.setExceptionHandler(new FatalExceptionHandler(panicker));

        replyRing.addGatingSequences(replyProcessor.getSequence());

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("reply-%d").build();
        ExecutorService replyExec = Executors.newSingleThreadExecutor(threadFactory);
        replyExec.submit(replyProcessor);

        this.futureEvents = new PriorityQueue<>(10, new Comparator<ReplyBatchEvent>() {
            public int compare(ReplyBatchEvent replyBatchEvent1, ReplyBatchEvent replyBatchEvent2) {
                return Long.compare(replyBatchEvent1.getBatchID(), replyBatchEvent2.getBatchID());
            }
        });

        this.abortMeter = metrics.meter(name("tso", "aborts"));
        this.commitMeter = metrics.meter(name("tso", "commits"));
        this.timestampMeter = metrics.meter(name("tso", "timestampAllocation"));

    }

    private void handleReplyBatchEvent(ReplyBatchEvent event) {

        String name;
        Batch batch = event.getBatch();
        for (int i=0; batch != null && i < batch.getNumEvents(); ++i) {
            PersistEvent localEvent = batch.getEvent(i);

            switch (localEvent.getType()) {
            case COMMIT:
                name = "commitReplyProcessor";
                localEvent.getMonCtx().timerStart(name);
                handleCommitResponse(localEvent.getStartTimestamp(), localEvent.getCommitTimestamp(), localEvent.getChannel());
                localEvent.getMonCtx().timerStop(name);
                 break;
            case ABORT:
                name = "abortReplyProcessor";
                localEvent.getMonCtx().timerStart(name);
                handleAbortResponse(localEvent.getStartTimestamp(), localEvent.getChannel());
                localEvent.getMonCtx().timerStop(name);
                break;
            case TIMESTAMP:
                name = "timestampReplyProcessor";
                localEvent.getMonCtx().timerStart(name);
                handleTimestampResponse(localEvent.getStartTimestamp(), localEvent.getChannel());
                localEvent.getMonCtx().timerStop(name);
                break;
            case LOW_WATERMARK:
                break;
            default:
                LOG.error("Unknown event {}", localEvent.getType());
                break;
            }
            localEvent.getMonCtx().publish();
        }

        if (batch != null) {
            batch.clear();
        }

    }

    private void processWaitingEvents() {

        while (!futureEvents.isEmpty() && futureEvents.peek().getBatchID() == nextIDToHandle.get()) {
            ReplyBatchEvent e = futureEvents.poll();
            handleReplyBatchEvent(e);
            nextIDToHandle.incrementAndGet();
        }

    }

    public void onEvent(ReplyBatchEvent event, long sequence, boolean endOfBatch) throws Exception {

        // Order of event's reply need to be guaranteed in order to preserve snapshot isolation.
        // This is done in order to present a scenario where a start id of N is returned
        // while commit smaller than still does not appear in the commit table.

        // If previous events were not processed yet (events contain smaller id)
        if (event.getBatchID() > nextIDToHandle.get()) {
            futureEvents.add(event);
            return;
         }

        handleReplyBatchEvent(event);

        if (event.getBatchID() == NO_ORDER) {
            return;
        }

        nextIDToHandle.incrementAndGet();

        // Process events that arrived before and kept in futureEvents.
        processWaitingEvents();

    }

    @Override
    public void manageResponsesBatch(long batchSequence, Batch batch) {

        long seq = replyRing.next();
        ReplyBatchEvent e = replyRing.get(seq);
        ReplyBatchEvent.makeReplyBatch(e, batch, batchSequence);
        replyRing.publish(seq);

    }

    // TODO This method can be removed if we return the responses from the retry processor
    @Override
    public void addAbort(Batch batch, long startTimestamp, Channel c, MonitoringContext context) {

        batch.addAbort(startTimestamp, true, c, context);
        manageResponsesBatch(NO_ORDER, batch);

    }

    // TODO This method can be removed if we return the responses from the retry processor
    @Override
    public void addCommit(Batch batch, long startTimestamp, long commitTimestamp, Channel c, MonitoringContext context) {

        batch.addCommit(startTimestamp, commitTimestamp, c, context);
        manageResponsesBatch(NO_ORDER, batch);

    }

    private void handleCommitResponse(long startTimestamp, long commitTimestamp, Channel c) {

        TSOProto.Response.Builder builder = TSOProto.Response.newBuilder();
        TSOProto.CommitResponse.Builder commitBuilder = TSOProto.CommitResponse.newBuilder();
        commitBuilder.setAborted(false)
                .setStartTimestamp(startTimestamp)
                .setCommitTimestamp(commitTimestamp);
        builder.setCommitResponse(commitBuilder.build());
        c.write(builder.build());

        commitMeter.mark();

    }

    private void handleAbortResponse(long startTimestamp, Channel c) {

        TSOProto.Response.Builder builder = TSOProto.Response.newBuilder();
        TSOProto.CommitResponse.Builder commitBuilder = TSOProto.CommitResponse.newBuilder();
        commitBuilder.setAborted(true);
        commitBuilder.setStartTimestamp(startTimestamp);
        builder.setCommitResponse(commitBuilder.build());
        c.write(builder.build());

        abortMeter.mark();

    }

    private void handleTimestampResponse(long startTimestamp, Channel c) {

        TSOProto.Response.Builder builder = TSOProto.Response.newBuilder();
        TSOProto.TimestampResponse.Builder respBuilder = TSOProto.TimestampResponse.newBuilder();
        respBuilder.setStartTimestamp(startTimestamp);
        builder.setTimestampResponse(respBuilder.build());
        c.write(builder.build());

        timestampMeter.mark();

    }

    final static class ReplyBatchEvent {

        private Batch batch;
        private long batchID;

        static void makeReplyBatch(ReplyBatchEvent e, Batch batch, long batchID) {
            e.batch = batch;
            e.batchID = batchID;
        }

        Batch getBatch() {
            return batch;
        }

        long getBatchID() {
            return batchID;
        }

        final static EventFactory<ReplyBatchEvent> EVENT_FACTORY = new EventFactory<ReplyBatchEvent>() {
            public ReplyBatchEvent newInstance() {
                return new ReplyBatchEvent();
            }
        };

    }

}
