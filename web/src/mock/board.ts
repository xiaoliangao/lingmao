import type { OrderReview } from '@/types/order'
import { mock } from './index'

// 审核对话框的评审信息（docs/api/02 §3.8）：后端在 board 域，资料包、缺口、加工厂在手要等算料、生产做好再写
mock('get', '/api/board/orders/:id/review', () => {
  const rev: OrderReview = {
    packs: [{ styleId: '1', styleCode: 'FS2611150101', styleName: '双排扣廓形短风衣', techPackId: '202', version: 1, problem: null }],
    shortages: [
      { materialSkuId: '101', skuCode: 'ML0001-KQ', materialName: '220g 精梳斜纹棉布', skuColorName: '卡其', skuSpec: '150cm', unit: 'METER', need: 310, available: 180, gap: 130, leadDays: 7, image: null },
    ],
    loads: [{ factoryId: '3', factoryName: '示例制衣一厂', styleCodes: ['FS2611150101'], wipQty: 520, orderCount: 2 }],
  }
  return rev
})
