package cn.erp.shared;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConstraintErrors {
    private static final Pattern NAME = Pattern.compile("\\b(?:uk|fk|ck)_[a-z0-9_]+", Pattern.CASE_INSENSITIVE);

    private ConstraintErrors() {}

    static ApiException from(String message) {
        Matcher match = NAME.matcher(message == null ? "" : message.toLowerCase(Locale.ROOT));
        if (!match.find()) { return unexpected(); }
        String name = match.group();
        return switch (name) {
            case "uk_invoice_red_active" -> new ApiException(409, "INVOICE_RED_EXISTS", "原票已有待确认或已确认红字登记");
            case "uk_invoice_red_number" -> new ApiException(409, "DUPLICATE", "红字发票号已登记");
            case "ck_invoice_red_review" -> business("TWO_PERSON_REQUIRED", "红字复核人须与登记人不同");
            case "ck_inventory_balance_available" -> business("INVENTORY_INSUFFICIENT", "可用库存不足");
            case "ck_sales_order_line_progress" -> business("SHIP_QTY_EXCEEDED", "发货数量超过剩余数量");
            case "ck_purchase_order_line_progress" -> business("RECEIVE_QTY_EXCEEDED", "收货数量超过可收上限");
            case "ck_shipment_line_return", "ck_goods_receipt_line_return" -> business("RETURN_QTY_EXCEEDED", "退货数量超过可退数量");
            case "ck_shipment_line_sign" -> business("SIGN_QTY_EXCEEDED", "签收数量有误");
            case "ck_shipment_sign_date" -> business("SIGN_DATE_INVALID", "签收日期有误");
            case "ck_partner_open_item_amount" -> business("SETTLEMENT_AMOUNT_EXCEEDED", "核销金额超过剩余金额");
            case "fk_settlement_allocation_debit", "fk_settlement_allocation_credit" -> business("SETTLEMENT_NOT_ALLOWED", "核销双方不匹配");
            case "ck_receipt_refund" -> business("REFUND_AMOUNT_EXCEEDED", "退款金额超过原收款");
            case "ck_cash_account_balance_nonneg" -> business("CASH_BALANCE_INSUFFICIENT", "资金账户余额不足");
            case "ck_refund_request_reviewer", "ck_document_reversal_approver" -> business("SAME_OPERATOR_NOT_ALLOWED", "经办人与审核人须为不同用户");
            case "ck_document_sequence_value" -> business("SEQUENCE_EXHAUSTED", "业务单号已用完");
            case "uk_document_change_pending" -> new ApiException(422, "CHANGE_PENDING", "已有待审核变更");
            case "uk_inventory_reservation_active", "uk_document_reversal_active", "uk_receivable_source",
                    "uk_journal_source", "uk_inventory_movement_source", "uk_inventory_reservation_change" ->
                    new ApiException(409, "STATE_CONFLICT", "该操作已执行或当前状态不允许");
            default -> name.startsWith("uk_") && (name.endsWith("_no") || name.endsWith("_code") ||
                    name.equals("uk_sku_barcode") || name.endsWith("_bank_serial"))
                    ? new ApiException(409, "DUPLICATE", "编码或流水号已存在") : unexpected();
        };
    }

    private static ApiException business(String code, String message) {
        return new ApiException(422, code, message);
    }

    private static ApiException unexpected() {
        return new ApiException(500, "INTERNAL_ERROR", "保存失败，请联系管理员并提供请求编号");
    }
}
