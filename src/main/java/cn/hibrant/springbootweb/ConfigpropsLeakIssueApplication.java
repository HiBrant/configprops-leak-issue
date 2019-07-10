package cn.hibrant.springbootweb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Repository;

@SpringBootApplication
@MapperScan(basePackages = "cn.hibrant.springbootweb.core.dao", annotationClass = Repository.class)
public class ConfigpropsLeakIssueApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConfigpropsLeakIssueApplication.class, args);
	}
}
