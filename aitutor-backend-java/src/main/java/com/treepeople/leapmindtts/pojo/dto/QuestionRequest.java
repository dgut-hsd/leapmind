package com.treepeople.leapmindtts.pojo.dto;

import lombok.Data;

@Data
public class QuestionRequest {
    private String question;
    private String userId; // Optional, for cases where it's passed in body
}
