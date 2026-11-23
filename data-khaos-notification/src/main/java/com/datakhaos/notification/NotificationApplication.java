package com.datakhaos.notification;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 推送系统服务
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.datakhaos.notification.mapper")
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
        System.out.println("""

                ==========================================
                 Data Khaos - Notification 推送系统已启动 :8090
                ==========================================
                """);
    }
}
