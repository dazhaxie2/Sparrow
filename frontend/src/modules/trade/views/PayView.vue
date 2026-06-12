<template>
  <div class="pay-shell">
    <header class="pay-header">
      <router-link class="brand-link" to="/">SPARROW</router-link>
      <span>模拟支付网关 / SANDBOX</span>
    </header>

    <main class="pay-page">
      <section class="checkout-card">
        <div class="badge">CHECKOUT REQUEST</div>
        <h1>{{ productName }}</h1>
        <div class="amount">¥{{ amount }}</div>

        <div class="order-box">
          <span>订单号</span>
          <strong>{{ orderNo || 'N/A' }}</strong>
        </div>

        <button class="pay-btn" type="button" :disabled="paid" @click="doPay">
          {{ paid ? '已支付' : '模拟支付成功' }}
        </button>
        <div v-if="paid" class="ok">支付成功，会员已开通。</div>
        <router-link class="back" to="/">返回科技树</router-link>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { post } from '../../../shared/api/request'

const route = useRoute()
const qs = route.query
const orderNo = computed(() => (qs.orderNo as string) || '')
const payToken = computed(() => (qs.payToken as string) || '')
const productName = computed(() => {
  const names: Record<string, string> = {
    MEMBER_MONTH: 'Sparrow 月度会员',
    MEMBER_YEAR: 'Sparrow 年度会员',
  }
  return names[(qs.product as string) || ''] || '商品'
})
const amount = computed(() => ((Number(qs.amount) || 0) / 100).toFixed(2))
const paid = ref(false)

async function doPay() {
  try {
    await post<{ orderNo: string; processed: boolean }>('/api/pay/mock/notify', {
      orderNo: orderNo.value,
      payToken: payToken.value,
    })
    paid.value = true
  } catch (error: any) {
    alert(error.message || '支付失败')
  }
}
</script>

<style scoped>
.pay-shell {
  min-height: 100vh;
  background: var(--bg);
  color: var(--ink);
}

.pay-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}

.brand-link {
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  letter-spacing: 0.12em;
  text-decoration: none;
}

.pay-header span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.pay-page {
  min-height: calc(100vh - 64px);
  display: grid;
  place-items: center;
  padding: 24px;
}

.checkout-card {
  width: min(420px, 100%);
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 30px;
  text-align: center;
}

.badge {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

h1 {
  margin-top: 10px;
  font-size: 26px;
  letter-spacing: 0;
}

.amount {
  margin: 20px 0 16px;
  color: var(--ink);
  font-size: 46px;
  font-weight: 900;
  letter-spacing: 0;
}

.order-box {
  display: grid;
  gap: 8px;
  margin-bottom: 22px;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 12px;
  text-align: left;
}

.order-box span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.order-box strong {
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  word-break: break-all;
}

.pay-btn {
  width: 100%;
  min-height: 44px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  font-size: 15px;
  font-weight: 800;
  cursor: pointer;
}

.pay-btn:hover {
  background: var(--accent);
  border-color: var(--accent);
}

.pay-btn:disabled {
  border-color: var(--line-strong);
  background: #d8d8d8;
  color: var(--muted);
  cursor: default;
}

.ok {
  margin-top: 14px;
  color: var(--success);
  font-size: 14px;
}

.back {
  display: inline-block;
  margin-top: 16px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}
</style>
