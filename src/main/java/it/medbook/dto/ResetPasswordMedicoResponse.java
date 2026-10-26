package it.medbook.dto;

/* Nessun segreto, hash, verificatore di credenziali o generazione esposto. */
public record ResetPasswordMedicoResponse(int idMedico, String message, boolean sessioniInvalidate) { }
