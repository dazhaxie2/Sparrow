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

        <p v-if="errorMessage" class="form-error" role="alert">
          <AlertTriangle :size="16" aria-hidden="true" />
          <span>{{ errorMessage }}</span>
        </p>

        <button class="pay-btn" type="button" :disabled="paid || submitting" @click="doPay">
          <CircleCheck v-if="paid" :size="18" aria-hidden="true" />
          <LoaderCircle v-else-if="submitting" class="spin" :size="18" aria-hidden="true" />
          <CreditCard v-else :size="18" aria-hidden="true" />
          <span>{{ buttonText }}</span>
        </button>

        <div v-if="paid" class="ok">
          <CircleCheck :size="16" aria-hidden="true" />
          <span>支付成功，会员已开通。</span>
        </div>

        <router-link class="back" to="/">
          <ArrowLeft :size="15" aria-hidden="true" />
          <span>返回科技树</span>
        </router-link>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { AlertTriangle, ArrowLeft, CircleCheck, CreditCard, LoaderCircle } from '@lucide/vue'
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
const submitting = ref(false)
const errorMessage = ref('')
const buttonText = computed(() => {
  if (paid.value) return '已支付'
  if (submitting.value) return '正在确认支付'
  return '模拟支付成功'
})

async function doPay() {
  if (submitting.value || paid.value) return

  errorMessage.value = ''
  submitting.value = true

  try {
    await post<{ orderNo: string; processed: boolean }>('/api/pay/mock/notify', {
      orderNo: orderNo.value,
      payToken: payToken.value,
    })
    paid.value = true
  } catch (error: any) {
    errorMessage.value = error.message || '支付失败，请稍后重试'
  } finally {
    submitting.value = false
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
  margin-bottom: 18px;
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

.form-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 14px;
  border: 1px solid rgba(185, 28, 28, 0.3);
  background: #fff2f2;
  color: #9f1239;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.45;
  text-align: left;
}

.form-error svg {
  flex: 0 0 auto;
  margin-top: 1px;
}

.pay-btn {
  width: 100%;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: var(--bg);
  font-size: 15px;
  font-weight: 800;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    transform 0.18s ease;
}

.pay-btn:hover:not(:disabled) {
  background: var(--accent);
  border-color: var(--accent);
  transform: translateY(-1px);
}

.pay-btn:disabled {
  border-color: var(--line-strong);
  background: #d8d8d8;
  color: var(--muted);
  cursor: default;
}

.spin {
  animation: spin 0.8s linear infinite;
}

.ok {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 14px;
  color: var(--success);
  font-size: 14px;
  font-weight: 800;
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
