package com.ieum.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.ieum.safety.AiQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Claude Opus 5.5 한 모델로 모든 AI 작업 (docs/이음수첩_AI_개인정보_약등록.md 2.1).
 * structured outputs로 JSON 서식 고정, effort low, 거절 대비 서버 쪽 fallbacks.
 * 키가 없거나 한도·거절·오류면 빈 값 → 부르는 쪽이 저장된 응답으로 대체한다. 원문은 로그에 남기지 않는다.
 */
@Component
public class Claude {

    private static final Logger log = LoggerFactory.getLogger(Claude.class);

    private final AnthropicClient client;
    private final String model;
    private final AiQuota quota;

    public Claude(@Value("${ANTHROPIC_API_KEY:}") String apiKey, @Value("${CLAUDE_MODEL:claude-opus-5-5}") String model,
                  AiQuota quota) {
        this.client = apiKey.isBlank() ? null : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
        this.quota = quota;
    }

    public <T> Optional<T> ask(String system, String user, Class<T> schema, String kind, UUID demoSessionId, String ipHash) {
        if (client == null || !quota.allows(demoSessionId, ipHash)) return Optional.empty();
        try {
            var params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .system(system)
                    .addUserMessage(user)
                    .outputConfig(StructuredOutputConfig.<T>builder()
                            .effort(OutputConfig.Effort.LOW)
                            .format(schema, JsonSchemaLocalValidation.YES)
                            .build())
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .build();
            StructuredMessage<T> res = client.messages().create(params);
            if (res.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                quota.record(demoSessionId, ipHash, kind, model, "refused");
                return Optional.empty();
            }
            quota.record(demoSessionId, ipHash, kind, model, "ok");
            return res.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst();
        } catch (RuntimeException e) {
            log.warn("Claude 호출 실패 ({}): {}", kind, e.getClass().getSimpleName());
            quota.record(demoSessionId, ipHash, kind, model, "error");
            return Optional.empty();
        }
    }
}
