package cn.erp.shared;

import java.time.LocalDate;

/** 返回空表示沿用该单据的默认取号规则。 */
public interface ConfiguredDocumentNumber {
    String document(ActorScope actor, String type, LocalDate date);
}
