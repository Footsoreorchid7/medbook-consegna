package it.medbook.dto;

import java.time.LocalDateTime;

/** Metadati pubblici UC-09: nessuna chiave storage. */
public record RefertoResponse(Integer idReferto, Integer idPrenotazione,
                              String nomeFile, LocalDateTime caricatoIl) { }
