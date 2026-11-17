ALTER TABLE reconciliation_run DROP CHECK ck_reconciliation_run_type,
    ADD CONSTRAINT ck_reconciliation_run_type CHECK (check_type IN (
        'inventory_balance','inventory_cost','reservation','cash_balance','open_item','journal_balance',
        'ledger_inventory','period_close','gl_trial','gl_operational','batch_balance','stock_blocks','location_balance'
    ));
