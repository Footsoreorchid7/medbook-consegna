<script setup>
import CatalogoAdmin from '../components/CatalogoAdmin.vue'
import { STANZE_CONFIG } from '../admin/cataloghi.js'

/** Stanze : elenco, creazione e modifica completa; nessuna eliminazione né lettura singola. */
</script>

<template>
  <CatalogoAdmin :config="STANZE_CONFIG" />
</template>
