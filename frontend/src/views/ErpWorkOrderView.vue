<template>
  <div class="page">
    <!-- 四张概览卡：车间主任第一眼要看的就是"现在在做什么" -->
    <div class="kpi-row">
      <div class="kpi" :class="{ on: status === 'RUNNING' }" @click="pickStatus('RUNNING')">
        <div class="kpi-label">{{ t('wo.kpiRunning') }}</div>
        <div class="kpi-value num">{{ summary.running }}</div>
      </div>
      <div class="kpi" :class="{ on: status === 'RELEASED' }" @click="pickStatus('RELEASED')">
        <div class="kpi-label">{{ t('wo.kpiReleased') }}</div>
        <div class="kpi-value num">{{ summary.released }}</div>
      </div>
      <div class="kpi" :class="{ on: status === 'DRAFT' }" @click="pickStatus('DRAFT')">
        <div class="kpi-label">{{ t('wo.kpiDraft') }}</div>
        <div class="kpi-value num">{{ summary.draft }}</div>
      </div>
      <div class="kpi" :class="{ on: status === 'DONE' }" @click="pickStatus('DONE')">
        <div class="kpi-label">{{ t('wo.kpiDone') }}</div>
        <div class="kpi-value num">{{ summary.done }}</div>
      </div>
      <div class="kpi kpi-warn" :class="{ on: showShortage }" @click="toggleShortage">
        <div class="kpi-label">{{ t('wo.kpiShortage') }}</div>
        <div class="kpi-value num">{{ shortageRows.length }}</div>
      </div>
    </div>

    <el-card shadow="never" class="card">
      <div class="tab-toolbar">
        <el-select v-model="status" clearable :placeholder="t('wo.allStatus')" class="w160" @change="load">
          <el-option v-for="s in WORK_ORDER_STATUSES" :key="s.value" :value="s.value" :label="t('wo.st_' + s.value)" />
        </el-select>
        <el-input
          v-model="kw"
          :placeholder="t('wo.searchPh')"
          clearable
          :prefix-icon="Search"
          class="w240"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('base.refresh') }}</el-button>
        <div class="grow" />
        <el-button v-if="auth.canWrite" type="primary" :icon="Plus" @click="openNew">
          {{ t('wo.newBtn') }}
        </el-button>
      </div>

      <!-- 缺料看板：点开就在列表位置直接看，不另开页面 -->
      <template v-if="showShortage">
        <div class="shortage-head">
          <span>{{ t('wo.shortageTitle') }}</span>
          <el-button link type="primary" @click="toggleShortage">{{ t('wo.backToList') }}</el-button>
        </div>
        <el-table :data="shortageRows" stripe v-loading="sLoading" :empty-text="t('wo.shortageEmpty')">
          <el-table-column prop="orderNo" :label="t('wo.colNo')" width="160">
            <template #default="{ row }"><span class="num">{{ row.orderNo }}</span></template>
          </el-table-column>
          <el-table-column prop="productName" :label="t('wo.colProduct')" min-width="140" />
          <el-table-column :label="t('wo.colStatus')" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="light" :type="workOrderStatusType(row.status)">
                {{ t('wo.st_' + row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colMaterial')" min-width="200">
            <template #default="{ row }">
              <span class="num">{{ row.skuCode }}</span> {{ row.skuName }}
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colNeed')" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.needQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colIssued')" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.issuedQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colGap')" width="100" align="right">
            <template #default="{ row }"><span class="num danger">{{ qty(row.gapQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colStock')" width="110" align="right">
            <template #default="{ row }">
              <span class="num" :class="{ danger: row.stockQty != null && row.stockQty < row.gapQty }">
                {{ row.stockQty == null ? '--' : qty(row.stockQty, 2) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('base.action')" width="110" align="right">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.orderNo)">{{ t('wo.detail') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">{{ t('wo.stockHint') }}</p>
      </template>

      <!-- 工单列表 -->
      <template v-else>
        <el-table :data="rows" stripe v-loading="loading" :empty-text="t('wo.empty')">
          <el-table-column :label="t('wo.colNo')" width="158">
            <template #default="{ row }">
              <el-button link type="primary" class="num" @click="openDetail(row.orderNo)">{{ row.orderNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colProduct')" min-width="170">
            <template #default="{ row }">
              <div class="cell-main">{{ row.productName || '--' }}</div>
              <div class="cell-sub num">{{ row.productCode }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colProgress')" min-width="180">
            <template #default="{ row }">
              <div class="prog">
                <el-progress
                  :percentage="Math.min(row.progress || 0, 100)"
                  :status="row.status === 'DONE' ? 'success' : row.overdue ? 'exception' : undefined"
                  :stroke-width="8"
                  :show-text="false"
                />
                <span class="prog-text num">
                  {{ qty(row.doneQty, 0) }} / {{ qty(row.planQty, 0) }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colStatus')" width="96">
            <template #default="{ row }">
              <el-tag size="small" effect="light" :type="workOrderStatusType(row.status)">
                {{ t('wo.st_' + row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colDevice')" width="120">
            <template #default="{ row }">
              <span class="num">{{ row.deviceCode || '--' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colDue')" width="106" align="right">
            <template #default="{ row }">
              <span v-if="row.planEnd == null" class="muted">--</span>
              <span v-else-if="row.status === 'DONE' || row.status === 'CANCELLED'" class="num muted">
                {{ day(row.planEnd) }}
              </span>
              <span v-else :class="row.overdue ? 'danger' : row.dueInDays != null && row.dueInDays <= 2 ? 'warn' : 'num'">
                {{ row.overdue ? t('wo.overdue') : row.dueInDays != null && row.dueInDays <= 2 ? t('wo.dueSoon') : day(row.planEnd) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('base.action')" width="236" align="right">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.orderNo)">{{ t('wo.detail') }}</el-button>
              <el-button
                v-if="row.status === 'DRAFT' && auth.canWrite"
                size="small" type="primary"
                @click="act(row, 'release')"
              >{{ t('wo.actRelease') }}</el-button>
              <el-button
                v-if="row.status === 'RELEASED' && auth.canOperate"
                size="small" type="primary"
                @click="act(row, 'start')"
              >{{ t('wo.actStart') }}</el-button>
              <el-button
                v-if="row.status === 'RUNNING' && auth.canOperate"
                size="small" type="success"
                @click="act(row, 'finish')"
              >{{ t('wo.actFinish') }}</el-button>
              <el-button
                v-if="(row.status === 'RELEASED' || row.status === 'RUNNING') && auth.canOperate"
                size="small"
                @click="openIssue(row.orderNo)"
              >{{ t('wo.actIssue') }}</el-button>
              <el-button
                v-if="row.status !== 'DONE' && row.status !== 'CANCELLED' && auth.canWrite"
                size="small" type="danger" plain
                @click="act(row, 'cancel')"
              >{{ t('wo.actCancel') }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="prev, pager, next, total"
            background
            @current-change="load"
          />
        </div>
      </template>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" size="720px" :title="t('wo.detailTitle')" destroy-on-close>
      <template v-if="detail">
        <div class="dt-head">
          <div class="dt-no num">{{ detail.orderNo }}</div>
          <el-tag size="small" effect="light" :type="workOrderStatusType(detail.status)">
            {{ t('wo.st_' + detail.status) }}
          </el-tag>
          <span v-if="detail.overdue" class="dt-flag danger">{{ t('wo.overdue') }}</span>
          <div class="grow" />
          <template v-if="detail.status === 'DRAFT' && auth.canWrite">
            <el-button size="small" @click="openEdit(detail)">{{ t('base.edit') }}</el-button>
            <el-button size="small" type="primary" @click="act(detail, 'release')">{{ t('wo.actRelease') }}</el-button>
          </template>
          <el-button
            v-if="detail.status === 'RELEASED' && auth.canOperate"
            size="small" type="primary" @click="act(detail, 'start')"
          >{{ t('wo.actStart') }}</el-button>
          <el-button
            v-if="detail.status === 'RUNNING' && auth.canOperate"
            size="small" type="success" @click="act(detail, 'finish')"
          >{{ t('wo.actFinish') }}</el-button>
          <el-button
            v-if="(detail.status === 'RELEASED' || detail.status === 'RUNNING') && auth.canOperate"
            size="small" @click="openIssue(detail.orderNo)"
          >{{ t('wo.actIssue') }}</el-button>
        </div>

        <el-descriptions :column="2" border size="small" class="dt-desc">
          <el-descriptions-item :label="t('wo.colProduct')">
            {{ detail.productName }} <span class="muted num">{{ detail.productCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colPlanQty')">
            <span class="num">{{ qty(detail.planQty, 2) }}</span> {{ detail.productUnit }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colDoneQty')">
            <span class="num strong">{{ qty(detail.doneQty, 2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colDefectQty')">
            <span class="num" :class="{ danger: detail.defectQty > 0 }">{{ qty(detail.defectQty, 2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colDevice')">
            <span class="num">{{ detail.deviceCode || '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colLine')">
            <span class="num">{{ detail.lineCode || '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colMaterialWh')">
            <span class="num">{{ whName(detail.materialWhId) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colSrcOrder')">
            <span class="num">{{ detail.srcOrderNo || '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colBackflush')">
            {{ detail.autoBackflush ? t('wo.backflushOn') : t('wo.backflushOff') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colPlanStart')">
            <span class="num">{{ detail.planStart ? day(detail.planStart) : '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colPlanEnd')">
            <span class="num">{{ detail.planEnd ? day(detail.planEnd) : '--' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('wo.colCreated')" :span="2">
            <span class="num">{{ dt(detail.createdAt) }}</span>
            <span class="muted" v-if="detail.createdBy"> · {{ detail.createdBy }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" :label="t('base.remark')" :span="2">
            {{ detail.remark }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="dt-sec">
          <span class="dt-sec-title">{{ t('wo.secMaterial') }}</span>
          <span class="dt-sec-sub">
            {{ t('wo.materialSum', { need: qty(detail.materialNeedTotal, 2), issued: qty(detail.materialIssuedTotal, 2) }) }}
          </span>
          <el-tag v-if="detail.shortageCount" type="danger" size="small" effect="light" class="ml8">
            {{ t('wo.shortageCount', { n: detail.shortageCount }) }}
          </el-tag>
        </div>
        <el-table :data="detail.materials" size="small" stripe :empty-text="t('wo.noMaterial')">
          <el-table-column :label="t('wo.colMaterial')" min-width="180">
            <template #default="{ row }">
              <span class="num">{{ row.skuCode }}</span> {{ row.skuName }}
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colNeed')" width="92" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.needQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colIssued')" width="92" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.issuedQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colGap')" width="92" align="right">
            <template #default="{ row }">
              <span class="num" :class="row.shortage ? 'danger' : row.over ? 'warn' : ''">
                {{ row.shortage ? qty(row.gapQty, 2) : row.over ? '+' + qty(-row.gapQty, 2) : '0' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colStock')" width="92" align="right">
            <template #default="{ row }">
              <span class="num">{{ row.stockQty == null ? '--' : qty(row.stockQty, 2) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('wo.colFlag')" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.shortage" size="small" type="danger" effect="plain">{{ t('wo.flagShort') }}</el-tag>
              <el-tag v-else-if="row.over" size="small" type="warning" effect="plain">{{ t('wo.flagOver') }}</el-tag>
              <span v-else class="muted">{{ t('wo.flagOk') }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="dt-sec">
          <span class="dt-sec-title">{{ t('wo.secOutput') }}</span>
          <span class="dt-sec-sub">{{ t('wo.outputSum', { good: qty(detail.doneQty, 0), bad: qty(detail.defectQty, 0), total: qty(detail.doneQty + detail.defectQty, 0) }) }}</span>
        </div>
        <el-table :data="detail.outputs" size="small" stripe :empty-text="t('wo.noOutput')">
          <el-table-column :label="t('wo.colBillNo')" width="168">
            <template #default="{ row }"><span class="num">{{ row.billNo }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colDevice')" width="120">
            <template #default="{ row }"><span class="num">{{ row.deviceCode || '--' }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colGoodQty')" width="100" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.qty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colDefectQty')" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ qty(row.defectQty, 2) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('wo.colTime')" min-width="140">
            <template #default="{ row }"><span class="num">{{ dt(row.createdAt) }}</span></template>
          </el-table-column>
        </el-table>

        <!-- 成本：料 / 工 / 费。算不出来的项显示 --，不显示 0 -->
        <div class="dt-sec">
          <span class="dt-sec-title">{{ t('cost.secTitle') }}</span>
          <span class="dt-sec-sub">{{ cost ? cost.hoursSourceLabel + ' ' + qty(cost.hours, 2) + ' h' : '' }}</span>
        </div>
        <template v-if="cost">
          <div class="cost-grid">
            <div class="cost-cell">
              <span class="cc-label">{{ t('cost.material') }}</span>
              <span class="cc-val num">{{ money(cost.materialCost) }}</span>
              <span class="cc-sub" v-if="cost.materialVariance != null">
                {{ t('cost.variance') }}
                <b :class="cost.materialVariance > 0 ? 'down' : 'up'">{{ money(cost.materialVariance) }}</b>
              </span>
            </div>
            <div class="cost-cell">
              <span class="cc-label">{{ t('cost.labor') }}</span>
              <span class="cc-val num" :class="{ muted: cost.laborCost == null }">{{ cost.laborCost == null ? '--' : money(cost.laborCost) }}</span>
              <span class="cc-sub">{{ t('cost.rateHint', { r: cost.laborRate }) }}</span>
            </div>
            <div class="cost-cell">
              <span class="cc-label">{{ t('cost.overhead') }}</span>
              <span class="cc-val num">{{ money(cost.overheadCost) }}</span>
              <span class="cc-sub">{{ t('cost.elecIncl', { amt: money(cost.electricityCost) }) }}</span>
            </div>
            <div class="cost-cell cc-total">
              <span class="cc-label">{{ t('cost.colUnitCost') }}</span>
              <span class="cc-val num">{{ cost.unitCost == null ? '--' : money(cost.unitCost) }}</span>
              <span class="cc-sub">{{ money(cost.totalCost) }} / {{ qty(cost.doneQty, 0) }}</span>
            </div>
          </div>
          <p v-if="cost.note" class="cost-note">{{ cost.note }}</p>
        </template>
        <p v-else class="cost-note">{{ t('cost.noCost') }}</p>
      </template>
    </el-drawer>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dlg" :title="editing ? t('wo.titleEdit') : t('wo.titleNew')" width="600px" :close-on-click-modal="false">
      <el-form label-width="104px">
        <el-form-item :label="t('wo.colProduct')" required>
          <el-select v-model="f.productSkuId" filterable :placeholder="t('wo.pickProduct')" class="w-full">
            <el-option v-for="s in skus" :key="s.id" :value="s.id" :label="`${s.code} ${s.name}`">
              <span class="opt-code">{{ s.code }}</span>{{ s.name }}
            </el-option>
          </el-select>
        </el-form-item>
        <div class="frow">
          <el-form-item :label="t('wo.colPlanQty')" required>
            <el-input-number v-model="f.planQty" :min="0.0001" :precision="2" controls-position="right" class="w-full" />
          </el-form-item>
          <el-form-item :label="t('wo.colUnit')">
            <span class="muted">{{ skus.find((s) => s.id === f.productSkuId)?.unit || '--' }}</span>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('wo.colLine')">
            <el-select v-model="f.lineCode" clearable :placeholder="t('wo.noLine')" class="w-full">
              <el-option v-for="l in lines" :key="l.code" :value="l.code" :label="`${l.code} ${l.name}`" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('wo.colDevice')">
            <el-select v-model="f.deviceCode" clearable filterable :placeholder="t('wo.deviceHint')" class="w-full">
              <el-option v-for="d in devices" :key="d.code" :value="d.code" :label="`${d.code} ${d.name}`" />
            </el-select>
          </el-form-item>
        </div>
        <div class="frow">
          <el-form-item :label="t('wo.colWarehouse')">
            <el-select v-model="f.warehouseId" clearable :placeholder="t('wo.whHint')" class="w-full">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('wo.colSrcOrder')">
            <el-input v-model="f.srcOrderNo" :placeholder="t('wo.srcOrderHint')" />
          </el-form-item>
        </div>
        <el-form-item :label="t('wo.colMaterialWh')">
          <el-select v-model="f.materialWhId" clearable :placeholder="t('wo.materialWhHint')" class="w-full">
            <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
          <div class="field-hint">{{ t('wo.materialWhHint') }}</div>
        </el-form-item>
        <div class="frow">
          <el-form-item :label="t('wo.colPlanStart')">
            <el-date-picker v-model="f.planStart" type="datetime" class="w-full" :placeholder="t('wo.pickDate')" />
          </el-form-item>
          <el-form-item :label="t('wo.colPlanEnd')">
            <el-date-picker v-model="f.planEnd" type="datetime" class="w-full" :placeholder="t('wo.pickDate')" />
          </el-form-item>
        </div>
        <el-form-item :label="t('wo.colBackflush')">
          <el-switch v-model="f.autoBackflush" />
          <span class="muted ml8">{{ t('wo.backflushHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('base.remark')">
          <el-input v-model="f.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">{{ t('base.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ t('base.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 领料 -->
    <el-dialog v-model="issueDlg" :title="t('wo.issueTitle')" width="640px" :close-on-click-modal="false">
      <div class="issue-head">
        <span>{{ t('wo.issueOrder') }} <b class="num">{{ issueNo }}</b></span>
        <div class="grow" />
        <el-select v-model="issueWh" :placeholder="t('wo.pickWh')" class="w180">
          <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
        </el-select>
      </div>
      <el-table :data="issueLines" size="small" stripe max-height="340">
        <el-table-column :label="t('wo.colMaterial')" min-width="180">
          <template #default="{ row }">
            <span class="num">{{ row.skuCode }}</span> {{ row.skuName }}
          </template>
        </el-table-column>
        <el-table-column :label="t('wo.colGap')" width="92" align="right">
          <template #default="{ row }"><span class="num">{{ qty(row.gapQty, 2) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('wo.colStock')" width="92" align="right">
          <template #default="{ row }">
            <span class="num" :class="{ danger: row.stockQty != null && row.stockQty < row.qty }">
              {{ row.stockQty == null ? '--' : qty(row.stockQty, 2) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('wo.colIssueQty')" width="140" align="right">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" :min="0" :precision="4" :step="1" size="small" controls-position="right" class="w-full" />
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">{{ t('wo.issueHint') }}</p>
      <template #footer>
        <el-button @click="issueDlg = false">{{ t('base.cancel') }}</el-button>
        <el-button :loading="saving" @click="doAutoIssue">{{ t('wo.issueAll') }}</el-button>
        <el-button type="primary" :loading="saving" @click="doIssue">{{ t('wo.issueSubmit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  workOrderApi,
  workOrderStatusType,
  WORK_ORDER_STATUSES,
  type ShortageRow,
  type WorkOrderDetail,
  type WorkOrderRow,
  type WorkOrderSummary
} from '../api/workOrder'
import { skuApi, warehouseApi, type ErpSku, type ErpWarehouse } from '../api/erp'
import { lineApi } from '../api/lines'
import { devicesApi } from '../api/devices'
import { costApi, type OrderCost } from '../api/cost'
import { useAuthStore } from '../stores/auth'
import { money, qty } from '../utils/format'

const { t } = useI18n()
const auth = useAuthStore()

const rows = ref<WorkOrderRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const saving = ref(false)
const status = ref<string>('')
const kw = ref('')

const summary = reactive<WorkOrderSummary>({ running: 0, released: 0, draft: 0, done: 0 })

const showShortage = ref(false)
const shortageRows = ref<ShortageRow[]>([])
const sLoading = ref(false)

const drawer = ref(false)
const detail = ref<WorkOrderDetail | null>(null)
const cost = ref<OrderCost | null>(null)

const dlg = ref(false)
const editing = ref(false)
const f = reactive({
  productSkuId: undefined as number | undefined,
  planQty: 1,
  lineCode: null as string | null,
  deviceCode: null as string | null,
  warehouseId: null as number | null,
  materialWhId: null as number | null,
  srcOrderNo: '',
  autoBackflush: true,
  planStart: null as Date | null,
  planEnd: null as Date | null,
  remark: ''
})

const issueDlg = ref(false)
const issueNo = ref('')
const issueWh = ref<number | null>(null)
const issueLines = ref<{ skuId: number; skuCode: string | null; skuName: string; gapQty: number; stockQty: number | null; qty: number }[]>([])

const skus = ref<ErpSku[]>([])
const warehouses = ref<ErpWarehouse[]>([])
const lines = ref<{ code: string; name: string }[]>([])
const devices = ref<{ code: string; name: string }[]>([])

const canAct = computed(() => auth.canWrite)
const deviceName = (c: string) => devices.value.find((d) => d.code === c)?.name || c
const whName = (id: number | null) => warehouses.value.find((w) => w.id === id)?.name || '--'

function pickStatus(s: string) {
  status.value = status.value === s ? '' : s
  showShortage.value = false
  page.value = 1
  load()
}

async function toggleShortage() {
  showShortage.value = !showShortage.value
  if (showShortage.value) await loadShortage()
}

async function loadShortage() {
  sLoading.value = true
  try {
    const r = await workOrderApi.shortage()
    shortageRows.value = r.rows || []
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    sLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const r = await workOrderApi.list({ status: status.value, kw: kw.value, page: page.value, size: size.value })
    if (!r.ok) {
      ElMessage.error((r as unknown as { message?: string }).message || t('common.reqFail'))
      return
    }
    rows.value = r.rows || []
    total.value = r.total || 0
    Object.assign(summary, r.summary || {})
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    loading.value = false
  }
}

async function openDetail(orderNo: string) {
  drawer.value = true
  try {
    const d = await workOrderApi.detail(orderNo)
    if (!d || !d.orderNo) {
      ElMessage.error((d as unknown as { message?: string })?.message || t('common.reqFail'))
      drawer.value = false
      return
    }
    detail.value = d
    // 成本单独拉：算不出来（没配工价/没开工）时不该让整个详情打不开
    try {
      const c = await costApi.order(orderNo)
      cost.value = c && c.ok ? c : null
    } catch {
      cost.value = null
    }
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

function openNew() {
  editing.value = false
  Object.assign(f, {
    productSkuId: undefined,
    planQty: 1,
    lineCode: null,
    deviceCode: null,
    warehouseId: null,
    materialWhId: null,
    srcOrderNo: '',
    autoBackflush: true,
    planStart: null,
    planEnd: null,
    remark: ''
  })
  dlg.value = true
}

function openEdit(o: WorkOrderDetail | WorkOrderRow) {
  editing.value = true
  Object.assign(f, {
    productSkuId: o.productSkuId,
    planQty: o.planQty,
    lineCode: o.lineCode,
    deviceCode: o.deviceCode,
    warehouseId: o.warehouseId,
    materialWhId: o.materialWhId,
    srcOrderNo: o.srcOrderNo || '',
    autoBackflush: o.autoBackflush,
    planStart: o.planStart ? new Date(o.planStart) : null,
    planEnd: o.planEnd ? new Date(o.planEnd) : null,
    remark: o.remark || ''
  })
  dlg.value = true
}

async function submit() {
  if (!f.productSkuId || !f.planQty) {
    ElMessage.warning(t('wo.needProduct'))
    return
  }
  saving.value = true
  try {
    const body = {
      productSkuId: f.productSkuId,
      planQty: f.planQty,
      lineCode: f.lineCode || null,
      deviceCode: f.deviceCode || null,
      warehouseId: f.warehouseId || null,
      materialWhId: f.materialWhId || null,
      srcOrderNo: f.srcOrderNo || null,
      autoBackflush: f.autoBackflush,
      planStart: f.planStart ? f.planStart.getTime() : null,
      planEnd: f.planEnd ? f.planEnd.getTime() : null,
      remark: f.remark || null
    }
    const r = editing.value && detail.value
      ? await workOrderApi.update(detail.value.orderNo, body)
      : await workOrderApi.create(body)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('wo.saved'))
    dlg.value = false
    await load()
    if (drawer.value && detail.value) await openDetail(detail.value.orderNo)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

async function act(row: WorkOrderRow | WorkOrderDetail, action: 'release' | 'start' | 'finish' | 'cancel') {
  let reason: string | undefined
  if (action === 'cancel') {
    try {
      const r = await ElMessageBox.prompt(t('wo.cancelReasonPh'), t('wo.actCancel'), {
        inputPlaceholder: t('wo.cancelReasonHint'),
        confirmButtonText: t('base.save'),
        cancelButtonText: t('base.cancel')
      })
      reason = r.value || ''
    } catch {
      return
    }
  }
  if (action === 'finish') {
    try {
      await ElMessageBox.confirm(t('wo.finishConfirm'), t('wo.actFinish'), {
        confirmButtonText: t('base.save'),
        cancelButtonText: t('base.cancel'),
        type: 'warning'
      })
    } catch {
      return
    }
  }
  try {
    const r = action === 'release' ? await workOrderApi.release(row.orderNo)
      : action === 'start' ? await workOrderApi.start(row.orderNo)
      : action === 'finish' ? await workOrderApi.finish(row.orderNo)
      : await workOrderApi.cancel(row.orderNo, reason)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('wo.done'))
    await load()
    if (drawer.value) await openDetail(row.orderNo)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

async function openIssue(orderNo: string) {
  issueNo.value = orderNo
  issueDlg.value = true
  issueWh.value = null
  try {
    const d = await workOrderApi.detail(orderNo)
    if (!d || !d.orderNo) {
      ElMessage.error((d as unknown as { message?: string })?.message || t('common.reqFail'))
      issueDlg.value = false
      return
    }
    issueLines.value = (d.materials || []).map((m) => ({
      skuId: m.skuId,
      skuCode: m.skuCode,
      skuName: m.skuName,
      gapQty: m.gapQty,
      stockQty: m.stockQty ?? null,
      // 默认填缺口（大于 0 才填），车间一般就是按缺口领
      qty: m.gapQty > 0 ? m.gapQty : 0
    }))
    const withWh = (d.materials || []).find((m) => m.warehouseId)
    issueWh.value = withWh?.warehouseId ?? (warehouses.value[0]?.id ?? null)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  }
}

async function doIssue() {
  const pick = issueLines.value.filter((l) => l.qty > 0)
  if (!pick.length) {
    ElMessage.warning(t('wo.needQty'))
    return
  }
  if (!issueWh.value) {
    ElMessage.warning(t('wo.pickWh'))
    return
  }
  saving.value = true
  try {
    const r = await workOrderApi.issue(issueNo.value, issueWh.value, pick.map((l) => ({ skuId: l.skuId, qty: l.qty })))
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('wo.issued'))
    issueDlg.value = false
    await load()
    if (drawer.value) await openDetail(issueNo.value)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

async function doAutoIssue() {
  saving.value = true
  try {
    const r = await workOrderApi.autoIssue(issueNo.value, issueWh.value)
    if (!r.ok) {
      ElMessage.error(r.message || t('common.reqFail'))
      return
    }
    ElMessage.success(r.message || t('wo.issued'))
    issueDlg.value = false
    await load()
    if (drawer.value) await openDetail(issueNo.value)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('common.reqFail'))
  } finally {
    saving.value = false
  }
}

const day = (ts: number) => {
  const d = new Date(ts)
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')}`
}
const dt = (ts: number) => {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(async () => {
  const [a, b, c] = await Promise.allSettled([skuApi.list(), warehouseApi.list(), lineApi.list()])
  if (a.status === 'fulfilled') skus.value = a.value || []
  if (b.status === 'fulfilled') warehouses.value = b.value || []
  if (c.status === 'fulfilled') {
    const r = c.value as unknown as { rows?: { code: string; name: string }[] }
    lines.value = (r?.rows || []).filter((l) => (l as { enabled?: boolean }).enabled !== false)
  }
  try {
    const dv = await devicesApi.list()
    devices.value = (dv || []).map((d: { code: string; name: string }) => ({ code: d.code, name: d.name }))
  } catch {
    devices.value = []
  }
  await load()
})
</script>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.kpi {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  padding: 10px 14px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.kpi:hover {
  border-color: var(--accent);
}
.kpi.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.kpi-label {
  font-size: 12px;
  color: var(--muted);
}
.kpi-value {
  margin-top: 2px;
  font-size: 22px;
  font-weight: 600;
  color: var(--fg);
  line-height: 1.2;
}
.kpi-warn .kpi-value {
  color: var(--danger);
}
.card {
  border-radius: var(--radius-md);
}
.grow {
  flex: 1;
}
.w160 { width: 160px; }
.w180 { width: 180px; }
.w240 { width: 240px; }
.w-full { width: 100%; }
.ml8 { margin-left: 8px; }
.cell-main {
  font-size: 13px;
  color: var(--fg);
}
.cell-sub {
  font-size: 11px;
  color: var(--muted);
}
.prog {
  display: flex;
  align-items: center;
  gap: 8px;
}
.prog :deep(.el-progress) {
  flex: 1;
}
.prog-text {
  font-size: 12px;
  color: var(--fg-2);
  white-space: nowrap;
}
.num {
  font-family: var(--font-mono);
}
.danger { color: var(--danger); }
.warn { color: var(--idle); }
.muted { color: var(--muted); }
.strong { font-weight: 600; }
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.hint {
  margin: 8px 2px 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
.shortage-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 2px 2px 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--fg);
}
.dt-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.dt-no {
  font-size: 16px;
  font-weight: 600;
  color: var(--fg);
}
.dt-flag {
  font-size: 12px;
  font-weight: 600;
}
.dt-desc {
  margin-bottom: 16px;
}
.dt-sec {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 12px 0 6px;
}
.dt-sec-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg);
}
.dt-sec-sub {
  font-size: 12px;
  color: var(--muted);
}
.frow {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.cost-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.cost-cell {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  background: var(--surface-2);
}
.cost-cell.cc-total {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.cc-label {
  display: block;
  font-size: 11px;
  color: var(--muted);
}
.cc-val {
  display: block;
  margin-top: 2px;
  font-size: 15px;
  font-weight: 600;
  color: var(--fg);
}
.cc-sub {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}
.cc-sub b {
  font-family: var(--font-mono);
}
.cost-note {
  margin: 6px 2px 0;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.5;
}
.issue-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.field-hint {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.4;
}
.opt-code {
  display: inline-block;
  min-width: 82px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--muted);
}
</style>
