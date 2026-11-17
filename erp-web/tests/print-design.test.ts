import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PrintCanvas from '../src/pages/print/PrintCanvas.vue'
import { createElement, emptyTemplate, paperDimensions } from '../src/types/print-design'
import type { PrintModel } from '../src/types/print-design'

describe('打印设计器版式', () => {
  it('三等分纸型保持毫米宽高，横向交换宽高', () => {
    const paper = emptyTemplate('shipment').layout.paper
    paper.kind = 'third'
    expect(paperDimensions(paper)).toEqual({ width: 241, height: 93 })
    paper.orientation = 'landscape'
    expect(paperDimensions(paper)).toEqual({ width: 93, height: 241 })
    expect(createElement('qrcode')).toMatchObject({ field: 'documentNo', w: 22, h: 22 })
  })

  it('相同版式渲染分页、毫米坐标和复用的条码资源', () => {
    const model: PrintModel = {
      width: 241,
      height: 93,
      background: 'data:image/png;base64,YQ==',
      assets: { 'asset-0': 'data:image/png;base64,Yg==' },
      pages: [
        {
          items: [
            {
              type: 'text',
              x: 4,
              y: 5,
              w: 60,
              h: 20,
              fontSize: 10,
              align: 'right',
              bold: true,
              lines: ['发货单 F-01'],
            },
          ],
        },
        {
          items: [
            {
              type: 'image',
              x: 4,
              y: 5,
              w: 20,
              h: 20,
              fontSize: 0,
              align: 'left',
              bold: false,
              lines: [],
              image: 'asset-0',
            },
          ],
        },
      ],
    }
    const wrapper = mount(PrintCanvas, { props: { model } })
    expect(wrapper.findAll('article')).toHaveLength(2)
    expect(wrapper.get('article').attributes('style')).toContain('width: 241mm; height: 93mm;')
    expect(wrapper.get('.print-text').attributes('style')).toContain('left: 4mm; top: 5mm;')
    expect(wrapper.get('.print-text').text()).toBe('发货单 F-01')
    expect(wrapper.get('.print-image img').attributes('src')).toBe(model.assets?.['asset-0'])
    expect(wrapper.get('.preprinted').attributes('src')).toBe(model.background)
  })
})
