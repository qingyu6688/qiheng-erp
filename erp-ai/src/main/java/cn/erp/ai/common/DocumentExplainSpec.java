package cn.erp.ai.common;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 单据解释仅接受当前页面明确绑定的单据，入口独立于查数模型的工具目录。 */
public record DocumentExplainSpec(String documentType,String documentId) {
    public static final String CODE="document_explain";
    public static final String LABEL="单据解释";
    private static final Set<String> TYPES=Set.of("sales_order","shipment","accounting_period");

    public static DocumentExplainSpec read(JsonNode arguments) {
        if (arguments==null || !arguments.isObject() || arguments.size()!=2
                || !arguments.path("documentType").isString() || !arguments.path("documentId").isString())
            throw AiException.invalid("单据解释需要有效的单据类型和编号");
        String type=arguments.path("documentType").stringValue();
        String id=arguments.path("documentId").stringValue();
        if (!TYPES.contains(type)) throw AiException.invalid("此单据类型暂不支持解释");
        AiInputs.id(id);
        return new DocumentExplainSpec(type,id);
    }

    public static boolean allowed(AiActor actor) {
        return TYPES.stream().anyMatch(type->allowed(actor,type));
    }

    public void authorize(AiActor actor) {
        actor.require("ai:use");
        if (!allowed(actor,documentType)) throw AiException.forbidden();
    }

    private static boolean allowed(AiActor actor,String type) {
        return switch (type) {
            case "sales_order" -> actor.has("sales_order:view");
            case "shipment" -> actor.has("shipment:view");
            case "accounting_period" -> actor.has("period:view") && actor.has("period:close") && actor.allWarehouses();
            default -> false;
        };
    }
}
