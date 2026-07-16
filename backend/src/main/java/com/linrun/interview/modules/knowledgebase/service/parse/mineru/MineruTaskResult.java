package com.linrun.interview.modules.knowledgebase.service.parse.mineru;

import java.net.URI;

public record MineruTaskResult(
    MineruTaskStatus status,
    URI resultZipUrl,
    String failureMessage
) {}
