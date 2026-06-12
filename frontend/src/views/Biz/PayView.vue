<template>
  <div class="pay-page">
    <div class="card">
      <div class="badge">SPARROW 模拟支付网关 · SANDBOX</div>
      <h2>{{ productName }}</h2>
      <div class="amount">¥{{ amount }}</div>
      <div class="order-no">订单号: {{ orderNo }}</div>
      <button :disabled="paid" @click="doPay">{{ paid ? '已支付' : '模拟支付成功' }}</button>
      <div v-if="paid" class="ok">✅ 支付成功,会员已开通</div>
      <router-link class="back" to="/">← 返回科技树</router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { post } from '../../api/request'

const route = useRoute()
const qs = route.query
const orderNo = computed(() => (qs.orderNo as string) || '')
const payToken = computed(() => (qs.payToken as string) || '')
const productName = computed(() => {
  const names: Record<string, string> = { MEMBER_MONTH: 'Sparrow 月度会员', MEMBER_YEAR: 'Sparrow 年度会员' }
  return names[(qs.product as string) || ''] || '商品'
})
const amount = computed(() => ((Number(qs.amount) || 0) / 100).toFixed(2))
const paid = ref(false)

async function doPay() {
  try {
    await post<{ orderNo: string; processed: boolean }>('/api/pay/mock/notify', {
      orderNo: orderNo.value, payToken: payToken.value,
    })
    paid.value = true
  } catch (e: any) { alert(e.message || '支付失败') }
}
</script>

<style scoped>
.pay-page {
  background: var(--bg); color: var(--ink); height: 100vh;
  display: flex; align-items: center; justify-content: center;
}
.card {
  width: 360px; background: var(--bg-2); border: 1px solid var(--line);
  border-radius: 16px; padding: 32px; text-align: center;
}
.badge { font-size: 12px; color: var(--ink-2); letter-spacing: 0.1em; }
h2 { margin: 10px 0 4px; }
.amount { font-size: 38px; font-weight: 700; color: var(--gold); margin: 18px 0 6px; }
.order-no { font-size: 12.5px; color: var(--ink-2); margin-bottom: 26px; word-break: break-all; }
button {
  width: 100%; padding: 12px; border-radius: 10px; border: none;
  background: var(--accent); color: #07101f; font-size: 16px; font-weight: 600; cursor: pointer;
}
button:disabled { opacity: 0.5; cursor: default; }
.back { display: inline-block; margin-top: 16px; color: var(--accent); font-size: 14px; text-decoration: none; }
.ok { color: #6fd388; font-size: 15px; margin-top: 14px; }
</style>
