<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <div class="modal-box">
        <div class="modal-head">
          <div>
            <span class="eyebrow">SPARROW MEMBERSHIP</span>
            <h3>开通 Sparrow 会员</h3>
          </div>
          <button class="icon-btn" type="button" aria-label="关闭" @click="$emit('close')">×</button>
        </div>

        <p class="muted">解锁全部深度内容，并获得 AI 向导的更完整问答体验。</p>
        <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

        <div class="products">
          <button class="product" type="button" @click="buy('MEMBER_MONTH')">
            <span class="p-code">MONTH</span>
            <span class="p-name">月度会员</span>
            <strong>¥9.90</strong>
            <span class="p-days">30 天</span>
          </button>
          <button class="product featured" type="button" @click="buy('MEMBER_YEAR')">
            <span class="p-code">YEAR</span>
            <span class="p-name">年度会员</span>
            <strong>¥99.00</strong>
            <span class="p-days">365 天</span>
          </button>
        </div>

        <div class="modal-actions">
          <button class="btn ghost" type="button" @click="$emit('close')">再想想</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { createOrder } from '../api'
import { useUserStore } from '../../user/store'

const emit = defineEmits<{ close: [] }>()
const user = useUserStore()
const errorMessage = ref('')

async function buy(code: string) {
  errorMessage.value = ''
  if (!user.isLoggedIn()) {
    emit('close')
    return
  }
  try {
    const res = await createOrder(code)
    window.location.href = res.payUrl
  } catch (error: any) {
    errorMessage.value = error.message
  }
}
</script>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(2px);
  z-index: 100;
}

.modal-box {
  width: min(520px, calc(100vw - 32px));
  border: 1px solid var(--ink);
  background: var(--panel);
  box-shadow: var(--shadow-md);
  padding: 22px;
}

.modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.eyebrow {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.modal-head h3 {
  margin-top: 6px;
  font-size: 24px;
  letter-spacing: 0;
}

.icon-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  cursor: pointer;
}

.muted {
  margin-top: 14px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.8;
}

.form-error {
  margin-top: 12px;
  border: 1px solid rgba(220, 38, 38, 0.32);
  background: rgba(220, 38, 38, 0.06);
  color: var(--danger);
  padding: 9px 10px;
  font-size: 13px;
  line-height: 1.6;
}

.products {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.product {
  display: grid;
  gap: 7px;
  min-height: 150px;
  border: 1px solid var(--line-strong);
  background: var(--surface);
  padding: 16px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.product:hover,
.product.featured {
  border-color: var(--accent);
  background: rgba(255, 87, 34, 0.06);
}

.p-code {
  color: var(--accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.p-name {
  color: var(--ink);
  font-size: 15px;
  font-weight: 800;
}

.product strong {
  font-size: 30px;
  letter-spacing: 0;
}

.p-days {
  color: var(--muted);
  font-size: 12px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.btn {
  min-height: 36px;
  border: 1px solid var(--line-strong);
  background: var(--panel);
  color: var(--ink);
  padding: 0 13px;
  font-size: 13px;
  cursor: pointer;
}

.btn:hover,
.icon-btn:hover {
  border-color: var(--ink);
}

.btn.ghost {
  background: transparent;
}

@media (max-width: 560px) {
  .products {
    grid-template-columns: 1fr;
  }
}
</style>
