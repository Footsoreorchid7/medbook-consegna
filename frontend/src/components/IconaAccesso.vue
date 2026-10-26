<script setup>
defineProps({ nome: { type: String, default: 'elenco' } })
const tracciati = {
  calendario: 'M5 5h14v15H5z M5 9h14 M8 3v4 M16 3v4 M8 13h2 M14 13h2 M8 17h2',
  elenco: 'M5 3h14v18H5z M8 7h8 M8 12h8 M8 17h5',
  rimborso: 'M4 7h16v13H4z M4 10h16 M9 4l-3 3 3 3 M6 7h8 M8 15h3',
  orari: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l4 2',
  avviso: 'M12 3L2 21h20L12 3z M12 9v5 M12 17v1',
  persone: 'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M2 21v-3a7 7 0 0 1 14 0v3 M17 4a4 4 0 0 1 0 8 M19 15a5 5 0 0 1 3 4v2',
  stanze: 'M4 21V3h12v18 M2 21h20 M16 8h4v13 M11 12h1',
}
</script>
<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="tracciati[nome] ?? tracciati.elenco" />
  </svg>
</template>
