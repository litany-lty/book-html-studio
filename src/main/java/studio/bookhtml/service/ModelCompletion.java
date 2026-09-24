package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared envelope validation; rejected or partial output is not complete content. */
final class ModelCompletion {
    private ModelCompletion() {}
    static String singleText(JsonNode envelope) throws OcrException {
        if (envelope == null || !envelope.isObject() || envelope.has("error"))
            throw new OcrException("模型返回业务错误，未当作完整结果");
        JsonNode choices = envelope.get("choices");
        if (choices == null || !choices.isArray() || choices.size() != 1 || !choices.get(0).isObject())
            throw new OcrException("模型响应必须包含一个明确结果");
        JsonNode choice = choices.get(0), message = choice.get("message");
        if (!choice.path("finish_reason").isTextual() || !"stop".equals(choice.path("finish_reason").textValue()))
            throw new OcrException("模型输出未正常完整结束，保留原文和未完成状态");
        if (message == null || !message.isObject() || !message.path("content").isTextual()
                || message.hasNonNull("refusal") && (!message.path("refusal").isTextual() || !message.path("refusal").asText().isBlank())
                || message.hasNonNull("function_call")
                || message.hasNonNull("tool_calls") && (!message.path("tool_calls").isArray() || !message.path("tool_calls").isEmpty()))
            throw new OcrException("模型返回不是完整文本结果");
        return message.path("content").textValue();
    }
}
