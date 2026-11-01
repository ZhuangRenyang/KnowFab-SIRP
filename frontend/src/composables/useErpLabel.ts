import { useI18n } from 'vue-i18n'

/**
 * 后端下发的枚举文案（单据类型 / 订单状态）一律是中文。
 * 这里按码查前端译文，查不到就回退后端给的文案——不会显示 key 原文。
 *
 * 用法：const { billType, orderStatus } = useErpLabel()
 *       {{ billType(row.type, row.typeLabel) }}
 */
const BILL_TYPE_KEYS: Record<string, string> = {
  PO_IN: 'erpBillType.poIn',
  SO_OUT: 'erpBillType.soOut',
  SO_RETURN: 'erpBillType.soReturn',
  OTHER_IN: 'erpBillType.otherIn',
  OTHER_OUT: 'erpBillType.otherOut',
  TRANSFER: 'erpBillType.transfer',
  PO_RETURN: 'erpBillType.poReturn',
  STOCKTAKE: 'erpBillType.stocktake',
  PROD_IN: 'erpBillType.prodIn'
}

export function useErpLabel() {
  const { t, te } = useI18n()

  function label(key: string | undefined, fallback: string | undefined, type?: string) {
    if (key && te(key)) return t(key)
    return fallback || type || ''
  }

  return {
    billType: (type?: string, fallback?: string) =>
      label(type ? BILL_TYPE_KEYS[type] : '', fallback, type),

    /** 采购订单状态：草稿/已确认/部分入库/已完成/已取消 */
    purStatus: (code?: string, fallback?: string) =>
      label(code ? `erpPurStatus.${code}` : '', fallback, code),

    /** 销售订单状态：部分发货 与采购的「部分入库」措辞不同，故分开 */
    salesStatus: (code?: string, fallback?: string) =>
      label(code ? `erpSalesStatus.${code}` : '', fallback, code)
  }
}
