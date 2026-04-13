package lora;

import java.nio.charset.StandardCharsets;

public class LoRaPacket {

    private final int recipientAddr;
    private final int channel;
    private final byte[] payload;
    private final int rssi;

    private LoRaPacket(Builder b) {
        this.recipientAddr = b.recipientAddr;
        this.channel = b.channel;
        this.payload = b.payload;
        this.rssi = 0;
    }

    public byte[] toBytes() {
        byte[] frame = new byte[3 + payload.length];
        frame[0] = (byte)(recipientAddr >> 8);
        frame[1] = (byte)(recipientAddr & 0xFF);
        frame[2] = (byte)(channel & 0xFF);
        System.arraycopy(payload, 0, frame, 3, payload.length);
        return frame;
    }

    private LoRaPacket(byte[] payload, int rssi) {
        this.recipientAddr = 0;
        this.channel = 0;
        this.payload = payload;
        this.rssi = rssi;
    }

    public static LoRaPacket fromBytes(byte[] raw, int len,
                                       boolean packetRssi) {
        if (len <= 0)
            return null;
        int payloadLen = len - (packetRssi ? 1 : 0);
        if (payloadLen < 0)
            return null;

        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, 0, payload, 0, payloadLen);

        int rssi = packetRssi ? -(256 - (raw[len - 1] & 0xFF)) : 0;

        return new LoRaPacket(payload, rssi);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LoRaPacket:\n");
        sb.append(String.format("Recipient : 0x%04X\n", recipientAddr));
        sb.append(String.format("Channel   : %d (%.3f MHz)\n", channel,
                                850.125 + channel));
        sb.append(
            String.format("Payload   : %s\n",
                          new String(payload, StandardCharsets.US_ASCII)));
        sb.append("Raw bytes : ");
        for (byte b : toBytes())
            sb.append(String.format("%02X ", b & 0xFF));
        
        sb.append("\n");
        if (rssi != 0)
            sb.append(String.format("RSSI      : %d dBm", rssi));
        return sb.toString().trim();
    }

    public static class Builder {
        private int recipientAddr = 0xFFFF;
        private int channel = 18;
        private byte[] payload = new byte[0];

        public Builder recipient(int addr) {
            this.recipientAddr = addr & 0xFFFF;
            return this;
        }

        public Builder channel(int channel) {
            this.channel = channel & 0xFF;
            return this;
        }

        public Builder payload(byte[] data) {
            this.payload = data;
            return this;
        }

        public Builder payload(String text) {
            this.payload = text.getBytes(StandardCharsets.US_ASCII);
            return this;
        }

        public LoRaPacket build() { return new LoRaPacket(this); }
    }
}
