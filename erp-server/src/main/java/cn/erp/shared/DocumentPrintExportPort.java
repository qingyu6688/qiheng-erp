package cn.erp.shared;

import java.util.Map;

public interface DocumentPrintExportPort {
    String TYPE="print-pdf";

    static boolean supports(String exportType) {return TYPE.equals(exportType);}

    Map<String,Object> freeze(ActorScope actor,Map<String,Object> query);
    void authorize(ActorScope actor,Map<String,Object> query,boolean checkDocuments);
    FinancialExportPort.File render(ActorScope actor,Map<String,Object> query);
    void completed(ActorScope actor,long job,Map<String,Object> query);
}
