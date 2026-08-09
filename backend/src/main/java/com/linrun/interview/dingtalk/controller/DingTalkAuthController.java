package com.linrun.interview.dingtalk.controller;

import com.linrun.interview.dingtalk.service.DingTalkAccessTokenService;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.dingtalk.model.DingTalkAccessToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 钉钉 OAuth 授权码换取用户访问令牌的受保护接口。 */
@RestController
@RequestMapping("/api/v1/dingtalk/auth")
@RequiredArgsConstructor
public class DingTalkAuthController {

    private final DingTalkAccessTokenService tokenService;

    @PostMapping("/token")
    public Result<DingTalkAccessToken> exchange(@Valid @RequestBody ExchangeRequest request) {
        return Result.success(tokenService.exchange(request.code()));
    }

    public record ExchangeRequest(@NotBlank String code) {
    }
}
