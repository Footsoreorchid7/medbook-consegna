package it.medbook.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import it.medbook.model.StatoRimborso;

public class RichiestaRimborsoRequest {
    private String motivazione;
    public String getMotivazione() { return motivazione; }
    public void setMotivazione(String motivazione) { this.motivazione = motivazione; }
}
