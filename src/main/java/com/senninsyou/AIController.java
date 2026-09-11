package com.senninsyou;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    @PostMapping("/command")
    public String command(@RequestBody String command) {

        System.out.println("Webからの命令：" + command);

        return "将軍が命令を受け取りました：" + command;
    }
}