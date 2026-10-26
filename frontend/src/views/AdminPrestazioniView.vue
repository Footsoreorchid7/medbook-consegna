<script setup>
import CatalogoAdmin from '../components/CatalogoAdmin.vue'
import { PRESTAZIONI_CONFIG } from '../admin/cataloghi.js'

/** Prestazioni : catalogo, creazione e modifica completa; nessuna eliminazione né stato attivo/inattivo. */
</script>

<template>
  <CatalogoAdmin :config="PRESTAZIONI_CONFIG" />
</template>
