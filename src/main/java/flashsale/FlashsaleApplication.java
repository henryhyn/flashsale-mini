package flashsale;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = "flashsale", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FlashsaleApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlashsaleApplication.class, args);
    }
}
