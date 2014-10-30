/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
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
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tsoclient;

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yahoo.omid.proto.TSOProto;
import com.yahoo.statemachine.StateMachine.*;

import org.apache.commons.configuration.Configuration;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.LengthFieldPrepender;
import org.jboss.netty.handler.codec.protobuf.ProtobufDecoder;
import org.jboss.netty.handler.codec.protobuf.ProtobufEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Communication endpoint for TSO clients.
 */
class TSOClientImpl extends TSOClient {
    private static final Logger LOG = LoggerFactory.getLogger(TSOClient.class);

    private ChannelFactory factory;
    private ClientBootstrap bootstrap;
    private final ScheduledExecutorService fsmExecutor;
    Fsm fsm;

    private final int requestTimeoutMs;
    private final int requestMaxRetries;
    private final int retryDelayMs; // ignored for now
    private final InetSocketAddress addr;
    private final MetricRegistry metrics;

    TSOClientImpl(Configuration conf, MetricRegistry metrics) {
        this.metrics = metrics;

        // Start client with Nb of active threads = 3 as maximum.
        int tsoExecutorThreads = conf.getInt(TSO_EXECUTOR_THREAD_NUM_CONFKEY, DEFAULT_TSO_EXECUTOR_THREAD_NUM);

        factory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(
                        new ThreadFactoryBuilder().setNameFormat("tsoclient-boss-%d").build()),
                Executors.newCachedThreadPool(
                        new ThreadFactoryBuilder().setNameFormat("tsoclient-worker-%d").build()), tsoExecutorThreads);
        // Create the bootstrap
        bootstrap = new ClientBootstrap(factory);

        String host = conf.getString(TSO_HOST_CONFKEY);
        int port = conf.getInt(TSO_PORT_CONFKEY, DEFAULT_TSO_PORT);
        requestTimeoutMs = conf.getInt(REQUEST_TIMEOUT_IN_MS_CONFKEY, DEFAULT_REQUEST_TIMEOUT_MS);
        requestMaxRetries = conf.getInt(REQUEST_MAX_RETRIES_CONFKEY, DEFAULT_TSO_MAX_REQUEST_RETRIES);
        retryDelayMs = conf.getInt(TSO_RETRY_DELAY_MS_CONFKEY, DEFAULT_TSO_RETRY_DELAY_MS);

        if (host == null) {
            throw new IllegalArgumentException("tso.host missing from configuration");
        }

        addr = new InetSocketAddress(host, port);

        fsmExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat("tsofsm-%d").build());
        fsm = new FsmImpl(fsmExecutor);
        fsm.setInitState(new DisconnectedState(fsm));

        ChannelPipeline pipeline = bootstrap.getPipeline();
        pipeline.addLast("lengthbaseddecoder",
                new LengthFieldBasedFrameDecoder(8 * 1024, 0, 4, 0, 4));
        pipeline.addLast("lengthprepender", new LengthFieldPrepender(4));
        pipeline.addLast("protobufdecoder",
                new ProtobufDecoder(TSOProto.Response.getDefaultInstance()));
        pipeline.addLast("protobufencoder", new ProtobufEncoder());
        pipeline.addLast("handler", new Handler(fsm));

        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("reuseAddress", true);
        bootstrap.setOption("connectTimeoutMillis", 100);
    }

    InetSocketAddress getAddress() {
        return addr;
    }

    public TSOFuture<Long> getNewStartTimestamp() {
        TSOProto.Request.Builder builder = TSOProto.Request.newBuilder();
        TSOProto.TimestampRequest.Builder tsreqBuilder = TSOProto.TimestampRequest.newBuilder();
        builder.setTimestampRequest(tsreqBuilder.build());
        RequestEvent request = new RequestEvent(builder.build(), requestMaxRetries);
        fsm.sendEvent(request);
        return new ForwardingTSOFuture<Long>(request);
    }

    public TSOFuture<Long> commit(long transactionId, Set<? extends CellId> cells) {
        TSOProto.Request.Builder builder = TSOProto.Request.newBuilder();
        TSOProto.CommitRequest.Builder commitbuilder = TSOProto.CommitRequest.newBuilder();
        commitbuilder.setStartTimestamp(transactionId);
        for (CellId cell : cells) {
            commitbuilder.addCellId(cell.getCellId());
        }
        builder.setCommitRequest(commitbuilder.build());
        RequestEvent request = new RequestEvent(builder.build(), requestMaxRetries);
        fsm.sendEvent(request);
        return new ForwardingTSOFuture<Long>(request);
    }

    public TSOFuture<Void> close() {
        CloseEvent closeEvent = new CloseEvent();
        fsm.sendEvent(closeEvent);
        closeEvent.addListener(new Runnable() {
            @Override
            public void run() {
                fsmExecutor.shutdown();
            }
        }, fsmExecutor);
        return new ForwardingTSOFuture<Void>(closeEvent);
    }

    private static class ParamEvent<T> implements Event {
        final T param;

        ParamEvent(T param) {
            this.param = param;
        }

        T getParam() {
            return param;
        }
    }

    private static class ErrorEvent extends ParamEvent<Throwable> {
        ErrorEvent(Throwable t) {
            super(t);
        }
    }

    private static class ConnectedEvent extends ParamEvent<Channel> {
        ConnectedEvent(Channel c) {
            super(c);
        }
    }

    private static class UserEvent<T> extends AbstractFuture<T>
        implements DeferrableEvent {
        public void success(T value) {
            set(value);
        }

        @Override
        public void error(Throwable t) {
            setException(t);
        }
    }

    private static class CloseEvent extends UserEvent<Void> {
    }

    private static class ChannelClosedEvent implements Event {
    }

    private static class TimestampRequestTimeoutEvent implements Event {
    }

    private static class CommitRequestTimeoutEvent implements Event {
        final long startTimestamp;

        public CommitRequestTimeoutEvent(long startTimestamp) {
            this.startTimestamp = startTimestamp;
        }

        public long getStartTimestamp() {
            return startTimestamp;
        }
    }

    private static class RequestEvent extends UserEvent<Long> {
        TSOProto.Request req;
        int retriesLeft;

        RequestEvent(TSOProto.Request req, int retriesLeft) {
            this.req = req;
            this.retriesLeft = retriesLeft;
        }

        TSOProto.Request getRequest() {
            return req;
        }

        void setRequest(TSOProto.Request request) {
            this.req = request;
        }

        int getRetriesLeft() {
            return retriesLeft;
        }

        void decrementRetries() {
            retriesLeft--;
        }

    }

    private static class ResponseEvent extends ParamEvent<TSOProto.Response> {
        ResponseEvent(TSOProto.Response r) {
            super(r);
        }
    }

    private class BaseState extends State {
        BaseState(Fsm fsm) {
            super(fsm);
        }

        public State handleEvent(Event e) {
            LOG.error("Unhandled event {} while in state {}", e, this.getClass().getName());
            return this;
        }
    }

    private class DisconnectedState extends BaseState {
        DisconnectedState(Fsm fsm) {
            super(fsm);
        }

        public State handleEvent(RequestEvent e) {
            fsm.deferEvent(e);
            bootstrap.connect(getAddress()).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future)
                            throws Exception {
                        if (!future.isSuccess()) {
                            fsm.sendEvent(new ErrorEvent(future.getCause()));
                        }
                    }
                });
            return new ConnectingState(fsm);
        }

        public State handleEvent(CloseEvent e) {
            factory.releaseExternalResources();
            e.success(null);
            return this;
        }
    }

    private class ConnectingState extends BaseState {
        ConnectingState(Fsm fsm) {
            super(fsm);
        }

        public State handleEvent(UserEvent e) {
            fsm.deferEvent(e);
            return this;
        }

        public State handleEvent(ConnectedEvent e) {
            return new ConnectedState(fsm, e.getParam());
        }

        public State handleEvent(ErrorEvent e) {
            LOG.error("Error connecting", ((ErrorEvent) e).getParam());
            return new DisconnectedState(fsm);
        }
    }

    class ConnectedState extends BaseState {
        final Queue<RequestEvent> timestampRequests;
        final Map<Long, RequestEvent> commitRequests;
        final Channel channel;

        final ScheduledExecutorService timeoutExecutor = Executors
                .newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("tso-client-timeout")
                        .build());

        ConnectedState(Fsm fsm, Channel channel) {
            super(fsm);
            this.channel = channel;
            timestampRequests = new ArrayDeque<RequestEvent>();
            commitRequests = new HashMap<Long, RequestEvent>();
        }

        private void sendRequest(final Fsm fsm, RequestEvent request) {
            TSOProto.Request req = request.getRequest();

            final Event timeoutEvent;
            if (req.hasTimestampRequest()) {
                timestampRequests.add(request);
                timeoutEvent = new TimestampRequestTimeoutEvent();
            } else if (req.hasCommitRequest()) {
                TSOProto.CommitRequest commitReq = req.getCommitRequest();
                commitRequests.put(commitReq.getStartTimestamp(), request);
                timeoutEvent = new CommitRequestTimeoutEvent(commitReq.getStartTimestamp());
            } else {
                timeoutEvent = null;
                request.error(new IllegalArgumentException("Unknown request type"));
                return;
            }
            ChannelFuture f = channel.write(req);
            if (requestTimeoutMs > 0) {
                timeoutExecutor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        fsm.sendEvent(timeoutEvent);
                    }
                }, requestTimeoutMs, TimeUnit.MILLISECONDS);
            }
            f.addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) {
                    if (!future.isSuccess()) {
                        fsm.sendEvent(new ErrorEvent(future.getCause()));
                    }
                }
            });
        }

        private void handleResponse(ResponseEvent response) {
            TSOProto.Response resp = response.getParam();
            if (resp.hasTimestampResponse()) {
                if (timestampRequests.size() == 0) {
                    LOG.debug("Received timestamp response when no requests outstanding");
                    return;
                }
                RequestEvent e = timestampRequests.remove();
                e.success(resp.getTimestampResponse().getStartTimestamp());
            } else if (resp.hasCommitResponse()) {
                long startTimestamp = resp.getCommitResponse().getStartTimestamp();
                RequestEvent e = commitRequests.remove(startTimestamp);
                if (e == null) {
                    LOG.debug("Received commit response for request that doesn't exist."
                            + " Start timestamp: {}", startTimestamp);
                    return;
                }
                if (resp.getCommitResponse().getAborted()) {
                    e.error(new AbortException());
                } else {
                    e.success(resp.getCommitResponse().getCommitTimestamp());
                }
            }
        }

        public State handleEvent(TimestampRequestTimeoutEvent e) {
            if (!timestampRequests.isEmpty()) {
                queueRetryOrError(fsm, timestampRequests.remove());
            }
            return this;
        }

        public State handleEvent(CommitRequestTimeoutEvent e) {
            long startTimestamp = e.getStartTimestamp();
            if (commitRequests.containsKey(startTimestamp)) {
                queueRetryOrError(fsm, commitRequests.remove(startTimestamp));
            }
            return this;
        }

        public State handleEvent(CloseEvent e) {
            timeoutExecutor.shutdownNow();
            closeChannelAndErrorRequests();
            fsm.deferEvent(e);
            return new ClosingState(fsm);
        }

        public State handleEvent(RequestEvent e) {
            sendRequest(fsm, e);
            return this;
        }

        public State handleEvent(ResponseEvent e) {
            handleResponse(e);
            return this;
        }

        public State handleEvent(ErrorEvent e) {
            timeoutExecutor.shutdownNow();
            handleError(fsm);
            return new ClosingState(fsm);
        }

        private void handleError(Fsm fsm) {
            while (timestampRequests.size() > 0) {
                queueRetryOrError(fsm, timestampRequests.remove());
            }
            Iterator<Map.Entry<Long, RequestEvent>> iter = commitRequests.entrySet().iterator();
            while (iter.hasNext()) {
                RequestEvent e = iter.next().getValue();
                queueRetryOrError(fsm, e);
                iter.remove();
            }
            channel.close();
        }

        private void queueRetryOrError(Fsm fsm, RequestEvent e) {
            if (e.getRetriesLeft() > 0) {
                e.decrementRetries();
                if (e.getRequest().hasCommitRequest()) {
                    TSOProto.CommitRequest commitRequest = e.getRequest().getCommitRequest();
                    if (!commitRequest.getIsRetry()) { // Create a new retry for the commit request
                        TSOProto.Request.Builder builder = TSOProto.Request.newBuilder();
                        TSOProto.CommitRequest.Builder commitBuilder = TSOProto.CommitRequest.newBuilder();
                        commitBuilder.mergeFrom(commitRequest);
                        commitBuilder.setIsRetry(true);
                        builder.setCommitRequest(commitBuilder.build());
                        e.setRequest(builder.build());
                    }
                }
                fsm.sendEvent(e);
            } else {
                e.error(new ServiceUnavailableException("Number of retries exceeded. This API request failed permanently"));
            }
        }

        private void closeChannelAndErrorRequests() {
            channel.close();
            for (RequestEvent r : timestampRequests) {
                r.error(new ClosingException());
            }
            for (RequestEvent r : commitRequests.values()) {
                r.error(new ClosingException());
            }
        }
    }

    private class ClosingState extends BaseState {
        ClosingState(Fsm fsm) {
            super(fsm);
        }

        public State handleEvent(TimestampRequestTimeoutEvent e) {
            // Ignored. They will be retried or errored
            return this;
        }

        public State handleEvent(CommitRequestTimeoutEvent e) {
            // Ignored. They will be retried or errored
            return this;
        }

        public State handleEvent(ErrorEvent e) {
            // Ignored. They will be retried or errored
            return this;
        }

        public State handleEvent(ResponseEvent e) {
            // Ignored. They will be retried or errored
            return this;
        }

        public State handleEvent(UserEvent e) {
            fsm.deferEvent(e);
            return this;
        }

        public State handleEvent(ChannelClosedEvent e) {
            return new DisconnectedState(fsm);
        }
    }

    private class Handler extends SimpleChannelHandler {
        private Fsm fsm;

        Handler(Fsm fsm) {
            this.fsm = fsm;
        }

        @Override
        public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
            fsm.sendEvent(new ConnectedEvent(e.getChannel()));
        }

        @Override
        public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e)
                throws Exception {
            fsm.sendEvent(new ErrorEvent(new ConnectionException()));
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
                throws Exception {
            fsm.sendEvent(new ChannelClosedEvent());
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            if (e.getMessage() instanceof TSOProto.Response) {
                fsm.sendEvent(new ResponseEvent((TSOProto.Response) e.getMessage()));
            } else {
                LOG.warn("Received unknown message", e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            LOG.error("Error on channel {}", ctx.getChannel(), e.getCause());
            fsm.sendEvent(new ErrorEvent(e.getCause()));
        }
    }

}
