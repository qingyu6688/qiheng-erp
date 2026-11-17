<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import FormCreate from '@form-create/element-ui'
import type { FormRule, Options } from '@form-create/element-ui'
import type { WorkflowFormField } from '../../types/workflow'
import type { WorkflowFormValues } from '../../types/workflow'

const props = defineProps<{ fields: WorkflowFormField[]; disabled?: boolean }>()
const values = defineModel<WorkflowFormValues>({ required: true })
const renderer: Component = FormCreate
const options = computed<Options>(() => ({
  submitBtn: false,
  resetBtn: false,
  row: false,
  col: false,
  form: { labelPosition: 'top', disabled: props.disabled },
}))
const rules = computed<FormRule[]>(() =>
  props.fields.map((field): FormRule => {
    const common = { field: field.key, title: field.label, $required: field.required }
    const accessible = { 'aria-label': field.label }
    if (field.type === 'boolean') return { ...common, type: 'switch', props: accessible }
    if (field.type === 'date')
      return {
        ...common,
        type: 'input',
        props: { ...accessible, type: 'date' },
      }
    if (field.type === 'select')
      return {
        ...common,
        type: 'select',
        props: accessible,
        options: (field.options ?? []).map((label) => ({ label, value: label })),
      }
    return {
      ...common,
      type: 'input',
      props: {
        ...accessible,
        maxlength: field.type === 'number' ? 26 : 2000,
        inputmode: field.type === 'number' ? 'decimal' : 'text',
      },
    }
  }),
)
</script>

<template>
  <component :is="renderer" v-if="rules.length" v-model="values" :rule="rules" :option="options" />
</template>
