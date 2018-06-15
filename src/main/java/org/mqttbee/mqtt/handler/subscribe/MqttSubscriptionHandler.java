/*
 * Copyright 2018 The MQTT Bee project
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
 *
 */

package org.mqttbee.mqtt.handler.subscribe;

import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.mqttbee.annotations.NotNull;
import org.mqttbee.api.mqtt.mqtt5.exceptions.Mqtt5MessageException;
import org.mqttbee.api.mqtt.mqtt5.message.Mqtt5ReasonCode;
import org.mqttbee.api.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import org.mqttbee.api.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import org.mqttbee.api.mqtt.mqtt5.message.unsubscribe.unsuback.Mqtt5UnsubAck;
import org.mqttbee.mqtt.MqttClientConnectionData;
import org.mqttbee.mqtt.MqttClientData;
import org.mqttbee.mqtt.MqttServerConnectionData;
import org.mqttbee.mqtt.handler.disconnect.MqttDisconnectUtil;
import org.mqttbee.mqtt.handler.publish.MqttIncomingPublishFlows;
import org.mqttbee.mqtt.handler.publish.MqttOutgoingQoSHandler;
import org.mqttbee.mqtt.handler.publish.MqttSubscriptionFlow;
import org.mqttbee.mqtt.handler.subscribe.MqttSubscribeWithFlow.MqttStatefulSubscribeWithFlow;
import org.mqttbee.mqtt.handler.subscribe.MqttUnsubscribeWithFlow.MqttStatefulUnsubscribeWithFlow;
import org.mqttbee.mqtt.ioc.ChannelScope;
import org.mqttbee.mqtt.message.subscribe.suback.MqttSubAck;
import org.mqttbee.mqtt.message.unsubscribe.unsuback.MqttUnsubAck;
import org.mqttbee.mqtt.message.unsubscribe.unsuback.mqtt3.Mqtt3UnsubAckView;
import org.mqttbee.rx.SingleFlow;
import org.mqttbee.util.Ranges;
import org.mqttbee.util.collections.IntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.LinkedList;

/**
 * @author Silvio Giebl
 */
