import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProvider;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProvider;
import com.pi4j.plugin.linuxfs.provider.i2c.LinuxFsI2CProvider;
import com.pi4j.plugin.raspberrypi.platform.RaspberryPiPlatform;
import com.pi4j.plugin.raspberrypi.provider.spi.RpiSpiProvider;
import java.nio.charset.StandardCharsets;
import lora.LoRaHAT;
import lora.LoRaPacket;

public class Main {

    static final int ownAddr = 0x4321;

    public static void main(String[] args) throws Exception {
        Context pi4j = Pi4J.newContextBuilder()
                .noAutoDetect()
                .add(new RaspberryPiPlatform() {
                    @Override
                    protected String[] getProviders() {
                        return new String[] {};
                    }
                })
                .add(GpioDDigitalInputProvider.newInstance(),
                        GpioDDigitalOutputProvider.newInstance(),
                        LinuxFsI2CProvider.newInstance(),
                        RpiSpiProvider.newInstance())
                .build();

        LoRaHAT hat = new LoRaHAT(pi4j, ownAddr, "/dev/ttyAMA0");
        hat.init();

        LoRaPacket bcast_packet = new LoRaPacket.Builder().origin(ownAddr).payload("hello 123").build();

        LoRaPacket addressed_packet = new LoRaPacket.Builder().origin(ownAddr).payload("hello").destination(0x8AEF).build();

        while (1 > 0) {
            // hat.broadcast("123456789".getBytes(StandardCharsets.US_ASCII));
            // hat.transmit(bcast_packet);
            hat.transmit(addressed_packet);
            Thread.sleep(1000);
        }
    }
}
