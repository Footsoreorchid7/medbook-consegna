package it.medbook.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import it.medbook.model.StatoRimborso;

public class DecisioneRimborsoResponse {
    private Integer idRimborso;
    private StatoRimborso stato;
    private Integer idOperatore;
    private LocalDateTime verificatoIl;
    public Integer getIdRimborso() { return idRimborso; }
    public void setIdRimborso(Integer idRimborso) { this.idRimborso = idRimborso; }
    public StatoRimborso getStato() { return stato; }
    public void setStato(StatoRimborso stato) { this.stato = stato; }
    public Integer getIdOperatore() { return idOperatore; }
    public void setIdOperatore(Integer idOperatore) { this.idOperatore = idOperatore; }
    public LocalDateTime getVerificatoIl() { return verificatoIl; }
    public void setVerificatoIl(LocalDateTime verificatoIl) { this.verificatoIl = verificatoIl; }
}
