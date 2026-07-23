package com.treepeople.leapmindtts.controller.lesson;

import com.treepeople.leapmindtts.pojo.dto.QuestionRequest;
import com.treepeople.leapmindtts.pojo.enums.BizErrorCode;
import com.treepeople.leapmindtts.pojo.result.ApiResponse;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    // Assuming a service will be injected here to handle the business logic
    // For example: @Autowired private ChatService chatService;

    @PostMapping("/ask")
    @RateLimiter(name = "userQuestionLimiter", fallbackMethod = "rateLimitFallback")
    public ApiResponse<String> askQuestion(@RequestBody QuestionRequest request, HttpServletRequest httpRequest) {
        log.info("Received a question from user.");
        // ... Normal business logic pipeline would be called here ...
        // For now, returning a dummy success response.
        String dummyAnswer = "This is a placeholder answer for the question: '" + request.getQuestion() + "'";
        return ApiResponse.success(dummyAnswer);
    }

    /**
     * Fallback method for the userQuestionLimiter.
     * It must have the same signature as the original method, with an added Throwable parameter.
     */
    public ApiResponse<String> rateLimitFallback(QuestionRequest request, HttpServletRequest httpRequest, Throwable t) {
        // The keyResolver will have already run. We can log the remote address as a fallback identifier.
        log.warn("Rate limit triggered for request from {}. Request rejected. Exception: {}", httpRequest.getRemoteAddr(), t.getMessage());
        return ApiResponse.error(BizErrorCode.RATE_LIMITED.getCode(), BizErrorCode.RATE_LIMITED.getMessage());
    }
}
