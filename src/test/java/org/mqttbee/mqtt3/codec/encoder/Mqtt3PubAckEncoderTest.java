package org.mqttbee.mqtt3.codec.encoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mqttbee.mqtt3.message.puback.Mqtt3PubAckImpl;

import static org.junit.Assert.assertArrayEquals;

class Mqtt3PubAckEncoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new Mqtt3Encoder());
    }

    @AfterEach
    void tearDown() {
        channel.close();
    }

    @Test
    void encode() {
        int id = 1;
        final byte msb = (byte) (id >>> 8);
        final byte lsb = (byte) id;
        byte[] exspected = {0x40, 0x02, msb, lsb};
        Mqtt3PubAckImpl internal = new Mqtt3PubAckImpl(id);
        encode(exspected, internal);
    }

    @Test
    void encodedRemainingLength() {
    }

    private void encode(final byte[] expected, final Mqtt3PubAckImpl pubAck) {
        channel.writeOutbound(pubAck);
        final ByteBuf byteBuf = channel.readOutbound();
        final byte[] actual = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(actual);
        assertArrayEquals(expected, actual);
    }

}