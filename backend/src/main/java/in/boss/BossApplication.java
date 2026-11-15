package in.boss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BossApplication {
    public static void main(String[] args) {
        SpringApplication.run(BossApplication.class, args);
    }
}
