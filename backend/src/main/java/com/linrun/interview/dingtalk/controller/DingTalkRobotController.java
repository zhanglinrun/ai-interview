package com.linrun.interview.dingtalk.controller;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.dingtalk.model.DingTalkRobotMessage;
import com.linrun.interview.dingtalk.service.DingTalkRobotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户主动发送机器人消息的受控接口。 */
@RestController
@RequestMapping("/api/v1/dingtalk/robot")
@RequiredArgsConstructor
public class DingTalkRobotController {

    private final DingTalkRobotService robotService;

    @PostMapping("/messages")
    public Result<Void> send(@Valid @RequestBody DingTalkRobotMessage request) {
        robotService.send(request.webhook(), request.secret(), request.content(), request.atUserId());
        return Result.success();
    }
}
