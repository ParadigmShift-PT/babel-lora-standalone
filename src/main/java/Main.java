import java.nio.charset.StandardCharsets;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProvider;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProvider;
import com.pi4j.plugin.linuxfs.provider.i2c.LinuxFsI2CProvider;
import com.pi4j.plugin.raspberrypi.platform.RaspberryPiPlatform;
import com.pi4j.plugin.raspberrypi.provider.spi.RpiSpiProvider;

import lora_hat.LoRaHAT;

public class Main {

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

        LoRaHAT hat = new LoRaHAT(pi4j, 0x1234,"/dev/serial0");
        hat.init();

        while (1 > 0) {
            System.out.println("tryin to broadcast");
            hat.broadcast("123456789".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(1000);
        }
    }
}
