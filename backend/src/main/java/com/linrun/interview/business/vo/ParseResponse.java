package com.linrun.interview.business.vo;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ParseResponse {
    private Boolean success;
    private CreateScheduleRequest data;
    private Double confidence;
    private String parseMethod; // rule, ai
    private String log;
}
