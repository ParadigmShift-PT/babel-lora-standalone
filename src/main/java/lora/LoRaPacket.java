package lora;

import java.nio.charset.StandardCharsets;

public class LoRaPacket {

    private final int recipientAddr;
    private final int channel;
    private final byte[] payload;

    private LoRaPacket(Builder b) {
        this.recipientAddr = b.recipientAddr;
        this.channel = b.channel;
        this.payload = b.payload;
    }

    public byte[] toBytes() {
        byte[] frame = new byte[3 + payload.length];
        frame[0] = (byte)(recipientAddr >> 8);
        frame[1] = (byte)(recipientAddr & 0xFF);
        frame[2] = (byte)(channel & 0xFF);
        System.arraycopy(payload, 0, frame, 3, payload.length);
        return frame;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("recipient : 0x%04X\n", recipientAddr));
        sb.append(String.format("channel   : %d (%.3f MHz)\n", channel,
                                850.125 + channel));
        sb.append(
            String.format("payload   : %s\n",
                          new String(payload, StandardCharsets.US_ASCII)));
        sb.append("raw bytes : ");
        for (byte b : toBytes())
            sb.append(String.format("%02X ", b & 0xFF));
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
