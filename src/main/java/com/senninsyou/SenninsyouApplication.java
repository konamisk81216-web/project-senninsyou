package com.senninsyou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class SenninsyouApplication {

    public static void main(String[] args) {
        SpringApplication.run(SenninsyouApplication.class, args);
    }

    @GetMapping("/")
    public String home() {
        return "Project千人将 起動中";
    }
}