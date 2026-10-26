package it.medbook.dto;

public record AccountInternoRequest(String nome, String cognome, String email, String password, it.medbook.model.RuoloGestionale ruoloGestionale) { }
