package com.feisheng.bot.admin;

import com.feisheng.bot.admin.config.PrefixingMapperNameGenerator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {"com.feisheng.bot"},
    exclude = UserDetailsServiceAutoConfiguration.class
)
@EnableScheduling
@MapperScan(value = {
    "com.feisheng.bot.admin.mapper",
    "com.feisheng.bot.core.mapper",
    "com.feisheng.bot.knowledge.mapper"
}, nameGenerator = PrefixingMapperNameGenerator.class)
public class AdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
