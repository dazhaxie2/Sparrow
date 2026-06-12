<template>
  <Teleport to="body">
    <div class="modal" @click.self="$emit('close')">
      <div class="modal-box">
        <h3>👑 开通 Sparrow 会员</h3>
        <p class="muted">解锁全部深度内容 + AI 向导无限次问答</p>
        <div class="products">
          <div class="product" @click="buy('MEMBER_MONTH')">
            <div class="p-name">月度会员</div>
            <div class="p-price">¥9.90</div>
            <div class="p-days">30 天</div>
          </div>
          <div class="product" @click="buy('MEMBER_YEAR')">
            <div class="p-name">年度会员</div>
            <div class="p-price">¥99.00</div>
            <div class="p-days">365 天</div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn ghost" @click="$emit('close')">再想想</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { createOrder } from '../api/trade'
import { useUserStore } from '../stores/user'

const emit = defineEmits<{ close: [] }>()
const user = useUserStore()

async function buy(code: string) {
  if (!user.isLoggedIn()) {
    emit('close')
    return
  }
  try {
    const res = await createOrder(code)
    window.location.href = res.payUrl
  } catch (e: any) {
    alert(e.message)
  }
}
</script>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  background: rgba(5, 9, 18, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal-box {
  width: 360px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 24px;
}
.modal-box h3 {
  margin-bottom: 14px;
}
.muted {
  color: var(--ink-2);
  font-size: 13px;
  margin-bottom: 14px;
}
.products {
  display: flex;
  gap: 12px;
}
.product {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px;
  text-align: center;
  cursor: pointer;
}
.product:hover {
  border-color: var(--gold);
  background: rgba(246, 194, 68, 0.06);
}
.p-name {
  font-size: 14px;
}
.p-price {
  font-size: 22px;
  font-weight: 700;
  color: var(--gold);
  margin: 8px 0 4px;
}
.p-days {
  font-size: 12.5px;
  color: var(--ink-2);
}
.modal-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.btn.ghost {
  background: transparent;
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 14px;
  cursor: pointer;
}
</style>
