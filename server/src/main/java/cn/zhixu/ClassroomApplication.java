package cn.zhixu;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知序课堂后端启动入口。
 *
 * <p>{@code @EnableScheduling} 负责启用按课次预约的自动缺勤任务；所有业务时间统一使用下方注入的中国时区时钟， 避免浏览器或服务器默认时区导致签到边界不一致。
 */
@SpringBootApplication
@EnableScheduling
public class ClassroomApplication {
  /** 启动 Spring Boot，并装配 Web、JDBC、Flyway 与业务 Bean。 */
  public static void main(String[] args) {
    SpringApplication.run(ClassroomApplication.class, args);
  }

  /** 为鉴权、签到、课表与调度提供可测试的统一服务端时钟。 */
  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("Asia/Shanghai"));
  }
}