@ChannelScope
public class MqttSubscriptionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqttSubscriptionHandler.class);

    public static final String NAME = "subscription";
    public static final int MAX_SUB_PENDING = 10; // TODO configurable

    private final MqttIncomingPublishFlows subscriptionFlows;
    private final Ranges packetIdentifiers;
    private final Ranges subscriptionIdentifiers;
    private final IntMap<MqttStatefulSubscribeWithFlow> subscribes;
    private final IntMap<MqttStatefulUnsubscribeWithFlow> unsubscribes;
    private final LinkedList<Object> queued;
    private int pending;

    private ChannelHandlerContext ctx; // TODO temp

    @Inject
    MqttSubscriptionHandler(final MqttIncomingPublishFlows subscriptionFlows, final MqttClientData clientData) {
        this.subscriptionFlows = subscriptionFlows;

        final MqttClientConnectionData clientConnectionData = clientData.getRawClientConnectionData();
        assert clientConnectionData != null;
        final MqttServerConnectionData serverConnectionData = clientData.getRawServerConnectionData();
        assert serverConnectionData != null;

        final int minPacketIdentifier =
                MqttOutgoingQoSHandler.getPubReceiveMaximum(serverConnectionData.getReceiveMaximum()) + 1;
        final int maxPacketIdentifier = minPacketIdentifier + MAX_SUB_PENDING - 1;
        packetIdentifiers = new Ranges(minPacketIdentifier, maxPacketIdentifier);
        subscriptionIdentifiers = new Ranges(1, clientConnectionData.getSubscriptionIdentifierMaximum());
        subscribes = new IntMap<>(minPacketIdentifier, maxPacketIdentifier);
        unsubscribes = new IntMap<>(minPacketIdentifier, maxPacketIdentifier);
        queued = new LinkedList<>();
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.ctx = ctx; // TODO temp
    }

    public void subscribe(@NotNull final MqttSubscribeWithFlow subscribeWithFlow) {
        ctx.executor().execute(() -> handleSubscribe(ctx, subscribeWithFlow)); // TODO temp
    }

    private void handleSubscribe(
            @NotNull final ChannelHandlerContext ctx, @NotNull final MqttSubscribeWithFlow subscribeWithFlow) {

        if (pending == MAX_SUB_PENDING) {
            queued.offer(subscribeWithFlow);
            return;
        }

        final int packetIdentifier = packetIdentifiers.getId();
        if (packetIdentifier == -1) {
            // TODO must not happen
            return;
        }
        writeSubscribe(ctx, subscribeWithFlow, packetIdentifier);
    }

    private void writeSubscribe(
            @NotNull final ChannelHandlerContext ctx, @NotNull final MqttSubscribeWithFlow subscribeWithFlow,
            final int packetIdentifier) {

        final MqttStatefulSubscribeWithFlow statefulSubscribeWithFlow =
                subscribeWithFlow.createStateful(packetIdentifier, subscriptionIdentifiers.getId());
        subscribes.put(packetIdentifier, statefulSubscribeWithFlow);
        pending++;
        ctx.writeAndFlush(statefulSubscribeWithFlow.getSubscribe()).addListener(future -> {
            if (!future.isSuccess()) {
                statefulSubscribeWithFlow.getSubAckFlow().onError(future.cause());
                handleComplete(ctx, packetIdentifier);
            }
        });
    }

    public void unsubscribe(@NotNull final MqttUnsubscribeWithFlow unsubscribeWithFlow) {
        ctx.executor().execute(() -> handleUnsubscribe(ctx, unsubscribeWithFlow)); // TODO temp
    }

    private void handleUnsubscribe(
            @NotNull final ChannelHandlerContext ctx, @NotNull final MqttUnsubscribeWithFlow unsubscribeWithFlow) {

        if (pending == MAX_SUB_PENDING) {
            queued.offer(unsubscribeWithFlow);
            return;
        }

        final int packetIdentifier = packetIdentifiers.getId();
        if (packetIdentifier == -1) {
            // TODO must not happen
            return;
        }
        writeUnsubscribe(ctx, unsubscribeWithFlow, packetIdentifier);
    }

    private void writeUnsubscribe(
            @NotNull final ChannelHandlerContext ctx, @NotNull final MqttUnsubscribeWithFlow unsubscribeWithFlow,
            final int packetIdentifier) {

        final MqttStatefulUnsubscribeWithFlow statefulUnsubscribeWithFlow =
                unsubscribeWithFlow.createStateful(packetIdentifier);
        unsubscribes.put(packetIdentifier, statefulUnsubscribeWithFlow);
        pending++;
        ctx.writeAndFlush(statefulUnsubscribeWithFlow.getUnsubscribe()).addListener(future -> {
            if (!future.isSuccess()) {
                statefulUnsubscribeWithFlow.getUnsubAckFlow().onError(future.cause());
                handleComplete(ctx, packetIdentifier);
            }
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof MqttSubAck) {
            handleSubAck(ctx, (MqttSubAck) msg);
        } else if (msg instanceof MqttUnsubAck) {
            handleUnsubAck(ctx, (MqttUnsubAck) msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void handleSubAck(@NotNull final ChannelHandlerContext ctx, @NotNull final MqttSubAck subAck) {
        final int packetIdentifier = subAck.getPacketIdentifier();
        final MqttStatefulSubscribeWithFlow statefulSubscribeWithFlow = subscribes.remove(packetIdentifier);

        if (statefulSubscribeWithFlow == null) {
            MqttDisconnectUtil.disconnect(
                    ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR, "unknown packet identifier for SUBACK");
            return;
        }

        // TODO validate reason code count

        final SingleFlow<Mqtt5SubAck> subAckFlow = statefulSubscribeWithFlow.getSubAckFlow();
        if (allErrorCodes(subAck.getReasonCodes())) {
            if (!subAckFlow.isCancelled()) {
                subAckFlow.onError(new Mqtt5MessageException(subAck, "SUBACK contains only Error Codes"));
            } else {
                LOGGER.warn("SUBACK contains only Error Codes but the SubAckFlow has been cancelled.");
            }
        } else {
            if (!subAckFlow.isCancelled()) {
                subAckFlow.onSuccess(subAck);
            }
            final MqttSubscriptionFlow subscriptionFlow = statefulSubscribeWithFlow.getSubscriptionFlow();
            if ((subscriptionFlow != null) && !subscriptionFlow.isCancelled()) {
                subscriptionFlows.subscribe(statefulSubscribeWithFlow.getSubscribe(), subAck, subscriptionFlow);
            }
        }

        handleComplete(ctx, packetIdentifier);
    }

    private void handleUnsubAck(@NotNull final ChannelHandlerContext ctx, @NotNull final MqttUnsubAck unsubAck) {
        final int packetIdentifier = unsubAck.getPacketIdentifier();
        final MqttStatefulUnsubscribeWithFlow statefulUnsubscribeWithFlow = unsubscribes.remove(packetIdentifier);

        if (statefulUnsubscribeWithFlow == null) {
            MqttDisconnectUtil.disconnect(
                    ctx.channel(), Mqtt5DisconnectReasonCode.PROTOCOL_ERROR, "unknown packet identifier for UNSUBACK");
            return;
        }

        // TODO validate reason code count

        final SingleFlow<Mqtt5UnsubAck> unsubAckFlow = statefulUnsubscribeWithFlow.getUnsubAckFlow();
        if (allErrorCodes(unsubAck.getReasonCodes())) {
            if (!unsubAckFlow.isCancelled()) {
                unsubAckFlow.onError(new Mqtt5MessageException(unsubAck, "UNSUBACK contains only Error Codes"));
            } else {
                LOGGER.warn("UNSUBACK contains only Error Codes but the UnsubAckFlow has been cancelled.");
            }
        } else {
            if (!unsubAckFlow.isCancelled()) {
                unsubAckFlow.onSuccess(unsubAck);
            }
            subscriptionFlows.unsubscribe(statefulUnsubscribeWithFlow.getUnsubscribe(), unsubAck);
        }

        handleComplete(ctx, packetIdentifier);
    }

    private void handleComplete(@NotNull final ChannelHandlerContext ctx, final int packetIdentifier) {
        pending--;
        final Object subscribeOrUnsubscribe = queued.poll();
        if (subscribeOrUnsubscribe == null) {
            packetIdentifiers.returnId(packetIdentifier);
        } else {
            if (subscribeOrUnsubscribe instanceof MqttSubscribeWithFlow) {
                writeSubscribe(ctx, (MqttSubscribeWithFlow) subscribeOrUnsubscribe, packetIdentifier);
            } else {
                writeUnsubscribe(ctx, (MqttUnsubscribeWithFlow) subscribeOrUnsubscribe, packetIdentifier);
            }
        }
    }

    private static boolean allErrorCodes(@NotNull final ImmutableList<? extends Mqtt5ReasonCode> reasonCodes) {
        for (final Mqtt5ReasonCode reasonCode : reasonCodes) {
            if (!reasonCode.isError()) {
                return false;
            }
        }
        return (reasonCodes != Mqtt3UnsubAckView.REASON_CODES_ALL_SUCCESS);
    }

}
