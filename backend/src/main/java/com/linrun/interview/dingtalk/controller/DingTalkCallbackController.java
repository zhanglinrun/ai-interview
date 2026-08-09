package com.linrun.interview.dingtalk.controller;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.dingtalk.model.DingTalkCallbackPayload;
import com.linrun.interview.dingtalk.model.DingTalkCallbackResult;
import com.linrun.interview.dingtalk.service.DingTalkCallbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 钉钉事件订阅回调，支持首次 URL 验证和机器人消息事件。 */
@RestController
@RequestMapping("/api/v1/dingtalk/callback")
@RequiredArgsConstructor
public class DingTalkCallbackController {

    private final DingTalkCallbackService callbackService;

    @GetMapping
    public String verify(
        @RequestParam String timestamp,
        @RequestParam(required = false) String signature,
        @RequestParam(required = false) String sign,
        @RequestParam(defaultValue = "") String challenge) {
        callbackService.verify(timestamp, firstNonBlank(signature, sign));
        return challenge;
    }

    @PostMapping
    public Result<DingTalkCallbackResult> callback(
        @RequestParam String timestamp,
        @RequestParam(required = false) String signature,
        @RequestParam(required = false) String sign,
        @Valid @RequestBody DingTalkCallbackPayload payload) {
        return Result.success(callbackService.handle(payload, timestamp,
            firstNonBlank(signature, sign)));
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
