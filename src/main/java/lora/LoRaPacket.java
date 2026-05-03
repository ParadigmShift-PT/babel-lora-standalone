package lora;

import java.nio.charset.StandardCharsets;

public class LoRaPacket {

    private final int destAddr;
    private final int rssi;

    public int getRssi() { return rssi; }

    public int getDestAddr() { return destAddr; }

    public int getChannel() { return channel; }

    public int getOriginAddr() { return originAddr; }

    public int getPrevHopAddr() { return prevHopAddr; }

    public byte[] getPayload() { return payload; }

    private final int channel;
    private final int originAddr;
    private final int prevHopAddr;
    private final byte[] payload;

    private LoRaPacket(Builder b) {
        this.destAddr = b.destAddr;
        this.channel = b.channel;
        this.originAddr = b.originAddr;
        this.prevHopAddr = b.prevHopAddr;
        this.payload = b.payload;
        this.rssi = b.rssi;
    }

    public byte[] toBytes() {
        // typedef struct __attribute__((packed)) {
        // uint16_t dest_addr;
        // uint8_t channel;
        // uint16_t origin_addr;
        // uint16_t prev_hop_addr;
        // uint8_t payload_len;
        // uint8_t payload[];
        // } lora_frame_t;

        final int headerLen = 8;
        byte[] frame = new byte[headerLen + payload.length];

        frame[0] = (byte)(destAddr & 0xFF);
        frame[1] = (byte)((destAddr >> 8) & 0xFF);
        frame[2] = (byte)(channel & 0xFF);
        frame[3] = (byte)(originAddr & 0xFF);
        frame[4] = (byte)((originAddr >> 8) & 0xFF);
        frame[5] = (byte)(prevHopAddr & 0xFF);
        frame[6] = (byte)((prevHopAddr >> 8) & 0xFF);
        frame[7] = (byte)(payload.length & 0xFF);

        System.arraycopy(payload, 0, frame, headerLen, payload.length);
        return frame;
    }

    public static LoRaPacket fromBytes(byte[] raw, int len, boolean packetRssi) {
    final int headerLen = 8;
    if (raw == null || len < headerLen) {
        return null;
    }

    int frameLen = packetRssi ? len - 1 : len;
    if (frameLen < headerLen) {
        return null;
    }

    int destAddr    = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
    int channel     =  raw[2] & 0xFF;
    int originAddr  = (raw[3] & 0xFF) | ((raw[4] & 0xFF) << 8);
    int prevHopAddr = (raw[5] & 0xFF) | ((raw[6] & 0xFF) << 8);
    int payloadLen  =  raw[7] & 0xFF;

    // if (headerLen + payloadLen > frameLen) {
    //     return null;
    // }

    byte[] payload = new byte[payloadLen];
    System.arraycopy(raw, headerLen, payload, 0, payloadLen);

    int rssi = packetRssi ? (raw[len - 1] & 0xFF) - 256 : 0;

    return new Builder()
            .destination(destAddr)
            .channel(channel)
            .origin(originAddr)
            .previousHop(prevHopAddr)
            .payload(payload)
            .rssi(rssi)
            .build();
}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LoRaPacket:\n");
        sb.append(String.format("Destination : 0x%04X\n", destAddr));
        sb.append(String.format("Channel   : %d (%.3f MHz)\n", channel,
                                850.125 + channel));
        sb.append(String.format("Origin : 0x%04X\n", originAddr));
        sb.append(String.format("Previous hop : 0x%04X\n", prevHopAddr));
        sb.append(
            String.format("Payload   : %s\n",
                          new String(payload, StandardCharsets.US_ASCII)));
        sb.append("Raw bytes : ");
        for (byte b : toBytes())
            sb.append(String.format("%02X ", b & 0xFF));

        sb.append(String.format("RSSI : %d dBm\n", rssi));

        return sb.toString().trim();
    }

    public static class Builder {
        // default to broadcat
        private int destAddr = 0xFFFF;
        // on channel 18, i.e. 868MHz
        private int channel = 18;
        private int originAddr;
        private int prevHopAddr;
        private int rssi = 0;
        private byte[] payload = new byte[0];

        public Builder destination(int addr) {
            this.destAddr = addr & 0xFFFF;
            return this;
        }

        public Builder origin(int addr) {
            this.originAddr = addr & 0xFFFF;
            return this;
        }

        public Builder previousHop(int addr) {
            this.prevHopAddr = addr & 0xFFFF;
            return this;
        }

        public Builder channel(int channel) {
            this.channel = channel & 0xFF;
            return this;
        }

        public Builder payload(byte[] data) {
            this.payload = data == null ? new byte[0] : data.clone();
            return this;
        }

        public Builder payload(String text) {
            this.payload = text.getBytes(StandardCharsets.US_ASCII);
            return this;
        }

        public Builder rssi(int rssi) {
            this.rssi = rssi;
            return this;
        }

        public LoRaPacket build() { return new LoRaPacket(this); }
    }
}
