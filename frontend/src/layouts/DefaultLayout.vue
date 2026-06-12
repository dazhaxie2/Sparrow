<template>
  <AppHeader />
  <main class="layout-content">
    <slot />
  </main>
</template>

<script setup lang="ts">
import AppHeader from '../components/common/AppHeader.vue'
</script>

<style scoped>
.layout-content {
  height: calc(100vh - 56px);
}
</style>
