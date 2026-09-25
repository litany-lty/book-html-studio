package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared envelope validation; rejected, ambiguous or partial output is not complete content. */
final class ModelCompletion {
    private ModelCompletion() {}
    static JsonNode read(ObjectMapper json,String value) throws OcrException {
        if(value==null || value.length()>32*1024*1024)throw new OcrException("模型响应为空或超过上限");
        try(var parser=json.getFactory().createParser(value)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode result=json.readTree(parser);
            if(result==null || !result.isObject() || parser.nextToken()!=null)throw new OcrException("模型响应不是单个对象");
            return result;
        }catch(OcrException safe){throw safe;}catch(Exception invalid){throw new OcrException("模型响应 JSON 无效");}
    }
    static JsonNode singleChoice(JsonNode envelope,boolean nativeOcr) throws OcrException {
        if(envelope==null || !envelope.isObject() || envelope.has("error"))throw new OcrException("模型返回业务错误，未当作完整结果");
        JsonNode choices=nativeOcr?envelope.at("/output/choices"):envelope.path("choices");
        if(!choices.isArray() || choices.size()!=1 || !choices.get(0).isObject())throw new OcrException("模型响应必须包含一个明确结果");
        JsonNode choice=choices.get(0),message=choice.get("message");
        if(!choice.path("finish_reason").isTextual() || !"stop".equals(choice.path("finish_reason").textValue()))
            throw new OcrException("模型输出未正常完整结束，保留原文和未完成状态");
        if(message==null || !message.isObject()
                || message.hasNonNull("refusal") && (!message.path("refusal").isTextual() || !message.path("refusal").asText().isBlank())
                || message.hasNonNull("function_call")
                || message.hasNonNull("tool_calls") && (!message.path("tool_calls").isArray() || !message.path("tool_calls").isEmpty()))
            throw new OcrException("模型返回不是完整内容结果");
        return choice;
    }
    static String singleText(JsonNode envelope) throws OcrException {
        JsonNode content=singleChoice(envelope,false).at("/message/content");
        if(!content.isTextual())throw new OcrException("模型返回不是完整文本结果");
        return content.textValue();
    }
}
