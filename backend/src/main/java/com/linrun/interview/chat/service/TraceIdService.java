package com.linrun.interview.chat.service;

import com.linrun.interview.infra.observability.TraceContext;
import org.springframework.stereotype.Service;

/** Chat-facing facade for the request trace. */
@Service
public class TraceIdService {
  public String currentOrCreate() {
    return TraceContext.currentOrCreate();
  }
}
