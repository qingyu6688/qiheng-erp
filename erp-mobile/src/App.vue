<script lang="ts">
export default {}
</script>

<style>
page {
  background: #f2f3f5;
  color: #1d2129;
  font: 16px/1.5 "Microsoft YaHei", "PingFang SC", sans-serif;
}
.layout {
  --mobile-primary: #165dff;
  --mobile-primary-hover: #0e42d2;
  --mobile-text: #1d2129;
  --mobile-muted: #4e5969;
  --mobile-border: #c9cdd4;
  --mobile-divider: #e5e6eb;
  --mobile-bg: #f2f3f5;
  --mobile-success: #0b7a26;
  --mobile-success-bg: #eaf6ed;
  --mobile-warning: #b24a00;
  --mobile-danger: #cb2634;
  --mobile-danger-bg: #ffeded;
  --mobile-touch: 48px;
  --mobile-scan: 56px;
  --mobile-radius: 8px;
  --wot-color-theme: var(--mobile-primary);
  --wot-color-title: var(--mobile-text);
  --wot-color-content: var(--mobile-text);
  --wot-color-secondary: var(--mobile-muted);
  --wot-color-border: var(--mobile-border);
  --wot-color-border-light: var(--mobile-divider);
  --wot-color-bg: var(--mobile-bg);
  --wot-color-success: var(--mobile-success);
  --wot-color-warning: var(--mobile-warning);
  --wot-color-danger: var(--mobile-danger);
  --wot-color-info: var(--mobile-muted);
  --wot-button-small-height: var(--mobile-touch);
  --wot-button-medium-height: var(--mobile-touch);
  --wot-button-large-height: var(--mobile-touch);
  --wot-button-medium-fs: 16px;
  --wot-button-medium-radius: var(--mobile-radius);
  --wot-fs-content: 16px;
  width: 100%; max-width: 780px; margin: 0 auto; padding: 0 12px 24px; box-sizing: border-box;
}
.masthead { margin: 0 -12px 12px; background: var(--mobile-primary); color: #fff; padding: 8px 12px; padding-top: calc(8px + max(var(--status-bar-height, 0px), env(safe-area-inset-top, 0px))); display: flex; align-items: center; justify-content: space-between; }
.product, .mast-context, .heading, .subheading, .muted, .error, .success, .field-label { display: block; }
.product { font-size: 18px; font-weight: 700; }
.mast-context { font-size: 16px; }
.heading { font-size: 20px; font-weight: 700; overflow-wrap: anywhere; }
.subheading { font-size: 18px; font-weight: 650; overflow-wrap: anywhere; }
.panel { display: flex; flex-direction: column; gap: 12px; border: 1px solid var(--mobile-divider); border-radius: var(--mobile-radius); background: #fff; padding: 12px; }
.muted { color: var(--mobile-muted); font-size: 16px; overflow-wrap: anywhere; }
.field-label { font-size: 16px; font-weight: 600; }
.field { font-size: 16px; background: #fff; border: 1px solid var(--mobile-border); box-sizing: border-box; border-radius: var(--mobile-radius); min-height: var(--mobile-touch); width: 100%; padding: 8px 12px; }
.field-text { height: auto; min-height: 64px; }
.scan-field { display: flex; flex-direction: column; gap: 6px; }
.scan-line .field { flex: 1; width: auto; min-width: 0; height: var(--mobile-scan); border: 2px solid var(--mobile-primary); }
.scan-line button { min-height: var(--mobile-scan); }
.inline, .workspace, .section-title, .work-row-title { display: flex; align-items: center; gap: 8px; }
.workspace, .section-title, .work-row-title { justify-content: space-between; }
.workspace { margin-bottom: 12px; min-height: var(--mobile-touch); }
.company { font-weight: 600; overflow-wrap: anywhere; }
.company-picker { display: flex; align-items: center; padding: 0 8px; min-height: var(--mobile-touch); }
.module-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.layout button { color: #fff; background: var(--mobile-primary); font-size: 16px; line-height: 1.3; padding: 8px 12px; min-height: var(--mobile-touch); min-width: var(--mobile-touch); margin: 0; border: 1px solid transparent; border-radius: var(--mobile-radius); }
.layout button::after { border: 0; }
.layout button[disabled] { opacity: .62; }
.layout .secondary { background: #fff; color: var(--mobile-text); border-color: var(--mobile-border); }
.compact { min-width: 64px; }
.layout .mast-button { color: #fff; background: transparent; border-color: #fff; }
.layout .work-list-row { width: 100%; background: #fff; border-color: var(--mobile-divider); color: var(--mobile-text); text-align: left; padding: 12px; }
.work-list-row text { display: block; overflow-wrap: anywhere; }
.status { color: var(--mobile-primary-hover); font-size: 16px; white-space: nowrap; }
.line-edit { display: flex; flex-direction: column; gap: 8px; border: 1px solid var(--mobile-divider); border-radius: var(--mobile-radius); padding: 10px; }
.highlighted, .selected { background: #e8f3ff; border-color: var(--mobile-primary); }
.error, .success { padding: 8px 10px; border-radius: var(--mobile-radius); overflow-wrap: anywhere; }
.error { color: var(--mobile-danger); background: var(--mobile-danger-bg); }
.success { color: var(--mobile-success); background: var(--mobile-success-bg); }
.danger { color: var(--mobile-danger); }
.summary-line { justify-content: space-between; }
.pair-code { display: block; overflow-wrap: anywhere; font-family: monospace; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.scan-preview { background: var(--mobile-bg); border-radius: var(--mobile-radius); padding: 6px; }
@media (max-width: 340px) {
  .layout { padding-right: 8px; padding-left: 8px; }
  .masthead { margin-right: -8px; margin-left: -8px; }
}
.task-with-footer { padding-bottom: 84px; padding-bottom: calc(84px + env(safe-area-inset-bottom)); }
.task-footer { position: fixed; z-index: 10; bottom: 0; left: 50%; display: flex; gap: 8px; width: 100%; max-width: 780px; transform: translateX(-50%); border-top: 1px solid var(--mobile-divider); background: #fff; padding: 12px; padding-bottom: calc(12px + env(safe-area-inset-bottom)); box-sizing: border-box; }
.task-footer-primary { flex: 1; min-width: 0; }
.task-footer > :only-child { flex: 1; }
</style>
