ALTER TABLE eval_run
  ADD COLUMN tool_selection_rate DECIMAL(8,4) NULL,
  ADD COLUMN parameter_rate DECIMAL(8,4) NULL,
  ADD COLUMN report_agreement_rate DECIMAL(8,4) NULL;

ALTER TABLE eval_result
  ADD COLUMN tool_selected BOOLEAN NULL,
  ADD COLUMN parameters_correct BOOLEAN NULL,
  ADD COLUMN report_agrees BOOLEAN NULL;
