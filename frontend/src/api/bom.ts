import { http } from './http'

/* BOM（物料清单）契约：与后端 ErpBomController 对齐。
   编辑语义是「整表提交」——提交上来的就是这张 BOM 的最终样子，不在列表里的行会被删掉。 */

export interface BomRow {
  id: number
  materialSkuId: number
  materialCode: string | null
  materialName: string
  unit: string | null
  spec: string | null
  qty: number
  lossRate: number
  remark: string | null
  purchasePrice: number | null
  lineCost: number | null
}

export interface BomDetail {
  ok: boolean
  productSkuId: number
  productCode: string
  productName: string
  productUnit: string | null
  /** 配过 BOM = true；配了但一行没有是另一种情况，界面提示不一样 */
  hasBom: boolean
  rows: BomRow[]
  /** 单件材料成本；一个采购价都没有时为 null（不是 0） */
  materialCost: number | null
}

export interface BomProduct {
  id: number
  code: string
  name: string
  unit: string | null
  category: string | null
  materialCount: number
}

export interface BomLineInput {
  materialSkuId: number
  qty: number
  lossRate?: number
  remark?: string | null
}

export const bomApi = {
  detail: (productSkuId: number) =>
    http.get<BomDetail>(`/api/erp/bom?productSkuId=${productSkuId}`),
  products: () => http.get<BomProduct[]>('/api/erp/bom/products'),
  usage: (materialSkuId: number) =>
    http.get<{ ok: boolean; rows: { productSkuId: number; productCode: string | null; productName: string; qty: number; lossRate: number }[] }>(
      `/api/erp/bom/usage?materialSkuId=${materialSkuId}`),
  save: (productSkuId: number, lines: BomLineInput[]) =>
    http.post<{ ok: boolean; message?: string; count?: number; removed?: number }>(
      `/api/erp/bom?productSkuId=${productSkuId}`, { lines })
}
