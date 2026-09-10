package be.ehealth.hub.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HubSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubSimulatorApplication.class, args);
    }
}
